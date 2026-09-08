# whisper.cpp Hexagon Backend — Investigation

Date: investigation performed against whisper.cpp **v1.9.3** (the exact tree vendored
at `native/jni/src/whisper-gpu/`), commit `371b5a7`, released 2026-08-20.

> **See also:** [`THIRD_PARTY_NOTICES.md`](./THIRD_PARTY_NOTICES.md) — attribution and
> licensing for every third-party source referenced here (incl. the QNN/HTP reference
> implementations and the Qualcomm SDK).

## Headline finding

**The tree we already vendored ships Qualcomm's official Hexagon DSP/HTP backend
(`ggml-hexagon`), fully built-out and merged upstream.** No new vendoring or external
patch is required — the backend is already in the source we have.

```
ggml/src/ggml-hexagon/
├── CMakeLists.txt              # backend lib + builds 4 DSP "skel" .so via ExternalProject
├── ggml-hexagon.cpp            # 4505 lines — host-side backend (FastRPC driver)
├── htp-drv.cpp / htp-drv.h     # HTP arch query / session driver
├── htp-opnode.h
└── htp/                        # DSP-side code, cross-compiled with hexagon-clang
    ├── CMakeLists.txt
    ├── cmake-toolchain.cmake   # Hexagon QURT cross-compile toolchain
    ├── main.c                  # skel entry point (htp_iface_open, hwinfo, invoke)
    ├── matmul-ops.c            # 227 KB — HVX + HMX matmul kernels
    ├── flash-attn-ops.c        # 116 KB
    ├── im2col-ops.c, softmax-ops.c, act-ops.c, rope-ops.c, ...
    └── (27+ op source files)
```

It is enabled with a single CMake option:

```
-DGGML_HEXAGON=ON
```

## What it is / how it works

1. **Two-part architecture:**
   - **Host side** (`ggml-hexagon.cpp`, runs on the ARM CPU): registers a ggml backend
     device, manages tensors/buffers, and drives the DSP over **FastRPC**.
   - **DSP side** (the `htp/` "skels"): compiled with Qualcomm's `hexagon-clang` into
     `libggml-htp-v{73,75,79,81}.so`, which are loaded **onto the Hexagon DSP/HTP**
     at runtime (`file:///libggml-htp-v<N>.so?htp_iface_skel_handle_invoke`).

2. **DSP version auto-detection:** `htpdrv_get_arch(CDSP_DOMAIN_ID, &arch)`, clamped to
   the v73–v81 range. Four skels are built to cover four DSP generations.

3. **Runs on HVX (Hexagon Vector eXtensions) and HMX (Hexagon Matrix eXtensions)** —
   the DSP's native vector/matrix accelerators, *not* the Adreno GPU. This sidesteps
   the Adreno 740 Vulkan driver bug that killed the ggml-vulkan path (see
   `GPU_VULKAN_FINDINGS.md`).

## Whisper op coverage

`ggml_backend_hexagon_device_supports_op` (ggml-hexagon.cpp:4057) claims every op
whisper's encoder/decoder graph needs:

- `MUL_MAT`, `MUL_MAT_ID` (matmul — HVX/HMX kernels)
- `IM2COL` (the encoder's conv1d → im2col + matmul)
- `ADD`, `SUB`, `MUL`, `DIV`, `SCALE`, `CLAMP`
- `SOFT_MAX`, `RMS_NORM`, `NORM`, `L2_NORM`
- `GELU`, `SQR`, `SQRT`, `UNARY` (activations)
- `GET_ROWS`, `SET_ROWS`, `SUM_ROWS`, `CONCAT`, `CPY`, `CONT`
- `REPEAT`, `FILL`, `PAD`, `ARGSORT`, `ROPE`

This is complete coverage for the whisper graph. No missing-op fallback risk.

## Integration surface (why this is low-friction for us)

The Hexagon backend **registers itself as `GGML_BACKEND_DEVICE_TYPE_GPU`**
(ggml-hexagon.cpp:3918). whisper.cpp's existing GPU selector
(`whisper_backend_init_gpu`, whisper.cpp:1290) picks the *first* device of type GPU/IGPU
when `params.use_gpu == true`.

Consequence: **our existing Kotlin `useGpu` flag + `whisper_init_*_params({ .use_gpu = X })`
JNI plumbing already reaches this backend with zero API change.** If `GGML_HEXAGON=ON` is
compiled in, `use_gpu=true` will select the Hexagon DSP as the GPU device automatically.
Only the *build* wiring and the *runtime capability gate* need changing.

## Requirements (the real cost)

| Requirement | Detail |
|---|---|
| **Qualcomm Hexagon SDK** | Proprietary. Requires a Qualcomm account + license agreement. Multi-GB download. Provides `HEXAGON_SDK_ROOT` (includes `build/cmake/hexagon_fun.cmake`, `incs/`, FastRPC) and the `hexagon-clang` toolchain (`HEXAGON_TOOLS_ROOT`). **Cannot be vendored or redistributed.** |
| **hexagon-clang toolchain** | The `hexagon-clang`/`hexagon-clang++` cross-compilers from the SDK, targeting `-mcpu=v73/-mhvx/-mhmx`. Not in the NDK. |
| **Signing** | The DSP skels must be signed. The backend attempts `DSPRPC_CONTROL_UNSIGNED_MODULE` ("Enable Unsigned PD") at runtime, which only works on devices/OSes that permit unsigned PD. For production, skels must be signed with a Qualcomm certificate. This is the main deployment friction. |
| **Build host** | The `ExternalProject_Add` compiles 4 skels × ~30 C files for 4 DSP archs using hexagon-clang — a heavy, non-NDK build step. |

## Device mapping (Snapdragon → DSP arch, approx.)

| SoC | Hexagon DSP arch | Skel |
|---|---|---|
| Snapdragon 8 Gen 1 (SM8450) | v69 | (below range — capped to v73) |
| **Snapdragon 8 Gen 2 (SM8550) — the test S23** | **v73** | `libggml-htp-v73.so` |
| Snapdragon 8 Gen 3 (SM8650) | v75 | `libggml-htp-v75.so` |
| Snapdragon 8 Elite (SM8750) | v79 | `libggml-htp-v79.so` |
| Snapdragon 8 Elite Gen 2 | v81 | `libggml-htp-v81.so` |

The test device (Galaxy S23, Snapdragon 8 Gen 2, Adreno 740) → **Hexagon v73**, covered
by the first skel. (Exact v-number for 8 Gen 2 should be confirmed on-device via the
backend's own `htpdrv_get_arch` log line.)

## The sibling `ggml-et` backend

`ggml/src/ggml-et/` is also present — a Qualcomm **AI Foundry ("ET" platform)** based
backend (`GGML_ET`, references `aifoundry-utils/ProjectFunctions`, `ET_PLATFORM`,
`GGML_ET_SYSEMU` for emulator). This is a *separate, newer* Qualcomm acceleration stack
(not the classic Hexagon SDK) with its own kernel set (mul_mat, flash_attn, rope, etc.).
Worth a follow-up look, but `ggml-hexagon` is the more mature/complete path and covers
whisper's full graph.

## Build scaffolding (implemented)

The wrapper CMake (`native/jni/src/whisper-gpu/CMakeLists.txt`) now selects the
acceleration backend via a cache variable:

```cmake
VOICEINPUT_HW_ACCEL_BACKEND  = vulkan  (default) | hexagon
```

- `vulkan` → `GGML_VULKAN=ON` (unchanged, working path).
- `hexagon` → `GGML_HEXAGON=ON`, `GGML_VULKAN=OFF`, and a build-time guard that
  `HEXAGON_SDK_ROOT` points at a real Hexagon SDK install (fails early with a clear
  message otherwise). A `voiceinput_htp_skels` custom target collects the four
  `libggml-htp-v*.so` skels so they can be packaged alongside `libvoiceinput_gpu.so`.
  Production skel signing is still a TODO (outside cmake).

Both backends compile into the single self-contained `libvoiceinput_gpu.so`; the CPU
path and `libjni_latinime.so` are untouched. The `vulkan` default was re-verified to
build cleanly (`BUILD SUCCESSFUL`) after the refactor.

## UI rename (implemented)

The user-facing label is now generic "hardware acceleration" instead of "GPU", since
the toggle may select the Hexagon DSP (not a GPU) on Qualcomm devices:

- `language_settings_voice_input_hw_accel_toggle` = "Use hardware acceleration"
  (was `language_settings_voice_input_gpu_toggle` = "Run this model on the GPU")
- `model_load_oom` now says "…too large to load using hardware acceleration…"
- `GpuUnavailableException` / `ModelOutOfMemoryException` messages reworded to
  "hardware acceleration" (class names unchanged for now).

## Conclusion / recommendation

1. **Hexagon is the correct answer for this device** — it uses the DSP/HTP, avoiding the
   broken Adreno Vulkan driver entirely.
2. **The backend is already vendored** in our `whisper-gpu` tree; no new source needed.
3. **The blocker is tooling, not code:** acquiring the Qualcomm Hexagon SDK + toolchain,
   and resolving DSP skel signing. Both require Qualcomm's proprietary bits.
4. **Recommended next step:** obtain the Hexagon SDK, then set
   `VOICEINPUT_HW_ACCEL_BACKEND=hexagon` (with `HEXAGON_SDK_ROOT`), cross-build
   `libvoiceinput_gpu.so` + the 4 skels inside distrobox, and confirm the DSP arch/load
   path on the S23 before committing to runtime-gate changes.

## Open questions

- Q-H1: Which exact Hexagon SDK version does `ggml-hexagon` expect? (toolchain probes
  `hexagon_sdk.json`, tool dir `toolv19_*`.)
- Q-H2: Can the S23 load **unsigned** skels (unsigned-PD enabled), or do we need Qualcomm
  production signing from day one? This decides whether on-device dev testing is possible.
- Q-H3: Is `ggml-et` (AI Foundry) preferable for shipping (signing/distribution model)?
