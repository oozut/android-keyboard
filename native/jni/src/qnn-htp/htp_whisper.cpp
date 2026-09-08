// Whisper-on-HTP via ONNX Runtime's C API (the Java API lacks UINT16/UINT8
// tensor support). Runs Qualcomm AI Hub's precompiled QNN context binaries for
// Whisper-Large-V3-Turbo-Quantized (w8a16, qcs8550-proxy):
//   4 layers, 20 KV heads, 128-mel, uint16 activations / uint8 KV,
//   vocab 51866, <|nospeech|> = 50363, 100 language tokens (50259..50358)
//
// Fixed-shape KV-cache decode: 200-token window, right-aligned, one token per
// Run; self-KV outputs feed back verbatim (identical in/out quantization).
// Greedy = argmax over raw logits (affine quantization is monotonic).
// libonnxruntime.so is dlopen'd — no link-time dependency.
//
// Ported from whisper-htp-android (MIT):
//   https://github.com/thedevguy/whisper-htp-android

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <string.h>
#include <time.h>

#include <string>
#include <vector>
#include <atomic>

#include "onnxruntime_c_api.h"

#define TAG "QnnWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Mel front end (plain C++, see whisper_mel.cpp).
namespace mel {
bool init(const char* filtersPath, int nMel);
bool compute(const float* pcm, size_t nSamples, int nMel, int16_t* out);
int numMelBins();
}  // namespace mel

namespace {

constexpr int WINDOW = 200;  // decoder attention window (199 cache + current)
constexpr int CACHE = 199;
constexpr int HEAD_DIM = 64;
constexpr int MAX_LAYERS = 12;
constexpr int EOT = 50257;  // same in v2 and v3 vocabs; also the argmax cutoff

struct ModelCfg {
    int layers;
    int kvHeads;
    int melBins;
    bool fp16;  // false = w8a16 (uint16 activations / uint8 KV)
    int vocab;
    int noSpeechId;  // <|nospeech|>: 50362 in the v2 vocab, 50363 in v3
    int langCount;   // language tokens start at 50259: 99 in v2, 100 in v3
    float logitsScale;  // affine scale for w8a16 logits (unused for fp16)
};
constexpr ModelCfg CFG_SMALL = {12, 12, 80, false, 51865, 50362, 99, 0.0012925398768857121f};
constexpr ModelCfg CFG_TURBO = {4, 20, 128, true, 51866, 50363, 100, 0.0f};
constexpr ModelCfg CFG_TURBO_W8A16 = {4, 20, 128, false, 51866, 50363, 100, 0.0007861469639465213f};

// w8a16 affine params (metadata.json); fp16 variant needs none
constexpr uint16_t MASK_ATTEND_U16 = 65535;  // dequantizes to 0.0
constexpr uint16_t MASK_OFF_U16 = 0;         // dequantizes to -100.0
constexpr uint8_t KV_ZP_U8 = 128;

std::string g_lastError;

void setError(const std::string& msg) { g_lastError = msg; }

struct Handle {
    void* ortLib = nullptr;
    const OrtApi* api = nullptr;
    OrtEnv* env = nullptr;
    OrtSession* enc = nullptr;
    OrtSession* dec = nullptr;
    OrtMemoryInfo* mem = nullptr;
    ModelCfg cfg = CFG_TURBO_W8A16;
    OrtValue* cross[2 * MAX_LAYERS] = {};  // encoder outputs, constant per segment
    std::vector<uint8_t> selfKV[2 * MAX_LAYERS];
    int32_t inputId = 0;
    int32_t positionId = 0;
    uint16_t mask[WINDOW] = {};  // raw bits (uint16 or fp16 per variant)
    std::atomic<bool> cancelRequested{false};

