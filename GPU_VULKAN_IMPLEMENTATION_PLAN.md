# FUTO Keyboard — GPU (Vulkan) Voice Transcription: Implementation Plan

Repo: `oozut/android-keyboard` fork of FUTO Keyboard
Date: 2026 (working draft)
Status: **All §7 questions resolved (user) — plan locked; executing Phase 0 empirical run**

> **See also:** [`THIRD_PARTY_NOTICES.md`](./THIRD_PARTY_NOTICES.md) — attribution and
> licensing for every third-party source this plan draws on.

---

## 1. Refined requirements

Derived from the feature request, refined into testable capabilities:

| # | Capability | Acceptance criteria |
|---|------------|--------------------|
| R1 | **GPU choice proposed at model specification, editable after** | When the user sets / changes the voice-input model for a language, the UI presents a "Use GPU for this model" choice (toggle + short explanation), **default = OFF** (user decision, Q2). The choice is **persisted per model assignment** (per language+kind) **and remains editable afterwards** (Q1): a second toggle is shown for an already-assigned model so the user can turn GPU on/off without re-assigning. |
| R2 | **GPU used only when transcription runs** | The GPU flag is read at the moment the voice-input action is triggered, threaded into model load, and passed to whisper as `{ .use_gpu = true/false }`. A CPU-only model never touches the GPU backend; a GPU model loads into the Vulkan backend. |
| R3 | **GPU capability gating** | The UI only offers GPU when the device can actually use it (Vulkan driver present + supported ABI + our GPU engine shipped for that ABI). If not supported, the choice is hidden or shown disabled with an explanatory tip. |
| R4 | **Memory-full error surfaced** | If the model cannot be fully loaded into memory (host RAM and/or GPU device memory), the user sees an explicit, actionable error message instead of a crash or the generic "model corrupt" message. Distinguish: *file corrupt/incompatible* vs *not enough memory to load this model*. |
| R5 | **Fallback integrity** | The existing CPU path stays byte-for-byte the default when GPU is off/unsupported; models cached/loaded on CPU keep working with no behavior change. |
| R6 | **Real-device verification** | On a physical Android device with a Vulkan-capable SoC, transcription with GPU on works end-to-end and is not slower than CPU for the same model. |

Non-goals for v1: GPU for the keyboard language-model (Transformer/GGUF path) and GPU for dictionary — this plan only covers voice transcription (`FileKind.VoiceInput` / whisper.cpp). Switching GPU *mid-recording* is not supported (backends are fixed at context init).

---

## 2. Ground truth — how voice transcription works today

(Files under `/home/gc/android-keyboard`, verified in this tree.)

### 2.1 Trigger path (per keystroke of the mic action)

1. User presses mic → `VoiceInputAction.windowImpl` (`java/src/.../uix/actions/VoiceInputAction.kt`).
2. Model for the active locale is resolved:
   `ResourceHelper.tryFindingVoiceInputModelForLocale()` (`java/src/.../uix/ImportResourceActivity.kt:571`)
   → returns a `ModelLoader` = either the built-in asset `tiny_en_acft_q8_0.bin.not.tflite` (`ModelBuiltInAsset`, from `voiceinput-shared/.../Models.kt`) or a user-imported `.bin` (`ModelFileFile`).
3. `VoiceInputActionWindow.loadSettings()` (`VoiceInputAction.kt`) reads DataStore settings (`getSetting(...)`), builds `RecognizerViewSettings` → `MultiModelRunConfiguration(primaryModel = …)`.
4. `RecognizerView` → `AudioRecognizer.startRecording()` (`voiceinput-shared/.../AudioRecognizer.kt`) → `preloadModels()` → `ModelManager.obtainModel()` (`voiceinput-shared/.../whisper/ModelManager.kt`) → `ModelLoader.loadGGML(context)` → constructs `WhisperGGML(mappedBuffer)`.
5. `WhisperGGML` init (`voiceinput-shared/.../ggml/WhisperGGML.kt`) → JNI `openFromBufferNative(buffer)` → `WhisperGGML_openFromBuffer` (`native/jni/org_futo_voiceinput_WhisperGGML.cpp:44`) → `whisper_init_from_buffer_with_params(…, { .use_gpu = false })`.
6. Model context stays cached in `ModelManager.loadedModels` keyed by `model.key(context)` for the lifetime of the action's persistent state (`VoiceInputPersistentState`), so the GPU/CPU decision is made **once per model per action session**, at step 5.

