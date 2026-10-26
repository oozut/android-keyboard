# GPU / Hardware-Acceleration Feature — Wrap-up & Change Inventory

> **Purpose:** single authoritative record of *what changed*, *why*, and *what's left*,
> for the on-device voice-input hardware-acceleration feature added to this fork of
> FUTO Keyboard.
>
> **Date:** 2026-09-08
> **Repo:** `oozut/android-keyboard` (fork of FUTO Keyboard)
> **Companion docs:** `GPU_VULKAN_IMPLEMENTATION_PLAN.md`, `GPU_VULKAN_FINDINGS.md`,
> `GPU_HEXAGON_INVESTIGATION.md`, `GPU_QNN_INTEGRATION.md`, `THIRD_PARTY_NOTICES.md`.

---

## 1. TL;DR — what the feature is now

The keyboard's **voice input** (previously CPU-only, via FUTO's vendored whisper.cpp) now
supports **three backends**, selected per model at transcription-trigger time:

| Backend | Engine | Runs on | Status |
|---|---|---|---|
| **CPU** | FUTO's original whisper.cpp (untouched) | every device | ✅ production, unchanged |
| **Vulkan** | whisper.cpp **v1.9.3** `ggml-vulkan` (new, vendored) | Mali/other GPUs (Vulkan ≥1.2) | ⚠️ built & validated on host; **needs device validation** (Adreno 740 = broken driver) |
| **QNN** | ONNX Runtime + Qualcomm **QNN HTP** (new) | Snapdragon DSP (Hexagon HTP) | ✅ **working end-to-end on S23 Ultra** |

- The user-facing toggle is labeled **"Use hardware acceleration"** (generic, since it
  now targets both GPU and DSP).
- **Default is OFF** for every model (hardware acceleration is strictly opt-in).
- Device type + capabilities are detected at runtime (`BackendSupport.kt`) and route to
  the right backend; unsupported hardware degrades gracefully to CPU.
- Model files are now *format-typed*: legacy ggml `.bin` (CPU/Vulkan) vs. **QNN context
  binaries** (`encoder.bin` + `decoder.bin` inside a `.zip`, with sibling `.onnx` graphs).

---

## 2. The journey (phase map)

| Phase | Outcome |
|---|---|
| **0 — compatibility spike** | Proven whisper.cpp v1.9.3 (Vulkan-enabled) loads & transcribes FUTO's ACFT `.bin` models on host. |
| **1 — native Vulkan engine** | Vendored whisper.cpp v1.9.3; new CMake target + JNI bridge; `.so` built & packaged in APK. |
| **2 — Kotlin plumbing** | `useGpu` flag end-to-end (settings → recognizer → model manager → JNI). |
| **3 — UI** | "Use hardware acceleration" toggle (assignment dialog + post-assignment), Vulkan gate, typed OOM error. |
| **Vulkan on-device debugging** | Found & fixed: uncaught ggml-vulkan `throw` → SIGABRT; `closeNative` JNI stale-ref crash; coopmat disable. **Conclusion: Adreno 740 rejects ggml-vulkan shaders (upstream-confirmed, no fix).** |
| **Hexagon investigation** | Found official `ggml-hexagon` backend in-tree — but blocked by Qualcomm proprietary SDK + DSP *signing* (dead end for distribution). |
| **QNN pivot** | QNN runs pre-signed Qualcomm binaries (no signing step). Maven `qnn-runtime` + ORT QNN EP. |
| **QNN integration** | Backend routing, model-format detection, ZIP import, decode-loop port, working transcription on S23 Ultra. |

---

## 3. Architecture

```
Voice input trigger
   └─ VoiceInputAction.loadSettings()  ← reads per-model "hardware acceleration" pref
        └─ BackendSupport.resolveBackend()  ← device type + capabilities
             ├─ Snapdragon  → Backend.QNN   → QnnWhisperEngine → libvoiceinput_qnn.so (ORT + QNN HTP)
             ├─ Mali/Vulkan ≥1.2 → Backend.VULKAN → WhisperGGMLGpu → libvoiceinput_gpu.so (ggml-vulkan)
             └─ else        → Backend.CPU    → WhisperGGML → libjni_latinime.so (untouched)
```