    size_t kvElem() const { return cfg.fp16 ? 2 : 1; }
    size_t kvBytes() const { return (size_t)cfg.kvHeads * CACHE * HEAD_DIM * kvElem(); }
    ONNXTensorElementDataType kvType() const {
        return cfg.fp16 ? ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16 : ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT8;
    }
    ONNXTensorElementDataType actType() const {  // mel, mask, logits
        return cfg.fp16 ? ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16 : ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT16;
    }
};

uint16_t fp16Bits(float f) {
    __fp16 h = (__fp16)f;
    uint16_t b;
    memcpy(&b, &h, 2);
    return b;
}

float fromFp16(uint16_t b) {
    __fp16 h;
    memcpy(&h, &b, 2);
    return (float)h;
}

bool check(const OrtApi* api, OrtStatus* st, const char* what) {
    if (!st) return true;
    const char* msg = api->GetErrorMessage(st);
    LOGE("%s failed: %s", what, msg);
    setError(std::string(what) + ": " + msg);
    api->ReleaseStatus(st);
    return false;
}

OrtSession* createQnnSession(Handle* h, const char* modelPath, const std::string& libDir) {
    const OrtApi* api = h->api;
    OrtSessionOptions* opts = nullptr;
    if (!check(api, api->CreateSessionOptions(&opts), "CreateSessionOptions")) return nullptr;
    api->SetSessionGraphOptimizationLevel(opts, ORT_DISABLE_ALL);
    std::string backend = libDir + "/libQnnHtp.so";
    // high_performance (NOT burst): burst drove the passively-cooled reference
    // device to thermal-emergency; high_performance keeps most of the speed at
    // sustainable clocks.
    const char* keys[] = {"backend_path", "htp_performance_mode"};
    const char* vals[] = {backend.c_str(), "high_performance"};
    if (!check(api, api->SessionOptionsAppendExecutionProvider(opts, "QNN", keys, vals, 2),
               "AppendExecutionProvider(QNN)")) {
        api->ReleaseSessionOptions(opts);
        return nullptr;
    }
    OrtSession* session = nullptr;
    bool ok = check(api, api->CreateSession(h->env, modelPath, opts, &session), modelPath);
    api->ReleaseSessionOptions(opts);
    return ok ? session : nullptr;
}

void resetSelfKV(Handle* h) {
    for (int i = 0; i < 2 * h->cfg.layers; i++) {
        h->selfKV[i].assign(h->kvBytes(), h->cfg.fp16 ? 0 : KV_ZP_U8);
    }
}

void destroy(Handle* h) {
    if (!h) return;
    if (h->api) {
        for (auto*& v : h->cross)
            if (v) h->api->ReleaseValue(v);
        if (h->mem) h->api->ReleaseMemoryInfo(h->mem);
        if (h->enc) h->api->ReleaseSession(h->enc);
        if (h->dec) h->api->ReleaseSession(h->dec);
        if (h->env) h->api->ReleaseEnv(h->env);
    }
    if (h->ortLib) dlclose(h->ortLib);
    delete h;
}

}  // namespace

// ---------------------------------------------------------------- JNI

