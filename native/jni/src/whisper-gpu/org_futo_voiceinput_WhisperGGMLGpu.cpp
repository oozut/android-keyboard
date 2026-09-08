// ------------------------------------------------------------------
// GPU (Vulkan) voice-input JNI bridge for the self-contained whisper.cpp
// v1.9.3 engine (native/jni/src/whisper-gpu).
//
// Deliberately mirrors the JNI surface of the CPU bridge
// (native/jni/org_futo_voiceinput_WhisperGGML.cpp) so that the Kotlin
// side can treat both engines interchangeably, but registers under a
// DIFFERENT native class (WhisperGGMLGpu) and lives in its own shared
// library (libvoiceinput_gpu.so) so the CPU path is never disturbed.
//
// Behavioral notes vs. the FUTO-patched CPU engine:
//  * use_gpu = true, backend chosen by ggml (Vulkan when available).
//  * upstream v1.9.3 has no token-level partial_text_callback, so live
//    partial results are delivered per finalized segment instead of per
//    token (slightly coarser, same callback surface).
//  * upstream has no allowed_langs decoding constraint. A non-empty
//    allow-list is approximated by aborting the decode as soon as the
//    detected language leaves the set (and force-selecting when the set
//    has exactly one language).
//  * a ggml log callback captures the init-time error text so a failed
//    GPU/model load can be classified (e.g. out-of-device-memory vs a
//    corrupt model) instead of collapsing into one generic failure.
// ------------------------------------------------------------------

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <exception>
#include <map>
#include <set>
#include <string>
#include <vector>
#include <unistd.h>

#include "whisper.h"

#define LOG_TAG "WhisperGGMLGpu"
#define AKLOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define AKLOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Upper bound for the accumulated init-time error log captured for
// classification on the Kotlin side (plenty for an OOM/corrupt reason line).
constexpr size_t kMaxErrorCapture = 4096;