### 2.2 Model storage & "specification" UX

- A model belongs to a **language** (per-locale resource, `FileKind.VoiceInput`). DataStore keys: file path stored under `FileKind.preferenceKeyFor(locale)` (e.g. `voice_input_model_en`), display name under `FileKind.namePreferenceKeyFor(locale)`.
- User "specifies the model" in Settings → Languages → language card → **Voice input model** row → `ConfirmResourceActionDialog` (in `Languages.kt`): buttons = Replace (import file via SAF / `openModelImporter`), Revert to default, or Explore online (`keyboard.futo.tech/voice-input-models`). There is currently **no list picker** for built-in sizes in this fork's keyboard — a model is an asset (English default) or one imported `.bin`.
- The legacy `ENGLISH_MODELS` / `MULTILINGUAL_MODELS` lists + `ENGLISH_MODEL_INDEX` (`VoiceInputSettingKeys.kt`) are the *upstream voice-input addon's* picker; the keyboard fork does not use them for selection (still referenced by the shared module).

### 2.3 Existing failure UX

- `ModelDoesNotExistException` (model file absent) → `NoModelInstalled(locale)` screen with a link.
- `InvalidModelException` (JNI returned 0 handle) → `listener.modelLoadingFailed()` → `RecognizerView` `ModelError` state → generic string `model_load_error` ("Error loading model. It may be corrupted or incomplete…", `strings-voiceinput.xml:60`).
- **There is no distinction for out-of-memory** today; `tryOpenDownloadedModel(...).load()` (`types/ModelData.kt`) force-touches every page, and whisper init allocates graph buffers natively — either can throw/OOM without an actionable message.

### 2.4 Native build

- Single Gradle module + `externalNativeBuild { cmake { path 'native/jni/CMakeLists.txt' } }` (`build.gradle:380`), NDK `28.2.13676358`.
- `native/jni/CMakeLists.txt` + `NativeFileList.cmake` compile the **entangled, pre-Vulkan ggml/llama.cpp/whisper.cpp** stack that FUTO vendors under `native/jni/src/ggml/` (also used by the keyboard language model). Both JNI open fns hardcode `use_gpu = false`.
- whisper.cpp Vulkan support only exists upstream (landed mid-2024); FUTO's vendored baseline predates it and is heavily customized (ACFT models, keyboard-LM ggml coupling). **Upgrading it in place risks the CPU keyboard-LM path and cannot be compile-verified by us in-repo.**

---

## 3. Design decisions

### D1 — Engine strategy: separate GPU-native engine, CPU path untouched

Vendor a **second, self-contained whisper.cpp** (upstream v1.9.3, `GGML_VULKAN=ON`) as its own native library set (e.g. `libwhisper-gpu.so` + `libggml-vulkan.so` + minimal deps), built from a new CMake target in `native/jni/CMakeLists.txt`. Load it only when R1/R2 say GPU. The existing CPU stack stays as-is (R5).

- **Why:** the vendored ggml is pre-Vulkan and entangled with the keyboard LM; rebasing it would destabilize CPU transcription which we cannot re-verify without the full Android toolchain.
- **Why v1.9.3:** latest stable; monorepo (ggml merged); Vulkan backend is mature; we already **proved it builds and transcribes through the Vulkan backend** on this machine (see §Phase 0).

### D2 — GPU flag is a per-model-assignment setting, read at trigger time