extern "C" {

static jlong nativeInit(JNIEnv* env, jobject, jstring jLibDir, jstring jEncPath,
                        jstring jDecPath, jint variant) {
    g_lastError.clear();
    const char* c;
    c = env->GetStringUTFChars(jLibDir, nullptr);  std::string libDir = c;  env->ReleaseStringUTFChars(jLibDir, c);
    c = env->GetStringUTFChars(jEncPath, nullptr); std::string encPath = c; env->ReleaseStringUTFChars(jEncPath, c);
    c = env->GetStringUTFChars(jDecPath, nullptr); std::string decPath = c; env->ReleaseStringUTFChars(jDecPath, c);

    auto* h = new Handle();
    switch (variant) {
        case 0: h->cfg = CFG_SMALL; break;
        case 1: h->cfg = CFG_TURBO; break;
        default: h->cfg = CFG_TURBO_W8A16; break;
    }
    std::string ortPath = libDir + "/libonnxruntime.so";
    h->ortLib = dlopen(ortPath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!h->ortLib) {
        LOGE("dlopen(%s): %s", ortPath.c_str(), dlerror());
        setError(std::string("dlopen libonnxruntime.so: ") + dlerror());
        destroy(h);
        return 0;
    }
    auto getApiBase = reinterpret_cast<const OrtApiBase* (*)()>(dlsym(h->ortLib, "OrtGetApiBase"));
    if (!getApiBase) {
        LOGE("dlsym OrtGetApiBase: %s", dlerror());
        setError("dlsym OrtGetApiBase failed");
        destroy(h);
        return 0;
    }
    h->api = getApiBase()->GetApi(ORT_API_VERSION);
    if (!h->api) {
        LOGE("GetApi(%d) returned null (runtime older than header?)", ORT_API_VERSION);
        setError("ORT API version mismatch");
        destroy(h);
        return 0;
    }
    const OrtApi* api = h->api;
    if (!check(api, api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "QnnWhisper", &h->env), "CreateEnv") ||
        !check(api, api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &h->mem),
               "CreateCpuMemoryInfo")) {
        destroy(h);
        return 0;
    }

    h->enc = createQnnSession(h, encPath.c_str(), libDir);
    if (!h->enc) { destroy(h); return 0; }
    h->dec = createQnnSession(h, decPath.c_str(), libDir);
    if (!h->dec) { destroy(h); return 0; }
    LOGI("sessions created (variant=%d: %d layers, %d kv-heads, %d mel bins, %s)", variant,
         h->cfg.layers, h->cfg.kvHeads, h->cfg.melBins, h->cfg.fp16 ? "fp16" : "w8a16");

    resetSelfKV(h);
    return reinterpret_cast<jlong>(h);
}

static void nativeFree(JNIEnv*, jobject, jlong jh) {
    destroy(reinterpret_cast<Handle*>(jh));
}

static void nativeCancel(JNIEnv*, jobject, jlong jh) {
    auto* h = reinterpret_cast<Handle*>(jh);
    if (h) h->cancelRequested.store(true);
}

static jstring nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_lastError.c_str());
}

static jboolean nativeMelInit(JNIEnv* env, jobject, jstring jFiltersPath, jint nMel) {
    const char* c = env->GetStringUTFChars(jFiltersPath, nullptr);
    bool ok = mel::init(c, nMel);
    env->ReleaseStringUTFChars(jFiltersPath, c);
    return ok ? JNI_TRUE : JNI_FALSE;
}

static jshortArray nativeMel(JNIEnv* env, jobject, jfloatArray jPcm) {
    int nMel = mel::numMelBins();
    if (nMel <= 0) {
        setError("mel init not called");
        return nullptr;
    }
    jsize n = env->GetArrayLength(jPcm);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(jPcm, 0, n, pcm.data());

    std::vector<int16_t> out((size_t)nMel * 3000);
    if (!mel::compute(pcm.data(), (size_t)n, nMel, out.data())) {
        setError("mel compute failed");
        return nullptr;
    }
    jshortArray res = env->NewShortArray((jsize)out.size());
    env->SetShortArrayRegion(res, 0, (jsize)out.size(), out.data());
    return res;
}

