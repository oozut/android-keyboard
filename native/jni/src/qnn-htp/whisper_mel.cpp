// Log-mel spectrogram for the QNN Whisper encoder, ported 1:1 from HF
// WhisperFeatureExtractor._np_extract_fbank_features (the exact pipeline that
// Qualcomm AI Hub exports against): hann(400)/hop 160/center reflect,
// power spectrum, slaney mel (filters precomputed on the host, nMel x 201 f32
// binary, bundled as mel_filters_128.bin), log10 with 1e-10 floor, 3001 frames
// computed and the last dropped, clamp to global max-8, (x+4)/4, then quantize
// to the encoder's uint16 contract (w8a16 Whisper-Large-V3-Turbo: scale
// 4.7461e-05, zp 31605 — see the model metadata.json).
//
// Exposed as plain C++ (no JNI) so the JNI surface lives entirely in
// htp_whisper.cpp, mirroring the original whisper-htp-android split (MIT).

#include <android/log.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>

#include <thread>
#include <mutex>
#include <vector>

#define MEL_TAG "QnnWhisperMel"

namespace mel {

constexpr int SAMPLES_30S = 480000;   // 16 kHz * 30 s
constexpr int N_FFT = 400;
constexpr int N_BINS = N_FFT / 2 + 1; // 201
constexpr int HOP = 160;
constexpr int N_FRAMES = 3000;
constexpr int N_THREADS = 4;

// w8a16 Whisper-Large-V3-Turbo encoder input contract (metadata.json):
// real = (q - zp) * scale  =>  q = round(v / scale) + zp
constexpr double QUANT_SCALE = 4.746108970721252e-05;
constexpr int QUANT_ZP = 31605;

static std::vector<float> g_filters;  // [nMel][201]
static int g_nmel = 0;
static float g_hann[N_FFT];

// The filterbank tables are process-global. MultiModelRunner.preload()
// constructs engines on parallel threads, so init() must not rewrite them
// while another engine is using them.
static std::mutex g_init_mutex;

// Recursive Cooley-Tukey for even N with a naive DFT base for odd N — same
// approach whisper.cpp uses; exact enough at N=400 (base case N=25).
static void fft(const float* in, int n, int stride, float* out /* interleaved re,im */) {
    if (n == 1) {
        out[0] = in[0];
        out[1] = 0.0f;
        return;
    }
    if (n % 2 == 1) {  // naive DFT
        for (int k = 0; k < n; k++) {
            double re = 0, im = 0;
            for (int t = 0; t < n; t++) {
                double a = -2.0 * M_PI * k * t / n;
                re += in[t * stride] * cos(a);
                im += in[t * stride] * sin(a);
            }
            out[2 * k] = (float)re;
            out[2 * k + 1] = (float)im;
        }
        return;
    }
    int half = n / 2;
    std::vector<float> even(2 * half), odd(2 * half);
    fft(in, half, stride * 2, even.data());
    fft(in + stride, half, stride * 2, odd.data());
    for (int k = 0; k < half; k++) {
        double a = -2.0 * M_PI * k / n;
        double wr = cos(a), wi = sin(a);
        double or_ = odd[2 * k], oi = odd[2 * k + 1];
        double tr = wr * or_ - wi * oi;
        double ti = wr * oi + wi * or_;
        out[2 * k] = even[2 * k] + (float)tr;
        out[2 * k + 1] = even[2 * k + 1] + (float)ti;
        out[2 * (k + half)] = even[2 * k] - (float)tr;
        out[2 * (k + half) + 1] = even[2 * k + 1] - (float)ti;
    }
}

int numMelBins() { return g_nmel; }

bool init(const char* filtersPath, int nMel) {
    std::lock_guard<std::mutex> lock(g_init_mutex);

    // Load once: a concurrent second engine's init returns the already-loaded
    // tables instead of rewriting them mid-flight. (nMel is constant for this
    // build, so a mismatch should never happen.)
    if (!g_filters.empty() && g_nmel == nMel) return true;

    FILE* f = fopen(filtersPath, "rb");
    if (!f) return false;
    g_nmel = nMel;
    g_filters.resize((size_t)g_nmel * N_BINS);
    size_t got = fread(g_filters.data(), 4, g_filters.size(), f);
    fclose(f);
    if (got != g_filters.size()) {
        g_filters.clear();
        g_nmel = 0;
        return false;
    }
    for (int i = 0; i < N_FFT; i++)
        g_hann[i] = 0.5f * (1.0f - cosf(2.0f * (float)M_PI * i / N_FFT));
    return true;
}

// Computes a uint16-quantized log-mel spectrogram into `out` (size nMel*3000).
// Returns false if init() has not been called (or failed).
bool compute(const float* pcm, size_t nSamples, int nMel, int16_t* out) {
    if (g_filters.empty() || nMel != g_nmel) return false;

    size_t n = nSamples > (size_t)SAMPLES_30S ? (size_t)SAMPLES_30S : nSamples;
    if (nSamples > (size_t)SAMPLES_30S) {
        // QnnWhisperEngine.infer decodes in 29 s windows, so this should never
        // fire; if it does, audio is being dropped without anyone noticing.
        __android_log_print(ANDROID_LOG_WARN, MEL_TAG,
                            "input %zu samples exceeds the 30 s window (%d): audio will be truncated",
                            nSamples, SAMPLES_30S);
    }

    // Zero-pad to 30s, then reflect-pad n_fft/2 for center=True.
    const int PAD = N_FFT / 2;
    std::vector<float> x((size_t)SAMPLES_30S + 2 * PAD, 0.0f);
    for (size_t i = 0; i < n; i++) x[i + PAD] = pcm[i];
    for (int i = 0; i < PAD; i++) {
        x[PAD - 1 - i] = x[PAD + 1 + i];                                   // left reflect
        x[PAD + SAMPLES_30S + i] = x[PAD + SAMPLES_30S - 2 - i];           // right reflect
    }

    std::vector<float> spec((size_t)nMel * N_FRAMES);

    // Frames are independent — split across the big cores.
    std::vector<std::thread> pool;
    for (int ti = 0; ti < N_THREADS; ti++) {
        pool.emplace_back([&, ti] {
            std::vector<float> frame(N_FFT), fftout(2 * N_FFT), power(N_BINS);
            for (int t = ti; t < N_FRAMES; t += N_THREADS) {
                const float* src = x.data() + t * HOP;
                for (int i = 0; i < N_FFT; i++) frame[i] = src[i] * g_hann[i];
                fft(frame.data(), N_FFT, 1, fftout.data());
                for (int k = 0; k < N_BINS; k++)
                    power[k] = fftout[2 * k] * fftout[2 * k] + fftout[2 * k + 1] * fftout[2 * k + 1];
                for (int m = 0; m < nMel; m++) {
                    const float* filt = g_filters.data() + m * N_BINS;
                    double acc = 0;
                    for (int k = 0; k < N_BINS; k++) acc += filt[k] * power[k];
                    if (acc < 1e-10) acc = 1e-10;
                    spec[m * N_FRAMES + t] = (float)log10(acc);
                }
            }
        });
    }
    for (auto& th : pool) th.join();

    float mmax = spec[0];
    for (float v : spec)
        if (v > mmax) mmax = v;
    float lo = mmax - 8.0f;

    for (size_t i = 0; i < spec.size(); i++) {
        float v = spec[i] < lo ? lo : spec[i];
        v = (v + 4.0f) / 4.0f;
        long qi = lround(v / QUANT_SCALE) + QUANT_ZP;
        if (qi < 0) qi = 0;
        if (qi > 65535) qi = 65535;
        out[i] = (int16_t)(uint16_t)qi;
    }
    return true;
}

}  // namespace mel