Persist GPU preference **keyed the same way the model itself is keyed** (per locale+kind), so a GPU model and a CPU model for the same language can coexist conceptually, and switching languages can't silently run GPU when the user said CPU (and vice versa). Setting read inside `loadSettings()` → carried in `MultiModelRunConfiguration` → passed to `loadGGML(context, useGpu)` → `WhisperGGML(buffer, useGpu)`. **Default for any new model assignment = off** (Q2, user decision). Written by the assignment dialog and by the post-assignment toggle (Q1, user decision).

### D3 — ModelManager cache must be GPU-aware

Cache key becomes `(model.key(context), useGpu)`; otherwise toggling GPU reuses the stale CPU-loaded context (step 2.1.6) and the toggle has no effect. Cleanup semantics unchanged.

### D4 — Capability gate = Vulkan feature check + ABI policy

- Gate A (build-time/shipping): we ship the GPU engine only for `arm64-v8a` initially (see §7 Q5).
- Gate B (runtime): Android has Vulkan ≥ API 24 as a *requirement*, but devices without a driver exist; use `PackageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)` (API 24+) as a cheap pre-check, and confirm backend availability with a **native probe** (`ggml_vulkan` device enumeration) so the UI/loader never offers GPU when the backend would find zero devices (mirrors the "No devices found" behavior we hit in de-risk).

### D5 — Error taxonomy: typed load failures instead of one generic message

Introduce a small typed exception set on the load path, classified at the native boundary:
- `InvalidModelException` (existing) — file corrupt / wrong architecture / unsupported format.
- **`ModelOutOfMemoryException` (new)** — not enough host memory *or* GPU device memory to load this model. Detected via (a) native: install `ggml_log_callback` during init and flagggml/whisper allocation failures ("failed to allocate", Vulkan device-memory errors) + return a distinct JNI error; (b) Kotlin: wrap `map(...).load()` OOM/`IOException` at `ModelData.kt`.
- `ModelDoesNotExistException` (existing).
UI maps each to a distinct message + suggested action (§5.4).

---

## 4. Native spike already done (evidence)

| Item | Result |
|---|---|
| whisper.cpp **v1.9.3** clone @ `/home/gc/whisper-poc` | ✅ `371b5a7` |
| Configure `GGML_VULKAN=ON`, `-DGGML_NATIVE=OFF` | ✅ Vulkan 1.4.341 found; backend included |
| Full build (gcc 15, host x86_64) | ✅ `BUILD_EXIT=0`, `whisper-cli`, `libggml-vulkan.so` |
| GPU transcription smoke test (llvmpipe software Vulkan) | ✅ correct JFK transcript via Vulkan; CPU-control identical |
| **ACFT model × stock v1.9.3** (user model `DeadBranches/acft-whisper-large-v3-turbo_q8_0.bin`, 874 MB) | ✅ loads — large-v3-turbo arch (32×1280 audio / 4×1280 text / 51866 vocab) recognized; correct CPU transcript; Vulkan init OK (`use gpu = 1`) |
| Gotchas learned | ggml-vulkan skips CPU-type devices by default (`GGML_VK_VISIBLE_DEVICES=0` forces it — not needed on real phones); per-ISA CMake flags (`GGML_AVX2=OFF …`) matter on old x86 hosts, irrelevant for NDK ARM |

---

## 5. Work breakdown

### Phase 0 — Model-compatibility spike (blocks D1 final sign-off)

**Question:** can upstream whisper.cpp v1.9.3 (Vulkan-capable) run FUTO's **ACFT** models (`*_acft_q8_0.bin` — custom-trained architecture on FUTO's whisper fork), or only the plain multilingual models?