static jintArray nativeTranscribe(JNIEnv* env, jobject, jlong jh, jshortArray jMel,
                                  jintArray jPrompt, jfloatArray jMetrics) {
    auto* h = reinterpret_cast<Handle*>(jh);
    if (!h) return nullptr;
    const OrtApi* api = h->api;
    const ModelCfg& cfg = h->cfg;
    const int L = cfg.layers;
    const int VOCAB_ARGMAX = EOT;  // inclusive upper bound of sampled ids

    // ------------------------------------------------------------- encoder
    jsize melLen = env->GetArrayLength(jMel);
    if (melLen != cfg.melBins * 3000) {
        setError("mel length mismatch");
        LOGE("mel length %d != %d", melLen, cfg.melBins * 3000);
        return nullptr;
    }
    std::vector<uint16_t> mel(melLen);
    env->GetShortArrayRegion(jMel, 0, melLen, reinterpret_cast<jshort*>(mel.data()));

    for (auto*& v : h->cross) {
        if (v) api->ReleaseValue(v);
        v = nullptr;
    }

    int64_t melShape[] = {1, cfg.melBins, 3000};
    OrtValue* melVal = nullptr;
    if (!check(api,
               api->CreateTensorWithDataAsOrtValue(h->mem, mel.data(), mel.size() * 2, melShape, 3,
                                                   h->actType(), &melVal),
               "create mel tensor"))
        return nullptr;

    std::vector<std::string> encOutStore;
    for (int i = 0; i < L; i++) encOutStore.push_back("k_cache_cross_" + std::to_string(i));
    for (int i = 0; i < L; i++) encOutStore.push_back("v_cache_cross_" + std::to_string(i));
    std::vector<const char*> encOut;
    for (auto& s : encOutStore) encOut.push_back(s.c_str());
    const char* encIn[] = {"input_features"};

    struct timespec ts0, ts1;
    clock_gettime(CLOCK_MONOTONIC, &ts0);
    OrtStatus* st = api->Run(h->enc, nullptr, encIn, (const OrtValue* const*)&melVal, 1,
                             encOut.data(), encOut.size(), h->cross);
    clock_gettime(CLOCK_MONOTONIC, &ts1);
    api->ReleaseValue(melVal);
    if (!check(api, st, "encoder Run")) return nullptr;
    double encMs = (ts1.tv_sec - ts0.tv_sec) * 1e3 + (ts1.tv_nsec - ts0.tv_nsec) / 1e6;
    LOGI("encoder: %.1f ms on HTP", encMs);

    // ------------------------------------------------------------- decoder
    jsize promptLen = env->GetArrayLength(jPrompt);
    std::vector<int32_t> prompt(promptLen);
    env->GetIntArrayRegion(jPrompt, 0, promptLen, prompt.data());

    resetSelfKV(h);
    const uint16_t maskOff = cfg.fp16 ? fp16Bits(-100.0f) : MASK_OFF_U16;
    const uint16_t maskAttend = cfg.fp16 ? fp16Bits(0.0f) : MASK_ATTEND_U16;
    for (auto& m : h->mask) m = maskOff;

    std::vector<std::string> inNameStore;
    inNameStore.push_back("input_ids");
    inNameStore.push_back("attention_mask");
    for (int i = 0; i < L; i++) {
        inNameStore.push_back("k_cache_self_" + std::to_string(i) + "_in");
        inNameStore.push_back("v_cache_self_" + std::to_string(i) + "_in");
    }
    for (int i = 0; i < L; i++) {
        inNameStore.push_back("k_cache_cross_" + std::to_string(i));
        inNameStore.push_back("v_cache_cross_" + std::to_string(i));
    }
    inNameStore.push_back("position_ids");
    std::vector<const char*> inNames;
    for (auto& s : inNameStore) inNames.push_back(s.c_str());

    int64_t idShape[] = {1, 1};
    int64_t maskShape[] = {1, 1, 1, WINDOW};
    int64_t kShape[] = {cfg.kvHeads, 1, HEAD_DIM, CACHE};
    int64_t vShape[] = {cfg.kvHeads, 1, CACHE, HEAD_DIM};
    int64_t posShape[] = {1};

    std::vector<OrtValue*> inVals;
    bool ok = true;
    OrtValue* val = nullptr;
    ok = ok && check(api, api->CreateTensorWithDataAsOrtValue(h->mem, &h->inputId, 4, idShape, 2,
                                                              ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32, &val),
                     "input_ids tensor");
    inVals.push_back(val);
    ok = ok && check(api, api->CreateTensorWithDataAsOrtValue(h->mem, h->mask, WINDOW * 2, maskShape, 4,
                                                              h->actType(), &val),
                     "mask tensor");
    inVals.push_back(val);
    for (int i = 0; i < L && ok; i++) {
        ok = ok && check(api,
                         api->CreateTensorWithDataAsOrtValue(h->mem, h->selfKV[2 * i].data(),
                                                             h->kvBytes(), kShape, 4, h->kvType(), &val),
                         "self k tensor");
        inVals.push_back(val);
        ok = ok && check(api,
                         api->CreateTensorWithDataAsOrtValue(h->mem, h->selfKV[2 * i + 1].data(),
                                                             h->kvBytes(), vShape, 4, h->kvType(), &val),
                         "self v tensor");
        inVals.push_back(val);
    }
    if (ok) {
        for (int i = 0; i < L; i++) {
            inVals.push_back(h->cross[i]);      // k_cache_cross_i
            inVals.push_back(h->cross[L + i]);  // v_cache_cross_i
        }
        ok = check(api, api->CreateTensorWithDataAsOrtValue(h->mem, &h->positionId, 4, posShape, 1,
                                                            ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32, &val),
                   "position_ids tensor");
        inVals.push_back(val);
    }

    std::vector<std::string> outNameStore;
    outNameStore.push_back("logits");
    for (int i = 0; i < L; i++) {
        outNameStore.push_back("k_cache_self_" + std::to_string(i) + "_out");
        outNameStore.push_back("v_cache_self_" + std::to_string(i) + "_out");
    }
    std::vector<const char*> outNamesC;
    for (auto& s : outNameStore) outNamesC.push_back(s.c_str());

    std::vector<int32_t> generated;
    double decTotalMs = 0;
    int steps = 0;
    double probSum = 0;
    int probCount = 0;
    float noSpeechProb = 0.0f;

    if (ok) {
        h->cancelRequested.store(false);
        clock_gettime(CLOCK_MONOTONIC, &ts0);
        for (int step = 0; step < WINDOW - 1; step++) {
            if (h->cancelRequested.load()) { ok = false; break; }
            h->inputId = (step < promptLen) ? prompt[step] : generated.back();
            h->positionId = step;
            // Right-aligned attention window: current token at slot 199.
            h->mask[WINDOW - 1 - step] = maskAttend;

            std::vector<OrtValue*> outs(1 + 2 * L, nullptr);
            st = api->Run(h->dec, nullptr, inNames.data(), (const OrtValue* const*)inVals.data(),
                          inVals.size(), outNamesC.data(), outNamesC.size(), outs.data());
            if (!check(api, st, "decoder Run")) { ok = false; break; }
            steps++;

            for (int i = 0; i < 2 * L; i++) {
                void* data = nullptr;
                api->GetTensorMutableData(outs[1 + i], &data);
                memcpy(h->selfKV[i].data(), data, h->kvBytes());
            }

            // Whisper's no-speech signal lives in the logits at the
            // start-of-transcript position (step 0 feeds <|startoftranscript|>).
            if (step == 0) {
                void* ldata = nullptr;
                api->GetTensorMutableData(outs[0], &ldata);
                auto* logits = static_cast<uint16_t*>(ldata);
                double denom = 0, lns = 0;
                if (cfg.fp16) {
                    float mx = fromFp16(logits[0]);
                    for (int i = 1; i < cfg.vocab; i++) mx = fmaxf(mx, fromFp16(logits[i]));
                    for (int i = 0; i < cfg.vocab; i++) denom += exp((double)(fromFp16(logits[i]) - mx));
                    lns = exp((double)(fromFp16(logits[cfg.noSpeechId]) - mx));
                } else {
                    uint16_t mx = logits[0];
                    for (int i = 1; i < cfg.vocab; i++) mx = logits[i] > mx ? logits[i] : mx;
                    for (int i = 0; i < cfg.vocab; i++) denom += exp(((int)logits[i] - (int)mx) * cfg.logitsScale);
                    lns = exp(((int)logits[cfg.noSpeechId] - (int)mx) * cfg.logitsScale);
                }
                noSpeechProb = (float)(lns / denom);
            }
            if (step >= promptLen - 1) {
                void* ldata = nullptr;
                api->GetTensorMutableData(outs[0], &ldata);
                int best = 0;
                if (cfg.fp16) {
                    auto* logits = static_cast<uint16_t*>(ldata);
                    float bestV = fromFp16(logits[0]);
                    for (int i = 1; i <= VOCAB_ARGMAX; i++) {
                        float v = fromFp16(logits[i]);
                        if (v > bestV) { bestV = v; best = i; }
                    }
                    double denom = 0;
                    for (int i = 0; i <= VOCAB_ARGMAX; i++)
                        denom += exp((double)(fromFp16(logits[i]) - bestV));
                    probSum += 1.0 / denom;
                } else {
                    auto* logits = static_cast<uint16_t*>(ldata);
                    for (int i = 1; i <= VOCAB_ARGMAX; i++)
                        if (logits[i] > logits[best]) best = i;
                    double denom = 0;
                    for (int i = 0; i <= VOCAB_ARGMAX; i++)
                        denom += exp(((int)logits[i] - (int)logits[best]) * cfg.logitsScale);
                    probSum += 1.0 / denom;
                }
                probCount++;
                for (auto* o : outs) api->ReleaseValue(o);
                if (best == EOT) break;
                generated.push_back(best);
            } else {
                for (auto* o : outs) api->ReleaseValue(o);
            }
        }
        clock_gettime(CLOCK_MONOTONIC, &ts1);
        decTotalMs = (ts1.tv_sec - ts0.tv_sec) * 1e3 + (ts1.tv_nsec - ts0.tv_nsec) / 1e6;
    }

    for (size_t i = 0; i < inVals.size(); i++) {
        bool isCross = i >= (size_t)(2 + 2 * L) && i < (size_t)(2 + 4 * L);
        if (!isCross && inVals[i]) api->ReleaseValue(inVals[i]);
    }
    if (!ok) return nullptr;

    float avgProb = probCount ? (float)(probSum / probCount) : 0.0f;
    LOGI("decoder: %d steps in %.1f ms (%.1f ms/token), %zu tokens, avg_p=%.3f no_speech=%.3f",
         steps, decTotalMs, steps ? decTotalMs / steps : 0.0, generated.size(), avgProb,
         noSpeechProb);
    if (jMetrics && env->GetArrayLength(jMetrics) >= 2) {
        env->SetFloatArrayRegion(jMetrics, 0, 1, &avgProb);
        env->SetFloatArrayRegion(jMetrics, 1, 1, &noSpeechProb);
    }

    jintArray result = env->NewIntArray((jsize)generated.size());
    env->SetIntArrayRegion(result, 0, (jsize)generated.size(), generated.data());
    return result;
}

}  // extern "C"

