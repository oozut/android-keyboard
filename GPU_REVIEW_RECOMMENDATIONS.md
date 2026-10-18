# Hardware-Accelerated Voice Input — Review Recommendations

> **Purpose:** actionable, prioritized list of fixes and improvements for the
> hardware-acceleration change (`6141656bf`, "Add hardware-accelerated voice input
> (Vulkan + QNN/HTP)") on branch `GPUEnablement`.
>
> **Scope reviewed:** the ~128 authored files only (the 1277 vendored ggml /
> SPIR-V / ORT headers are excluded). Kotlin call chain + native bridges + build/UI.
>
> **Priority legend:** **P0** = functional bug, fix before shipping · **P1** =
> feature parity / UX · **P2** = native hygiene · **P3** = docs · **P4** =
> size / maintainability.
>
> **Status (2026-10-10):** all P0, P1 and P2 items below are addressed in the
> working tree on `GPUEnablement` and verified with `assembleUnstableDebug`
> (`BUILD SUCCESSFUL`, native libs recompiled + relinked). See the per-item
> notes. P3/P4 remain open.

---

## P0 — Correctness (fix first)

- [x] **1. Force the QNN backend for `ModelQnnZip`, not just `ModelQnnFileFile`.**
  - Where: `java/src/org/futo/inputmethod/latin/uix/actions/VoiceInputAction.kt:144-148`
  - Problem: `preferHardware = getVoiceInputModelUseGpu(...) || primaryModel is ModelQnnFileFile`.
    The working ZIP path returns `ModelQnnZip`, and `getVoiceInputModelUseGpu()`
    reads the **ggml `FileKind.VoiceInput`** pref key, which does not exist for a
    QNN-only user → `false` → `resolveBackend(..., false)` → `Backend.CPU` →
    `ModelQnnZip.loadGGML` throws `GpuUnavailableException`
    (`ModelData.kt:228-235`) → UI falsely reports "hardware unavailable".
  - Recommended change: add a `val requiresHardware: Boolean` (default `false`) to
    the `ModelLoader` interface, return `true` from `ModelQnnFileFile`/`ModelQnnZip`,
    and use `primaryModel.requiresHardware` in the `preferHardware` expression.

- [x] **2. Scope the import-failure handling to the QNN ZIP branch.**
  - Where: `java/src/org/futo/inputmethod/latin/uix/ImportResourceActivity.kt:927-1019`
  - Problem: `qnnZipImportFailed` is set by a `catch (e: Exception)` that wraps
    **all** import kinds, so a failed transformer / dictionary / ggml import shows
    the QNN-ZIP-specific string (`file_kind_qnn_zip_invalid`) and suppresses
    `GlobalIMEMessage.tryEmit(ReloadResources)`.
  - Recommended change: only set the QNN flag inside the `VoiceInputQnnZip` branch;
    show a generic error toast otherwise; keep emitting `ReloadResources` on
    non-QNN failures. Re-indent the block inside `withContext(Dispatchers.IO) {`.

- [x] **3. Reset the global whisper log callback on `close()`.**
  - Where: `native/jni/src/whisper-gpu/org_futo_voiceinput_WhisperGGMLGpu.cpp:134,164,342-349`
  - Problem: `whisper_log_set(log_capture, state)` sets ggml's **process-global**
    callback with `user_data = state`; `close()` does `delete state` without
    restoring it → any later log dereferences freed memory (use-after-free). With
    two GPU models loaded, the last `whisper_log_set` also wins, breaking the
    other engine's `last_error` classification.
  - Recommended change: call `whisper_log_set(nullptr, nullptr)` (or a stable
    router) in `close()`, and/or install the callback once at `JNI_OnLoad` with a
    live-state registry instead of a raw state pointer.

- [x] **4. Make the hardware gates ABI-aware (avoid `UnsatisfiedLinkError`).**
  - Where: `voiceinput-shared/.../ggml/GpuSupport.kt:22`, `BackendSupport.kt:78`,
    `voiceinput-shared/.../types/ModelData.kt:47-51`
  - Problem: both native engines are **arm64-v8a only**, but the gates check only
    device type / Vulkan version. An x86_64 emulator with Vulkan 1.2 → `VULKAN` →
    `System.loadLibrary("voiceinput_gpu")` → `UnsatisfiedLinkError`, which
    `loadGpuOrFallback` (catches only `GpuUnavailableException`) and
    `AudioRecognizer.preloadModels` (catches only the 3 typed exceptions) do not
    handle.
  - Recommended change: require `"arm64-v8a" in Build.SUPPORTED_ABIS` in the
    capability checks; also catch `LinkageError` in `loadGpuOrFallback` and wrap
    QNN construction so a missing lib surfaces as `GpuUnavailableException`.

---

## P1 — Feature parity & UX

- [x] **5. Wire the glossary / prompt into the QNN decoder (or disable it explicitly).**
  - Where: `voiceinput-shared/.../ggml/QnnWhisperEngine.kt:130-161`
  - Problem: `buildPrompt(languages)` ignores the `prompt` argument, so the
    personal-dictionary glossary that the CPU path honours is silently dropped.
    `bailLanguages`, `decodingMode` (always greedy, not BeamSearch5) and
    `suppressNonSpeechTokens` are also ignored.
  - Recommended change: tokenize the glossary into the decoder prompt for QNN, or
    at minimum disable/annotate the glossary UI for QNN models so the gap is not
    silent.

- [x] **6. Resolve the single-file QNN loader.**
  - Where: `voiceinput-shared/.../types/ModelData.kt:199`
  - Problem: `QnnWhisperEngine(context, file, file)` uses the same file as both
    encoder and decoder session — almost certainly non-functional for a single
    graph. The doc comment at `ModelData.kt:175-177` ("throws on construction,
    blocked on `LessOrEqual`") is stale.
  - Recommended change: support an encoder+decoder pair for the legacy
    `FileKind.VoiceInputQnn` path, or remove `ModelQnnFileFile` /
    `FileKind.VoiceInputQnn` to shrink the surface.

- [x] **7. Fix the misleading hardware-acceleration switch on Snapdragon.**
  - Where: `java/src/.../settings/pages/Languages.kt:420`,
    `voiceinput-shared/.../ggml/BackendSupport.kt:78-88`
  - Problem: the toggle is shown whenever Vulkan ≥ 1.2 (Snapdragon qualifies), but
    Snapdragon always routes to QNN and a ggml model on QNN degrades to CPU — so
    the switch reads "faster transcription" yet does nothing.
  - Recommended change: hide the toggle where it cannot take effect, or show the
    resolved backend in the subtitle.

- [x] **8. Make `ModelManager` thread-safe.**
  - Where: `voiceinput-shared/.../whisper/ModelManager.kt:17-22` (+ `preload()` in
    `MultiModelRunner.kt`)
  - Problem: unsynchronized `HashMap` mutated by parallel `Dispatchers.Default`
    jobs (pre-existing, amplified by the new backend key).
  - Recommended change: `ConcurrentHashMap` + `computeIfAbsent`, or a `Mutex`.

---

## P2 — Native hygiene

- [x] **9. QNN decode-loop cleanups** (`native/jni/src/qnn-htp/htp_whisper.cpp`):
  - Release `outs` OrtValues on the mid-loop `check()` failure path (currently
    leaked).
  - `DeleteLocalRef` the `GetObjectArrayElement` references for languages/bail.
    (These are in the GPU bridge, `whisper-gpu/org_futo_voiceinput_WhisperGGMLGpu.cpp`
    `infer()`, not `htp_whisper.cpp`; fixed there.)
  - Guard `mel::init` global state against concurrent construction during
    `preload()`, or init the filterbank once.

---

## P3 — Documentation

- [ ] **10. Correct the drifted docs.**
  - Where: `GPU_QNN_INTEGRATION.md` still says the engine is an "honest skeleton"
    that throws on construction, that the ZIP holds `encoder.bin`/`decoder.bin`,
    and that detection reads 4 KB. Code is a working ORT loop, expects
    `encoder.onnx`/`decoder.onnx`, and reads up to 64 KB.
  - Also fix the stale comment at `ModelData.kt:175-177`, and the inventory counts
    in `GPU_FEATURE_WRAPUP.md` §4.1 (listed 17 files / +693-−83; actual 18 files /
    +1167-−82 for the non-vendored set).

---

## P4 — Size & maintainability

- [ ] **11. Use Maven coordinates for the QNN AARs.**
  - `com.microsoft.onnxruntime:onnxruntime-android-qnn:1.27.0` and
    `com.qualcomm.qti:qnn-runtime:2.48.0` are on Maven Central (confirmed in
    `GPU_QNN_INTEGRATION.md`). Removes ~76 MB of committed binaries from the `libs`
    submodule, improves provenance/licensing, and lets Gradle handle ABI filtering
    instead of hardcoded `implementation files('libs/...aar')`.

- [ ] **12. Attack APK/install size (currently ~248 MB).**
  - Enable ABI splits / App Bundle; note `useLegacyPackaging true` leaves native
    libs uncompressed on disk.
  - Consider on-demand model delivery (models are already large and per-arch).

- [ ] **13. Decide the Vulkan path's fate after a real Mali device test.**
  - If it doesn't pan out, dropping the Vulkan backend removes the ~51 MB
    `libvoiceinput_gpu.so` plus a large part of the vendored `whisper-gpu/ggml`
    tree. Otherwise narrow the vendored ggml source list or move it to a submodule.

- [ ] **14. Repo cleanup.**
  - Delete untracked `br.zip` (64 MB) and `.pod-sdk-install.log`.
  - Consolidate the five root `GPU_*.md` docs into two (plan/status + findings) and
    move them under `docs/`.

---

## Testing recommendations

- [ ] **15. Add unit tests** for:
  - `BackendSupport.resolveBackend` across the Snapdragon / Mali / Vulkan≥1.2 /
    none × preferHardware matrix.
  - `determineFileKind` on real QNN context-binary and AI Hub ZIP headers
    (64 KB sniff + ZIP local-header scan are subtle and currently untested).
  - A regression test asserting a QNN ZIP model resolves to `Backend.QNN`.

---

## Suggested order of work

1. P0 #1 and #2 (small, high user impact).
2. P0 #3 and #4.
3. P1 #5 (glossary) and #7 (UX).
4. P4 #11 and #14 (size/cleanup) — these also shrink future review surface.
5. P3 #10 last, reflecting the final implemented behavior.

> Note: P0 #1 is a prerequisite for any further QNN work, including the
> streaming/partial-transcription plan in `PARTIAL_TRANSCRIPTION_PLAN.md`.
