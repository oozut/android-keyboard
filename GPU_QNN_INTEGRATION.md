# QNN Acceleration — Integration Notes

Date: this work is against the Qualcomm QNN SDK **2.32.0.250228** (AIR), the
ONNX exports in `/home/gc/qnn-work`, and the FUTO keyboard tree.

> **Related:** [`GPU_VULKAN_FINDINGS.md`](./GPU_VULKAN_FINDINGS.md) (why the
> Vulkan/whisper.cpp path is routed away from Snapdragon/Mali),
> [`GPU_HEXAGON_INVESTIGATION.md`](./GPU_HEXAGON_INVESTIGATION.md) (the separate
> ggml-hexagon FastRPC backend).

## Status: honest scaffolding, decoder blocked

What ships in this tree is **plumbing, not a functional QNN engine**. The
encoder converts and lowers to QNN cleanly; the decoder does **not**:

```
qnn-onnx-converter (decoder, dry-run):
  LessOrEqual: unsupported version
```

`LessOrEqual` is the decoder's causal attention mask. It is not one of the ops
the converter can lower for HTP, and it cannot be trivially substituted while
preserving the mask. Producing a runnable QNN context binary for whisper
requires Qualcomm's **single-head-attention (SHA) decoder re-architecture**
(the approach used by `whisper-htp-android`, MIT) — a decode-graph rewrite, not
a converter-flag change. Until that lands there is **no decoder context
binary**, and therefore no functional QNN decode path.

What *does* exist and build:

- `Backend` enum + `BackendSupport` device-type routing (see below).
- A `FileKind.VoiceInputQnn` import field that recognises a QNN context binary
  as a distinct model file (different from whisper.cpp's ggml `.bin`).
- `QnnWhisperEngine` — an honest skeleton that throws
  `GpuUnavailableException` on construction so the recognizer degrades to the
  CPU path (or shows the hardware-unavailable error) instead of silently
  misbehaving.
- `ModelQnnFileFile` — the loader that maps a `.qnn.bin` context binary to the
  QNN engine.
- `uses-native-library libcdsprpc.so` (required=false) in the manifest.

## Model-format change (the "field" that changed)

The model-import field previously accepted a single voice-input format: a
whisper.cpp ggml model whose first 4 bytes are `ggml` (big-endian-read as
`0x6c6d6767`). QNN cannot run a ggml model — it needs a **QNN context binary**
produced by `qnn-context-binary-generator` (or the newer `qnn-context-binary`
path). So a new file kind was added rather than overloading the old one:

| FileKind          | Extension    | Magic (on disk)                                        | Backend        |
|-------------------|--------------|--------------------------------------------------------|----------------|
| `VoiceInput`      | `.bin`       | `ggml` → `0x6c6d6767`                                  | CPU / Vulkan   |
| `VoiceInputQnn`   | `.qnn.bin`   | container header `00 00 00 02 00 00 00 03` (+ build-id) | QNN (Snapdragon) |
| `VoiceInputQnnZip`| `.qnn.zip`   | ZIP (`PK\x03\x04`) containing `encoder.bin` + `decoder.bin` (any nesting) | QNN (Snapdragon) |

### ZIP detection — VERIFIED against the real AI Hub package

The AI Hub `qnn_context_binary` download is a ZIP that nests the model parts
under a top-level directory (`<name>/encoder.bin`, `<name>/decoder.bin`), so
the *first* local entry is a directory, not a model part. `determineFileKind`
scans local file headers (seek-only, no decompression) for any entry whose base
name is `encoder.bin` or `decoder.bin`. Verified against
`whisper_large_v3_turbo_quantized-qcs8550-proxy-qnn_context_binary.zip` —
matches at entry 2 (`…/decoder.bin`).

### Magic detection — VERIFIED against a real context binary

The original assumption (HTP pickle magic at offset 0) was **wrong** and has
been corrected. Verified against the official Qualcomm AI Hub precompiled
`encoder.bin`/`decoder.bin` (`qualcomm-qcs8550-proxy`, qairt 2.45.0):

- **Offset 0** is a **QNN container header**, big-endian: bytes
  `00 00 00 02 00 00 00 03` (container format version `2.3`).
- The HTP *pickle* magics DO exist but sit ~400 KB into the file —
  `Hdr_MAGIC` (LE `0x7309F72B`) at offset 413696, `Hdr_MAGIC_MULTI`
  (LE `0x3790FA5C`) at offset 401408 — unreachable by a 4-byte sniff.
- The SDK **build-id string** `v2.45.0.260326154327` sits at offset ~1852
  (present in the first 4 KB of every context binary).

`determineFileKind` now reads up to 4 KB and classifies a file as
`VoiceInputQnn` when **both** hold:

1. first two big-endian words are `0x00000002` and `0x00000003` (container
   version 2.3), and
2. an embedded `v<digit>.<digit>…` build-id string is present in the header.

This is robust without relying on the proprietary container-body layout.
`Hdr_MAGIC`/`Hdr_MAGIC_MULTI` remain useful as a *body-level* sanity check but
are not used for offset-0 detection.

## Device-type validation (backend routing)

`BackendSupport.resolveBackend(context, preferHardware)` in
`voiceinput-shared/.../ggml/BackendSupport.kt`:

```
preferHardware off  -> CPU
Snapdragon          -> QNN     (Adreno; Vulkan path is known-broken here)
Mali (Exynos/MTK)   -> CPU     (Mali; Vulkan path is known-broken here)
Vulkan >= 1.2       -> VULKAN  (whisper.cpp GPU path)
otherwise           -> CPU
```

Snapdragon detection prefers `Build.SOC_MANUFACTURER` (API 31+) containing
`qualcomm`/`qti`, falling back to `ro.hardware`/`ro.product.board` code names
(`msm`, `sdm`, `sm8*`, `kalama`, `pineapple`, …). Mali detection covers
`mediatek`/`samsung`/`exynos`/`mt6*`/`s5e`/`mt8*`/`mt9*`.

A ggml model on a QNN backend (or a QNN backend that is not yet functional)
**falls back to CPU** — ggml cannot run on QNN and QNN cannot run ggml. A QNN
context binary on a non-QNN backend raises `GpuUnavailableException`, which the
recognizer maps to a dedicated `ModelLoadFailure.HardwareUnavailable` message
(`model_load_hw_unavailable`).

## Threading through the call chain

- `VoiceInputAction.loadSettings()` computes `preferHardware`
  (`getVoiceInputModelUseGpu || primaryModel is ModelQnnFileFile`) and resolves
  a `Backend` via `BackendSupport.resolveBackend`.
- `MultiModelRunConfiguration(backend = …)` (was `useGpu: Boolean`).
- `MultiModelRunner` → `ModelManager.obtainModel(model, backend)`, cache key
  `(modelKey, backend)`.
- `ModelLoader.loadGGML(context, backend)`.
- ggml loaders route `VULKAN` → GPU-with-CPU-fallback, everything else → CPU.
- `ModelQnnFileFile.loadGGML` routes `QNN` → `QnnWhisperEngine`, else throws.

## Runtime dependency (when the engine lands)

The QNN HTP runtime needs, on device, `libQnnHtp.so` + `libcdsprpc.so` (the
FastRPC transport) plus the HTP "skel" shared objects. `libcdsprpc.so` is a
system lib on Snapdragon and is declared in the manifest as
`required=false`. Two integration options once a decoder binary exists:

1. **ONNX Runtime QNN EP** — `com.microsoft.onnxruntime:onnxruntime-android-qnn:1.27.0`
   (verified fetchable from Maven Central; 8.0 MB, ships
   `libonnxruntime.so` + `libonnxruntime4j_jni.so`). This is the exact
   version the `whisper-htp-android` reference uses.
   No `libQnnHtp.so` vendoring needed; ORT ships the QNN backend and loads
   the system libraries.

   The QNN runtime itself is pulled separately as
   `com.qualcomm.qti:qnn-runtime:2.48.0` (verified: 68 MB AAR containing
   `libQnnHtp.so`, `libQnnHtpPrepare.so`, `libQnnSystem.so`, `libQnnDsp.so`,
   and the HTP skel/stub pairs for **V66/V68/V69/V73/V75/V79/V81** — V73 is
   the Galaxy S23 Ultra / SM8550 target).

   **Version note:** the precompiled binaries are built with qairt
   **2.45.0.260326154327**, so the runtime must be **≥ 2.45.0**. The reference
   app pins `qnn-runtime:2.48.0` (a 2.45-built context binary loads fine on a
   newer runtime). Do **not** use the local 2.32 SDK's runtime to load these
   binaries — 2.32 < 2.45 and will reject them.
2. **Direct QNN C API** — vendored `libQnnHtp.so` + `libQnnSystem.so` +
   skels, load `libcdsprpc.so` via `System.loadLibrary`, and call
   `QnnContext_createFromBinary`. More control (matches how
   `whisper-htp-android` works) but more build work.

## What remains before QNN can actually transcribe

1. Decoder SHA re-architecture (see top) so the causal mask no longer relies on
   `LessOrEqual`.
2. Generate a real encoder+decoder context binary with
   `qnn-context-binary-generator` for the target HTP arch.
3. DONE — context-binary leading bytes verified and `determineFileKind` fixed
   to detect the container header + build-id (see above).
4. Implement `QnnWhisperEngine` (open binary, build input tensor, run
   encoder/decoder loop, map output tokens) using one of the two runtime
   options above.

## Deploying test artifacts to the phone (Syncthing)

Test APKs and model ZIPs are synced to the S23 via Syncthing, which runs
**inside a podman container** (`systemd-syncthing`) — not on the host directly.
This matters because the container's `/var/syncthing` is a **named volume**, so
the host path `/var/syncthing/s23` is **NOT** the synced folder (files dropped
there are invisible to Syncthing).

Correct host-side drop location for the `s23` shared folder
(`ss6ek-hhrv7`, `sendreceive`, container path `/var/syncthing/s23`):

```
/var/lib/containers/storage/volumes/syncthing-data/_data/s23/
```

Container mount mapping (for reference):

| Host source | Container path |
|---|---|
| `syncthing-data` volume `_data` | `/var/syncthing` |
| `/home/gc/syncthing` (bind) | `/syncthing` |

Notes:

- The volume's parent dirs are root-owned, so moving files into the drop path
  needs `sudo` (the `s23` dir itself is `gc`-owned).
- After dropping files, confirm Syncthing indexed them via its API
  (`folder=ss6ek-hhrv7`): expect `state: idle` with
  `globalFiles == localFiles == inSyncFiles`.
- To change where files are synced from (e.g. to the `/home/gc/syncthing` bind
  mount), edit the folder `path` in the container's
  `/var/syncthing/config/config.xml` and restart the container.