std::string jstring2string(JNIEnv* env, jstring jstr) {
    if (!jstr) return std::string();
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

jstring string2jstring(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

struct WhisperModelState {
    JNIEnv* env = nullptr;
    jobject partial_result_instance = nullptr;
    jmethodID partial_result_method = nullptr;

    whisper_context* context = nullptr;

    // Language handling (mirrors CPU bridge semantics, see file header).
    std::set<int> allowed_languages;   // empty == autodetect any
    std::set<int> forbidden_languages; // bail out as soon as detected
    int bail_language_id = -1;         // set when decode is aborted by language
    volatile int cancel_flag = 0;

    // Running transcript for live partial results within one infer() call.
    std::string partial_text;

    // Error capture from whisper/ggml logs during (re)init for typed
    // failures (out-of-memory vs corrupt model vs missing Vulkan device).
    std::string last_error;
};

void log_capture(enum ggml_log_level level, const char* text, void* user_data) {
    if (!text) return;
    if (level != GGML_LOG_LEVEL_ERROR && level != GGML_LOG_LEVEL_WARN) return;
    auto* state = static_cast<WhisperModelState*>(user_data);
    if (!state) return;

    std::string line(text);
    while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
    if (line.empty()) return;

    // Accumulate (capped) rather than keeping only the first message, so a
    // benign earlier warning can't mask the real failure (e.g. a Vulkan
    // out-of-device-memory error logged after a host-memory-type warning).
    if (state->last_error.size() < kMaxErrorCapture) {
        if (!state->last_error.empty()) state->last_error += "\n";
        state->last_error += line;
        if (state->last_error.size() > kMaxErrorCapture) {
            state->last_error.resize(kMaxErrorCapture);
        }
    }
}

// Trimmed error text used for logcat; only first 2000 chars kept sane.
void log_error_text(const WhisperModelState* state) {
    if (state && !state->last_error.empty()) {
        AKLOGE("init error: %s", state->last_error.c_str());
    }
}

jlong open_impl(JNIEnv* env, whisper_context* ctx, WhisperModelState* state) {
    // Keep the state alive even when the load failed (ctx == nullptr) so the
    // Kotlin layer can call lastErrorNative() to classify the failure
    // (out-of-memory vs corrupt model) before closing the handle.
    state->context = ctx;
    if (!ctx) {
        log_error_text(state);
    }
    return reinterpret_cast<jlong>(state);
}

jboolean WhisperGGMLGpu_loaded(JNIEnv* env, jclass clazz, jlong handle) {
    auto* state = reinterpret_cast<WhisperModelState*>(handle);
    return (state && state->context != nullptr) ? JNI_TRUE : JNI_FALSE;
}

jlong WhisperGGMLGpu_open(JNIEnv* env, jclass clazz, jstring model_path) {
    const std::string path = jstring2string(env, model_path);

    auto* state = new WhisperModelState();

    whisper_log_set(log_capture, state);
    AKLOGI("Attempting to load GPU model from file: %s", path.c_str());

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = true;       // backend selection handled by ggml
    cparams.flash_attn = false;

    whisper_context* ctx = nullptr;
    try {
        ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    } catch (const std::exception& e) {
        AKLOGE("GPU init threw: %s", e.what());
        state->last_error = std::string("GPU init failed: ") + e.what();
    } catch (...) {
        AKLOGE("GPU init threw an unknown exception");
        state->last_error = "GPU init failed: unknown native exception";
    }
    return open_impl(env, ctx, state);
}

jlong WhisperGGMLGpu_openFromBuffer(JNIEnv* env, jclass clazz, jobject buffer) {
    void* address = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (!address || capacity <= 0) {
        AKLOGE("openFromBuffer: not a direct buffer");
        return 0L;
    }

    auto* state = new WhisperModelState();

    whisper_log_set(log_capture, state);
    AKLOGI("Attempting to load GPU model from direct buffer (%lld bytes)", (long long)capacity);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = true;
    cparams.flash_attn = false;

    whisper_context* ctx = nullptr;
    try {
        ctx = whisper_init_from_buffer_with_params(address, (size_t)capacity, cparams);
    } catch (const std::exception& e) {
        AKLOGE("GPU init threw: %s", e.what());
        state->last_error = std::string("GPU init failed: ") + e.what();
    } catch (...) {
        AKLOGE("GPU init threw an unknown exception");
        state->last_error = "GPU init failed: unknown native exception";
    }
    return open_impl(env, ctx, state);
}

jstring WhisperGGMLGpu_infer(JNIEnv* env, jobject instance, jlong handle,
                             jfloatArray samples_array, jstring prompt,
                             jobjectArray languages, jobjectArray bail_languages,
                             jint decoding_mode, jboolean suppress_non_speech_tokens) {
    auto* state = reinterpret_cast<WhisperModelState*>(handle);
    if (!state || !state->context) {
        AKLOGE("infer: null state/context");
        return string2jstring(env, "");
    }

    state->cancel_flag = 0;
    state->bail_language_id = -1;
    state->partial_text.clear();
    state->allowed_languages.clear();
    state->forbidden_languages.clear();

    const int n_languages = languages ? env->GetArrayLength(languages) : 0;
    for (int i = 0; i < n_languages; i++) {
        jstring jstr = static_cast<jstring>(env->GetObjectArrayElement(languages, i));
        const int lang_id = whisper_lang_id(jstring2string(env, jstr).c_str());
        if (lang_id >= 0) state->allowed_languages.insert(lang_id);
    }

    const int n_bail = bail_languages ? env->GetArrayLength(bail_languages) : 0;
    for (int i = 0; i < n_bail; i++) {
        jstring jstr = static_cast<jstring>(env->GetObjectArrayElement(bail_languages, i));
        const int lang_id = whisper_lang_id(jstring2string(env, jstr).c_str());
        if (lang_id >= 0) state->forbidden_languages.insert(lang_id);
    }

    const size_t num_samples = samples_array ? env->GetArrayLength(samples_array) : 0;
    if (num_samples == 0) return string2jstring(env, "");
    jfloat* samples = env->GetFloatArrayElements(samples_array, nullptr);

    long num_procs = sysconf(_SC_NPROCESSORS_ONLN);
    if (num_procs < 2 || num_procs > 16) num_procs = 6;

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_realtime = false;
    wparams.print_special = false;
    wparams.print_timestamps = false;
    wparams.max_tokens = 256;
    wparams.n_threads = (int)num_procs;

    // Same audio-context sizing heuristic as the CPU bridge.
    wparams.audio_ctx = (int)std::max(160, std::min(1500, (int)ceil((double)num_samples / 320.0) + 32));
    wparams.temperature_inc = 0.0f;

    if (decoding_mode == 0) {
        wparams.strategy = WHISPER_SAMPLING_GREEDY;
        wparams.greedy.best_of = 1;
    } else {
        wparams.strategy = WHISPER_SAMPLING_BEAM_SEARCH;
        wparams.beam_search.beam_size = decoding_mode;
        wparams.greedy.best_of = decoding_mode;
    }

    wparams.suppress_blank = false;
    wparams.suppress_nst = (suppress_non_speech_tokens == JNI_TRUE);
    wparams.no_timestamps = num_samples < 16000u * 25u;

    if (state->allowed_languages.size() == 1) {
        wparams.language = whisper_lang_str(*state->allowed_languages.begin());
    } else {
        wparams.language = nullptr; // autodetect
    }

    const std::string prompt_str = jstring2string(env, prompt);
    wparams.initial_prompt = prompt_str.empty() ? nullptr : prompt_str.c_str();

    state->env = env;
    // `instance` is a JNI *local* reference, valid only for the duration of this
    // infer() call. closeNative() later calls DeleteGlobalRef on it, so it must
    // be stored as a *global* reference — otherwise the stale local ref aborts
    // with "invalid JNI transition frame reference" (CheckJNI) on close.
    if (state->partial_result_instance) {
        env->DeleteGlobalRef(state->partial_result_instance);
    }
    state->partial_result_instance = env->NewGlobalRef(instance);
    state->partial_result_method = env->GetMethodID(
            env->GetObjectClass(instance), "invokePartialResult", "(Ljava/lang/String;)V");

    // Live partial results: upstream v1.9.3 finalizes whole segments only.
    wparams.new_segment_callback = [](whisper_context* ctx, whisper_state* wstate, int n_new, void* user_data) {
        auto* s = static_cast<WhisperModelState*>(user_data);
        if (!s || !s->env || !s->partial_result_instance || !s->partial_result_method) return;
        if (n_new <= 0) return;

        const int n_segments = whisper_full_n_segments_from_state(wstate);
        for (int i = n_segments - n_new; i < n_segments; i++) {
            const char* text = whisper_full_get_segment_text_from_state(wstate, i);
            if (text) s->partial_text += text;
        }

        jstring partial = string2jstring(s->env, s->partial_text.c_str());
        s->env->CallVoidMethod(s->partial_result_instance, s->partial_result_method, partial);
        s->env->DeleteLocalRef(partial);
    };
    wparams.new_segment_callback_user_data = state;

    wparams.abort_callback = [](void* user_data) -> bool {
        auto* s = static_cast<WhisperModelState*>(user_data);
        if (!s) return false;

        const int detected = whisper_full_lang_id(s->context);
        if (detected >= 0) {
            const bool forbidden = s->forbidden_languages.count(detected) > 0;
            const bool not_allowed = !s->allowed_languages.empty() && s->allowed_languages.count(detected) == 0;
            if (forbidden || not_allowed) {
                s->bail_language_id = detected;
                return true;
            }
        }
        if (s->cancel_flag) {
            AKLOGI("cancel flag set! Aborting...");
            return true;
        }
        return false;
    };
    wparams.abort_callback_user_data = state;

    AKLOGI("Calling whisper_full (gpu)");
    std::string output;
    try {
        const int res = whisper_full(state->context, wparams, samples, (int)num_samples);
        if (res != 0) {
            AKLOGE("whisper_full failed with non-zero code %d", res);
        }
        whisper_print_timings(state->context);

        const int n_segments = whisper_full_n_segments(state->context);
        for (int i = 0; i < n_segments; i++) {
            auto seg = std::string(whisper_full_get_segment_text(state->context, i));
            if (seg == " you" && i == n_segments - 1) continue;
            output.append(seg);
        }
    } catch (const std::exception& e) {
        AKLOGE("GPU inference threw: %s", e.what());
        state->last_error = std::string("GPU inference failed: ") + e.what();
        output.clear();
    } catch (...) {
        AKLOGE("GPU inference threw an unknown exception");
        state->last_error = "GPU inference failed: unknown native exception";
        output.clear();
    }
    env->ReleaseFloatArrayElements(samples_array, samples, JNI_ABORT);

    if (state->bail_language_id >= 0) {
        output = "<>CANCELLED<> lang=" +
                 std::string(whisper_lang_str(state->bail_language_id));
    } else if (state->cancel_flag) {
        output = "<>CANCELLED<> flag";
    }

    return string2jstring(env, output);
}

void WhisperGGMLGpu_close(JNIEnv* env, jclass clazz, jlong handle) {
    auto* state = reinterpret_cast<WhisperModelState*>(handle);
    if (!state) return;
    whisper_free(state->context);
    if (state->partial_result_instance) {
        env->DeleteGlobalRef(state->partial_result_instance);
    }
    delete state;
}

void WhisperGGMLGpu_cancel(JNIEnv* env, jclass clazz, jlong handle) {
    auto* state = reinterpret_cast<WhisperModelState*>(handle);
    if (state) state->cancel_flag = 1;
}

jstring WhisperGGMLGpu_lastError(JNIEnv* env, jclass clazz, jlong handle) {
    auto* state = reinterpret_cast<WhisperModelState*>(handle);
    return string2jstring(env, state ? state->last_error : "");
}

const JNINativeMethod sMethods[] = {
    { const_cast<char*>("openNative"),
      const_cast<char*>("(Ljava/lang/String;)J"),
      reinterpret_cast<void*>(WhisperGGMLGpu_open) },
    { const_cast<char*>("openFromBufferNative"),
      const_cast<char*>("(Ljava/nio/Buffer;)J"),
      reinterpret_cast<void*>(WhisperGGMLGpu_openFromBuffer) },
    { const_cast<char*>("inferNative"),
      const_cast<char*>("(J[FLjava/lang/String;[Ljava/lang/String;[Ljava/lang/String;IZ)Ljava/lang/String;"),
      reinterpret_cast<void*>(WhisperGGMLGpu_infer) },
    { const_cast<char*>("cancelNative"),
      const_cast<char*>("(J)V"),
      reinterpret_cast<void*>(WhisperGGMLGpu_cancel) },
    { const_cast<char*>("closeNative"),
      const_cast<char*>("(J)V"),
      reinterpret_cast<void*>(WhisperGGMLGpu_close) },
    { const_cast<char*>("lastErrorNative"),
      const_cast<char*>("(J)Ljava/lang/String;"),
      reinterpret_cast<void*>(WhisperGGMLGpu_lastError) },
    { const_cast<char*>("loadedNative"),
      const_cast<char*>("(J)Z"),
      reinterpret_cast<void*>(WhisperGGMLGpu_loaded) },
};

} // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // Mobile GPUs (notably Qualcomm Adreno) sometimes reject ggml-vulkan's
    // cooperative-matrix compute pipelines with vk::SystemError
    // "createComputePipeline: ErrorUnknown", aborting the first matmul.
    // Disable coopmat so the well-tested scalar matmul shaders are used.
    // (GGML_VK_DISABLE_COOPMAT2 is a no-op on Qualcomm — VK_NV_cooperative_matrix2
    // is NVIDIA-only — but set it too for completeness.)
    setenv("GGML_VK_DISABLE_COOPMAT", "1", 1);
    setenv("GGML_VK_DISABLE_COOPMAT2", "1", 1);
    AKLOGI("GPU workaround: GGML_VK_DISABLE_COOPMAT=1 (Adreno coopmat pipeline workaround)");

    const char* const kClassPathName = "org/futo/voiceinput/shared/ggml/WhisperGGMLGpu";
    jclass clazz = env->FindClass(kClassPathName);
    if (!clazz) {
        AKLOGE("JNI_OnLoad: cannot find class %s", kClassPathName);
        return JNI_ERR;
    }

    if (env->RegisterNatives(clazz, sMethods, sizeof(sMethods) / sizeof(sMethods[0])) != JNI_OK) {
        AKLOGE("JNI_OnLoad: RegisterNatives failed for %s", kClassPathName);
        env->DeleteLocalRef(clazz);
        return JNI_ERR;
    }
    env->DeleteLocalRef(clazz);

    AKLOGI("libvoiceinput_gpu JNI_OnLoad: registered %s", kClassPathName);
    return JNI_VERSION_1_6;
}