Key design decisions (from the plan, all held):
- **D1** — GPU engine is a *separate, self-contained* lib; FUTO's entangled pre-Vulkan
  ggml CPU path is **never touched**.
- **D2/D3** — GPU/hardware choice is **per model**, default **off**; the `ModelManager`
  cache key is `(model, backend)` so toggling never reuses a stale context.
- **D5** — typed error taxonomy: OOM (`ModelOutOfMemoryException`) and hardware-unavailable
  (`GpuUnavailableException`) are distinct from "corrupt model".

---

## 4. Complete change inventory

### 4.1 Modified files (tracked) — 17 files, +693 / −83 lines

| File | What changed |
|---|---|
| `build.gradle` | Added QNN AAR deps: `onnxruntime-android-qnn-1.27.0`, `qnn-runtime-2.48.0`. |
| `java/AndroidManifest.xml` | `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>` (QNN needs it; false = graceful on non-Snapdragon). |
| `java/res/values/strings-uix.xml` | "Use hardware acceleration" toggle label/subtitle + QNN model kind strings. |
| `…/uix/ImportResourceActivity.kt` | New `FileKind.VoiceInputQnn` / `VoiceInputQnnZip`; QNN magic detection (container header `0x00000002 0x00000003` + embedded build-id string); ZIP detection via local-header scan; stream-extract import; `try/catch` so the spinner always resolves. |
| `…/uix/actions/VoiceInputAction.kt` | `loadSettings()` resolves `backend` via `BackendSupport` at trigger time and passes it through. |
| `…/uix/settings/pages/Languages.kt` | Hardware-accel toggle in assignment dialog + post-assignment toggle; new `FileKind` cases. |
| `…/uix/settings/pages/modelmanager/Utils.kt` | File picker now accepts `application/zip` etc. (was `octet-stream` only, which hid `.zip` on Samsung). |
| `…/utils/ZipFileHelper.kt` | `extract(InputStream, dir)` — stream extraction (no temp copy), zip-slip safe. |
| `native/jni/CMakeLists.txt` | `add_subdirectory(src/whisper-gpu)` + `add_subdirectory(src/qnn-htp)`. |
| `voiceinput-shared/…/AudioRecognizer.kt` | New `ModelLoadFailure.HardwareUnavailable`; catches `GpuUnavailableException`. |
| `voiceinput-shared/…/RecognizerView.kt` | Distinct error copy for hardware-unavailable vs OOM vs corrupt. |
| `voiceinput-shared/…/ggml/WhisperGGML.kt` | Implements new `WhisperEngine` interface; `ModelOutOfMemoryException`. |
| `voiceinput-shared/…/types/AudioRecognizerListener.kt` | `ModelLoadFailure` enum extended. |
| `voiceinput-shared/…/types/ModelData.kt` | `loadGGML(context, backend)`; QNN/ZIP model loaders; backend-aware fallback. |
| `voiceinput-shared/…/whisper/ModelManager.kt` | Cache key `(modelKey, backend)`. |
| `voiceinput-shared/…/whisper/MultiModelRunner.kt` | `backend` threaded through `preload()`/`run()`. |
| `voiceinput-shared/…/res/values/strings-voiceinput.xml` | Hardware-accel + OOM + QNN strings. |

### 4.2 New files — Kotlin (`voiceinput-shared/.../ggml/`)

| File | Lines | Purpose |
|---|---|---|
| `BackendSupport.kt` | 90 | `Backend` enum + `resolveBackend()` (Snapdragon/Mali/Vulkan device detection). |
| `GpuSupport.kt` | 37 | Vulkan ≥1.2 capability gate. |
| `QnnWhisperEngine.kt` | 196 | Full QNN engine (was a skeleton; now the real ORT+QNN decode loop wrapper). |
| `WhisperEngine.kt` | 31 | Common engine interface (`infer/cancel/close/lastError`). |
| `WhisperGGMLGpu.kt` | 163 | Vulkan engine wrapper (`libvoiceinput_gpu.so`). |