static JNINativeMethod sMethods[] = {
    {"nativeInit", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)J",
     (void*)nativeInit},
    {"nativeFree", "(J)V", (void*)nativeFree},
    {"nativeCancel", "(J)V", (void*)nativeCancel},
    {"nativeLastError", "()Ljava/lang/String;", (void*)nativeLastError},
    {"nativeMelInit", "(Ljava/lang/String;I)Z", (void*)nativeMelInit},
    {"nativeMel", "([F)[S", (void*)nativeMel},
    {"nativeTranscribe", "(J[S[I[F)[I", (void*)nativeTranscribe},
};

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    const char* const kClassPathName = "org/futo/voiceinput/shared/ggml/QnnWhisperEngine";
    jclass clazz = env->FindClass(kClassPathName);
    if (!clazz) {
        LOGE("JNI_OnLoad: cannot find class %s", kClassPathName);
        return JNI_ERR;
    }
    if (env->RegisterNatives(clazz, sMethods, sizeof(sMethods) / sizeof(sMethods[0])) != JNI_OK) {
        LOGE("JNI_OnLoad: RegisterNatives failed for %s", kClassPathName);
        return JNI_ERR;
    }
    LOGI("libvoiceinput_qnn JNI_OnLoad: registered %s", kClassPathName);
    return JNI_VERSION_1_6;
}
