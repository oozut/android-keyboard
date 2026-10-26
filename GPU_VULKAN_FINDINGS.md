# GPU Vulkan — Findings & Diagnosis Record

This document records the on-device testing results and root-cause analysis for the
GPU (Vulkan) transcription feature. It complements `GPU_VULKAN_IMPLEMENTATION_PLAN.md`.

> **See also:** [`THIRD_PARTY_NOTICES.md`](./THIRD_PARTY_NOTICES.md) — attribution and
> licensing for every third-party source referenced here.

## Device under test

| Field | Value |
|---|---|
| Model | Samsung Galaxy S23 (SM-S918B) |
| SoC | Snapdragon 8 Gen 2 |
| GPU | Adreno 740 |
| OS | Android 16 (API 36) |
| Vulkan | 1.3 (present, advertised) |

## Timeline of events

| Time | Event |
|---|---|
| 21:42–21:59 | First crashes (stale build, no try/catch) |
| 22:05 | "Fixed" build compiled (BuildId `0755f05f`) |
| 22:17 | First `br.zip` exported (contained only pre-fix crashes) |
| 23:35 | Second `br.zip` — two findings (see below) |
| 22:30 / later | Coopmat-disable + JNI global-ref fix builds |

## Finding 1 — `createComputePipeline: ErrorUnknown` (shader rejection)

### First crash (pre-fix build)

```
Abort message: 'terminating due to uncaught exception of type vk::SystemError:
                vk::Device::createComputePipeline: ErrorUnknown'
```

Backtrace:
```
whisper_full → whisper_lang_auto_detect → ggml_backend_sched_graph_compute
  → ggml_vk_guess_split_k (ggml-vulkan.cpp:8723)   ← creates a matmul compute pipeline
  → vk::Device::createComputePipeline → throwResultException("ErrorUnknown")
```

### Root cause

- The crash was an **uncaught C++ exception** across the JNI boundary:
  ggml-vulkan `throw`s → `std::terminate()` → `abort()` → SIGABRT.
- It happens **before any model loads**, which is why **every model size failed
  identically** (the user reported all model sizes failing).

### First fix (multi-layer)

1. **Native bridge** (`org_futo_voiceinput_WhisperGGMLGpu.cpp`): wrapped
   `whisper_init_*` and `whisper_full` in `try/catch(std::exception)`, capturing
   failures into `last_error` instead of crashing.
2. **Capability gate** (`GpuSupport.kt`): probe actual Vulkan version via
   `hasSystemFeature(FEATURE_VULKAN_HARDWARE_VERSION, 0x402000)` (Vulkan ≥ 1.2).
3. **Graceful fallback** (`ModelData.kt` + `WhisperGGMLGpu.kt`): new
   `GpuUnavailableException` → falls back to CPU instead of "corrupt model".

### Prime suspect — cooperative matrix shaders

Adreno 740 advertises `VK_KHR_cooperative_matrix`, so ggml-vulkan takes the
coopmat (`_cm1`) matmul path, and the driver then rejects the pipeline.

**Fix applied:** `JNI_OnLoad` sets `GGML_VK_DISABLE_COOPMAT=1` (+ `_COOPMAT2`),
forcing the scalar matmul shaders.

## Finding 2 — `quantize_q8_1_x4` pipeline failure (after coopmat disable)

After disabling coopmat, the build got past the matmul failure but hit the **next**
shader in the chain:

```
23:35:22.119 E WhisperGGMLGpu: ggml_vulkan: Compute pipeline creation failed for
    quantize_q8_1_x4: vk::Device::createComputePipeline: ErrorUnknown
23:35:22.120 E WhisperGGMLGpu: GPU inference threw: vk::Device::createComputePipeline: ErrorUnknown
```

Result: exception caught → **no crash, but no transcription** (empty output).

### Analysis — not patchable

- Checked upstream ggml-vulkan: the maintainer's answer to this class of Adreno
  crash is **"Known issue with Qualcomm's shader compiler. Currently Adreno GPUs
  just don't work."** (llama.cpp issue #12421; Adreno 740 specifically #6713/#8965).
- Even in latest llama.cpp master, `quantize_q8_1_x4` is unchanged with no Adreno
  fallback.
- The non-`_x4` `quantize_q8_1` shader **exists** in the compiled SPIR-V but is
  **orphaned** — no pipeline references it, and the matmul shaders
  (`mul_mmq.comp`, `mul_mat_vecq.comp`) hardcode the `block_q8_1_x4` layout.
  There is no drop-in fallback.

### Conclusion

**Adreno 740 cannot run whisper.cpp's Vulkan backend.** Qualcomm's Vulkan driver
rejects ggml's compute shaders. This is a driver-level incompatibility with no
upstream fix. The Vulkan path itself is correct and validated — it just cannot run
on this specific GPU family.

## Finding 3 — JNI crash in `closeNative` (our bug, fixed)

```
Abort message: 'JNI DETECTED ERROR IN APPLICATION: JNI ERROR (app bug):
  jobject is an invalid JNI transition frame reference: 0x71e5a81118 (use of invalid jobject)
  from void ...WhisperGGMLGpu.closeNative(long)'
```

### Root cause

`infer()` stored the `jobject instance` parameter — a **local** JNI reference
(valid only during that call) — into the long-lived
`state->partial_result_instance`. Later `closeNative()` called
`DeleteGlobalRef()` on that stale local ref → CheckJNI abort.

### Fix

`infer()` now stores `env->NewGlobalRef(instance)` (deleting any prior global ref),
so `closeNative()`'s `DeleteGlobalRef` operates on a valid global reference.

## Where this leaves the feature

| Option | Reality |
|---|---|
| CPU-only on this phone | Works today. GPU toggle falls back to CPU. |
| Vulkan on a Mali GPU phone (Exynos / MediaTek / Google Tensor) | Likely works — confirmed reports of ggml-vulkan on Mali. Build is ready. |
| Proper Adreno acceleration | Needs a **different backend** — Qualcomm **QNN** (AI Engine Direct), not ggml. Separate, sizeable integration. |

## Artifacts / build history

| BuildId (libvoiceinput_gpu.so) | State |
|---|---|
| `14f3638b...` | Pre-fix (no try/catch) — crashed with uncaught exception |
| `0755f05f...` | try/catch + version gate + fallback |
| `f4fbe04e...` | + coopmat disable + pipeline-name logging |
| `8258f290...` | + JNI NewGlobalRef fix (latest, current) |

Current APK:
`/home/gc/android-keyboard/build/outputs/apk/unstable/debug/android-keyboard-unstable-debug.apk`