### 4.3 New files — native

**`native/jni/src/qnn-htp/`** → `libvoiceinput_qnn.so` (407 KB)

| File | Purpose |
|---|---|
| `htp_whisper.cpp` (520) | ORT C API + QNN EP decode loop: dlopen `libonnxruntime.so`, encoder/decoder sessions, fixed-window KV-cache greedy decode (`CFG_TURBO_W8A16`), JNI registration. |
| `whisper_mel.cpp` (152) | 128-bin Slaney mel front-end (w8a16 quant params). |
| `ort/include/*.h` (3 files) | ONNX Runtime C API headers (vendored from ORT). |
| `CMakeLists.txt` (41) | Build config. |

**`native/jni/src/whisper-gpu/`** → `libvoiceinput_gpu.so` (~51 MB, arm64-only)

| File | Purpose |
|---|---|
| `CMakeLists.txt` | Backend switch (`VOICEINPUT_HW_ACCEL_BACKEND=vulkan\|hexagon`), static-link config, API-28 Vulkan stub fix. |
| `org_futo_voiceinput_WhisperGGMLGpu.cpp` | JNI bridge (mirrors CPU bridge; try/catch; `NewGlobalRef` fix; coopmat disable). |
| `ggml/` (23 MB) | whisper.cpp **v1.9.3** ggml monorepo (vendored; contains vulkan + hexagon backends). |
| `whisper/` (396 KB) | whisper.cpp sources. |
| `third_party/` (16 MB) | vendored `vulkan.hpp` + SPIR-V headers (needed by NDK cross-build). |

### 4.4 New assets

| File | Purpose |
|---|---|
| `java/assets/mel_filters_128.bin` | 128-bin mel filterbank (QNN front-end). |
| `java/assets/whisper_vocab_v3.json` | Large-v3-turbo detokenizer vocab. |

### 4.5 New dependencies (AARs in `libs/`)

| AAR | Size | Purpose |
|---|---|---|
| `onnxruntime-android-qnn-1.27.0.aar` | 8 MB | `libonnxruntime.so` + JNI (ORT with QNN EP). |
| `qnn-runtime-2.48.0.aar` | 68 MB | `libQnnHtp.so`, `libQnnHtpPrepare.so`, `libQnnSystem.so`, HTP Stub/Skel V66–V81. |

### 4.6 New documentation

| File | Purpose |
|---|---|
| `GPU_VULKAN_IMPLEMENTATION_PLAN.md` | The plan + all §7 decisions (Q1–Q7 rulings). |
| `GPU_VULKAN_FINDINGS.md` | Vulkan crash investigation + Adreno 740 conclusion. |
| `GPU_HEXAGON_INVESTIGATION.md` | ggml-hexagon backend + SDK/signing blocker. |
| `GPU_QNN_INTEGRATION.md` | QNN integration details, model-format change, routing matrix, deploy notes. |
| `THIRD_PARTY_NOTICES.md` | Attribution + licenses (MIT `whisper-htp-android`, etc.). |

---

## 5. Current status

**✅ Working & verified**
- CPU voice input — unchanged, no regression.
- QNN hardware acceleration — **transcribing on S23 Ultra** (Snapdragon 8 Gen 2 / HTP v73),
  using the `Whisper-Large-V3-Turbo-Quantized` (`qcs8550-proxy`) precompiled context binary.
- Model ZIP import — detection, extraction, registration, error handling all functional.
- Build — `./gradlew assembleUnstableDebug` green.

**⚠️ Needs device validation**
- **Vulkan** on a **Mali** GPU phone (Exynos/MediaTek/Tensor). Built & host-validated
  (llvmpipe), but never run on real Android hardware. *Adreno 740 is a known dead end
  (Qualcomm driver rejects ggml-vulkan shaders).*