**Do:**
1. Fetch the user-specified **`DeadBranches/acft-whisper-large-v3-turbo_q8_0`** (FUTO ACFT whisper-large-v3-turbo, GGUF/q8_0) — serves as both the compatibility probe **and** the R6 speed-validation model.
2. Load + transcribe both with the already-built `whisper-poc/build-vk/bin/whisper-cli` (CPU path first, then `GGML_VK_VISIBLE_DEVICES=0` GPU path). GPU is a no-op difference at this stage — the question is pure **model-format/architecture compatibility**.
3. Outcomes: (a) both load → D1 as stated is safe; (b) ACFT fails, plain works → GPU engine can only serve non-ACFT models; the UI must then mark GPU as unavailable for ACFT-built-in models and the import validation must detect ACFT format; (c) both fail → stock engine can't be used; revisit D1 (port Vulkan into FUTO's whisper fork instead — much larger).

**Exit criteria:** recorded compatibility matrix + D1 confirmed or amended. Also record a **CPU-vs-Vulkan benchmark baseline** on this host for the turbo model (latency per utterance + load time) to compare against on-device numbers in Phase 4.

**Result (empirical, this machine):**
- CPU control: model loads (528 ms) → **correct JFK transcript**; encode 16.4 s / total 18.6 s (8 threads, no-AVX2 Sandy Bridge Xeon).
- Vulkan/llvmpipe: device found, `use gpu = 1`, arch recognized, model load proceeds into Vulkan. Full GPU transcription of the 874 MB turbo on *software* Vulkan is impractically slow (~100× CPU) — not needed for Phase 0, since GPU-path correctness was already proven on the small model and the same ggml-vulkan backend serves this one.
- **Verdict: outcome (a) — D1 CONFIRMED.** Stock v1.9.3 GPU engine serves FUTO ACFT `.bin` models directly; no conversion. Host CPU timings above become the Phase 4 comparison baseline.
- Side note for R4: the turbo q8 model (~874 MB weights + compute buffers) is a natural **memory-stress case** on a phone — likely to exercise the typed out-of-memory path in Phase 4 device tests.

### Phase 1 — Native: GPU engine vendor + JNI surface