**⛔ Known blocked / abandoned**
- **ggml-hexagon** (raw dispatch) — abandoned: proprietary SDK + DSP production signing
  makes it non-viable for distribution.
- **Adreno Vulkan** — abandoned: upstream-confirmed driver incompatibility.

---

## 6. Build & deploy

```bash
# build (inside distrobox container "android-dbx")
cd ~/android-keyboard && ./gradlew assembleUnstableDebug
# → build/outputs/apk/unstable/debug/android-keyboard-unstable-debug.apk  (~248 MB)
```

**Deploy to S23 Ultra via Syncthing** (see `GPU_QNN_INTEGRATION.md` §"Deploying"):
drop files into the container volume path, *not* the host path:

```
/var/lib/containers/storage/volumes/syncthing-data/_data/s23/
```

**On-device model file** (the correct one):
```
whisper_large_v3_turbo_quantized-precompiled_qnn_onnx-qcs8550-proxy.zip
```
(the `qnn_context_binary`-only ZIP does **not** work — ORT needs the sibling `.onnx` graphs.)

---

## 7. Trim-down candidates (next session)

The user's next goal is to shrink the diff. Concrete candidates, in order of value:

1. **Remove debug/transient artifacts (not part of the feature):**
   - `br.zip` (bug report — remove)
   - `.pod-sdk-install.log` (stray log — remove)
   - `native/jni/src/whisper-gpu/build-ndk/` (standalone build dir — add to `.gitignore` / remove)
   - `native/jni/src/whisper-gpu/gpu-build.log` (build log — remove)

2. **Remove the Hexagon scaffold** (`VOICEINPUT_HW_ACCEL_BACKEND=hexagon` branch in
   `whisper-gpu/CMakeLists.txt`) — it's a signing dead end and adds config surface with
   no reachable benefit. (The `ggml-hexagon` *sources* stay — they're part of the
   vendored upstream ggml tree.)

3. **Consolidate docs** — 5 docs can fold into 2: keep a single *plan/status* doc and a
   single *findings/notes* doc. `GPU_HEXAGON_INVESTIGATION.md` content is largely
   superseded by the QNN pivot.

4. **Decide the Vulkan path's fate** after the Mali device test:
   - **If it works:** keep `libvoiceinput_gpu.so` (+51 MB APK).
   - **If not:** consider dropping the Vulkan backend and its vendored tree entirely,
     leaving QNN (Snapdragon) + CPU (everyone). This would remove ~40 MB of vendored
     ggml + the 51 MB `.so` and is the biggest single win.

5. **APK size (248 MB)** — dominated by `libonnxruntime.so` (21 MB) + the QNN HTP stack
   (68 MB) + `libvoiceinput_gpu.so` (51 MB). Options to explore later:
   - Ship QNN AARs in a separate APK flavor / ABI split.
   - On-demand model download (models are already per-arch and large).

6. **AARs in `libs/`** — the two QNN AARs are committed binaries. Fine for now (matches
   the repo's existing flatDir pattern for `mozc`, `rime`, etc.), but worth a comment in
   `THIRD_PARTY_NOTICES.md` about how they were obtained (already partially covered).

---

## 8. Licensing / attribution quick reference

- **`whisper-htp-android`** (thedevguy) — **MIT** — the decode loop is ported from here;
  each derived file carries an attribution header. See `THIRD_PARTY_NOTICES.md`.
- **`anywhere-transcript`** (dan-el-7) — **no license → read for ideas only, do not copy.**
- **whisper.cpp v1.9.3** (ggml/llama.cpp) — MIT, vendored in-tree.
- **QNN SDK / runtime / AI Hub binaries** — Qualcomm proprietary; used as tooling/runtime,
  never vendored as source.
- **FUTO Keyboard** (this fork's upstream) — FUTO Source First License (non-commercial).