1. Vendor upstream whisper.cpp v1.9.3 under `native/jni/src/whisper-gpu/` (submodule or vendored tree; single source of truth = our `whisper-poc`).
2. Add a dedicated CMake target in `native/jni/CMakeLists.txt` building `libwhisper-gpu` + `libggml-vulkan` (`GGML_VULKAN=ON`, `GGML_NATIVE=OFF`, static or shared per packaging choice — shared libs keep the existing big lib intact, see §6 Risk 2). Wire into `NativeFileList.cmake`/ABI splits.
3. New JNI bridge (do **not** touch the existing CPU bridge's ABI): e.g. `org_futo_voiceinput_WhisperGGMLGpu.cpp` with:
   - `openFromBufferNative(buffer, useGpu)` — passes `.use_gpu = useGpu` into `whisper_init_from_buffer_with_params` **and** installs a `ggml_log_callback` capture during init to classify failures (§D5) into: success / invalid / out-of-memory.
   - `inferNative(...)` / `cancelNative(...)` / `closeNative(...)` mirroring the existing bridge.
   - Reuse `jni_common.h` helpers.
4. ABI note: Vulkan needs the NDK's `vulkan` headers + `libvulkan.so` link; the CMake target must set `find_package(Vulkan)` equivalent via NDK (`-DANDROID` toolchain exposes Vulkan headers at `$ANDROID_NDK/sources/...`/platform libs).

**Exit criteria:** APK builds on a machine with the Android toolchain; `libwhisper-gpu.so` + `libggml-vulkan.so` present for the chosen ABI(s); no regression in the existing native lib compile.

### Phase 2 — Kotlin plumbing: flag end-to-end ✅ DONE

Implemented (build `BUILD SUCCESSFUL`, APK verified):
1. **`ggml/WhisperEngine.kt`** (new): common interface `{ infer, cancel, close, lastError }` implemented by both engines.
2. **`ggml/WhisperGGMLGpu.kt`** (new): GPU wrapper loading `libvoiceinput_gpu.so`, mirroring `WhisperGGML` API; calls `openFromBufferNative(buffer, true)`, `lastErrorNative(handle)`.
3. **`ggml/GpuSupport.kt`** (new): `isAvailable(context)` gate = `SDK_INT >= P (28)` AND `FEATURE_VULKAN_HARDWARE_VERSION` present.
4. **`ggml/WhisperGGML.kt`**: implements `WhisperEngine` (`override` keywords; `lastError()` → `""`); CPU `useGpu=false` path untouched.
5. **`types/ModelData.kt`**: `ModelLoader.loadGGML(context, useGpu)` → `WhisperEngine`; all 3 impls (`BuiltIn`, `Downloadable`, `File`) instantiate `WhisperGGMLGpu` when `useGpu` else `WhisperGGML`.
6. **`whisper/ModelManager.kt`**: cache key `ModelCacheKey(modelKey, useGpu)`; `obtainModel(model, useGpu)`.
7. **`whisper/MultiModelRunner.kt`**: `MultiModelRunConfiguration.useGpu` (default `false`) threaded through `preload()`/`run()` → `obtainModel`.
8. **`uix/ImportResourceActivity.kt`**: `FileKind.gpuPreferenceKeyFor(locale)` → `booleanPreferencesKey("resource_${name}_gpu_${locale}")`; `ResourceHelper.getVoiceInputModelUseGpu`/`setVoiceInputModelUseGpu` (per-locale, keyed by the same locale key as the model assignment).
9. **`uix/actions/VoiceInputAction.kt`**: `loadSettings()` reads `useGpu = getVoiceInputModelUseGpu(...) && GpuSupport.isAvailable(...)` and passes it into `MultiModelRunConfiguration`.

Storage: per-locale boolean keyed by `findKeyForLocaleAndKind` (the same key that holds the model path), so GPU follows the assignment. Built-in fallback model (no locale key) stays CPU/off — matches default-off.

**Exit criteria met:** single source of truth (per model-assignment, read at trigger), `ModelManager` key includes gpu.

**Deferred to Phase 3:** typed `ModelOutOfMemoryException` + native `last_error` surfacing (the `lastError()` seam is wired and ready; native `open_impl` currently deletes state on failure, so OOM text capture needs the native bridge to return the captured error before delete).

### Phase 3 — UI: propose GPU at model specification + capability gate + error copy

1. `Languages.kt` — the **model-specification dialog** `ConfirmResourceActionDialog`: when `resourceKind == FileKind.VoiceInput`, add a "Run this model on the GPU" switch (subtitle: speed vs memory tradeoff), **default OFF** (Q2). Set = write `gpuPreferenceKeyFor(locale)`. Gate D4: hidden/disabled if no Vulkan or model incompatible (Phase 0 outcome).
   - **Post-assignment toggle (Q1):** on the language card's voice-input row, for an already-assigned model, surface the current GPU state (small "GPU" indicator next to the model name) that is *tappable* — opens a small confirm dialog with the same switch, letting the user flip GPU on/off without re-assigning/re-importing the model. Both dialog and row-toggle write the same per-locale key.
2. Settings strings: new strings in `strings-voiceinput.xml` (GPU toggle label/subtitle; capability-unavailable tip).
3. Error copy (§D5): distinct user-facing states in `RecognizerView`/`ModelError`:
   - GPU-load OOM: "This model is too large to load on this device's GPU. Try a smaller model or switch it to CPU."
   - CPU-load OOM: "...not enough memory on this device. Try a smaller model."
   - Keep existing corrupt/invalid copy for `InvalidModelException`.
4. (Optional, cheap) proactive preflight: before whisper init, compare file size + a memory budget (device RAM for CPU; reported Vulkan heap for GPU) and fail fast with the same typed message instead of waiting for an OOM.

**Exit criteria:** the exact flows in R1/R3/R4 demonstrable in the settings UI; error states reachable in a device test.

### Phase 4 — Device verification (user has hardware)

Checklist per model/backed combo (CPU-default regression, GPU per model):
1. Install build; toggle GPU off → transcribe → baseline behavior identical to today.
2. Assign model, toggle GPU on → transcribe → `ggml_vulkan` device line appears in logs; result correct (R2, R6).
3. Capability gate on a non-Vulkan/old device if available (R3).
4. Memory-full: load a large model on a small device (or with GPU on + small device heap) → typed error message, no crash (R4).
5. Cold/warm load times + first-utterance latency CPU vs GPU; quantization (fp16) availability on the real GPU.

---

## 6. Risks

1. **ACFT compatibility with stock whisper.cpp** — biggest unknown; Phase 0 exists to retire it. Mitigation if incompatible: restrict GPU to non-ACFT models, or (larger) port Vulkan into FUTO's whisper fork.
2. **APK size / build complexity** — a second full whisper.cpp + ~SPIR-V shaders ≈ tens of MB per ABI. Mitigate: split by ABI (arm64 only for v1), strip, consider delivering GPU libs as an on-demand add-on download later (matches the existing "download a model" UX pattern).
3. **We cannot compile the APK in this environment** (no Android SDK/NDK, no Java here). All in-repo work stays reviewable; compilation + device test happens on your machine (you have the toolchain + device).
4. **GPU device-memory pressure on phones** — Vulkan devices with small heaps (or shared-memory heaps already in use) can fail at graph/buffer allocation after init succeeds; the typed-error path must also cover *inference-time* allocation failures (treat as load failure and revert state, not crash).
5. **Long-lived cached contexts** (2.1.6) mean a GPU context pins device memory for the whole action session; ensure close path (`ModelManager.cleanUp`) reliably frees Vulkan resources (validation via `dumpsys gfxinfo`/memory profiles on device).

---

## 7. Decisions & open questions

**Decided by user (2026):**

- **Q1 — scope of "specify the model": (a) + a post-assignment toggle.** GPU proposal goes in the per-language assignment dialog **and** the user can change GPU on/off for an *already-assigned* model (a second toggle, no re-import needed). No upstream-style size-list picker restoration requested. → Shapes Phase 3 step 1 (two UI touchpoints writing the same per-locale key).
- **Q2 — GPU default: OFF always.** Every newly assigned model starts CPU. GPU is opt-in per model (safest, conservative; matches "turn on if the user wants to enable").
- **Q4 — model format: no conversion needed — CONFIRMED empirically.** User asserted `*_acft_q8_0.bin` are GGUF-container, no translation required. Phase 0 refined this: these files are actually the **legacy `ggml` whisper container** (magic `0x67676d6c` — same constant FUTO's vendored loader checks at `native/.../ggml/whisper.cpp:1121`), not GGUF-magic. **Stock v1.9.3 reads them anyway**: the run loaded `DeadBranches/...-q8_0.bin` (874 MB) and transcribed correctly, so no conversion/translation layer is needed. Container nuance is invisible to the engine (v1.9.3 accepts both).

- **Q3 — granularity: model level only.** GPU choice persists per model assignment only. **No** global master switch.
- **Q5 — ABI: `arm64-v8a` only for v1.** User asked "is amd64 Android?" — clarification recorded: Android phones are **ARM64** (`arm64-v8a`); AMD/x86_64 does **not** run on phones (x86_64 Android exists only on emulators / rare old Intel tablets, and Vulkan there is irrelevant to this feature). The GPU engine ships for `arm64-v8a` only; `armeabi-v7a` (32-bit) is not targeted (Vulkan support there is negligible). CPU-only behavior for other ABIs is unchanged — no GPU engine packaged there.
- **Q6 — Phase 0 + speed-validation model: `DeadBranches/acft-whisper-large-v3-turbo_q8_0`.** User-specified (FUTO ACFT whisper-large-v3-turbo, GGUF/q8_0). One model used for both the compatibility run and the R6 speed benchmark — large model ⇒ measurable CPU-vs-GPU delta on device; also a natural R4 memory-stress case.
- **Q7 — vendor shape: vendored tree in-repo.** GPU engine source committed under `native/jni/src/whisper-gpu/` (no submodule / pinned fetch).
