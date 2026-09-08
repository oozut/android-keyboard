# Third-Party Notices & Attribution

This file records every third-party source whose code, documentation, or
binaries we incorporate, adapt, or derive from in this repository, together
with the license under which each is used and a link to the original source.

**Convention for copied code:** any file that is copied from, or substantially
derived from, a third-party source MUST carry a header comment naming the
source, its license, and a link. Example:

```c
// Derived from whisper-htp-android (https://github.com/thedevguy/whisper-htp-android),
// MIT License, Copyright (c) 2026 Ali Daniali.
// Link: https://github.com/thedevguy/whisper-htp-android/blob/b33eae5/...
```

---

## 1. whisper.cpp / ggml — MIT License

- **Source:** https://github.com/ggerganov/whisper.cpp
- **Vendored version:** v1.9.3 (commit `371b5a7`)
- **License:** MIT
- **Copyright:** Copyright (c) 2023-2024, The ggml/whisper.cpp authors (Georgi Gerganov et al.)
- **How we use it:** vendored under `native/jni/src/whisper-gpu/` (ggml + whisper sources,
  plus the `third_party/` headers for `vulkan.hpp` and SPIR-V).
- **License text:** MIT — see `native/jni/src/whisper-gpu/ggml/LICENSE` and
  `native/jni/src/whisper-gpu/LICENSE` (the upstream files, kept in place).

---

## 2. whisper-htp-android (thedevguy) — MIT License ✅

- **Source:** https://github.com/thedevguy/whisper-htp-android
- **Pinned reference commit:** `b33eae5c9985db06a4fb520272c321dea6849907`
- **License:** MIT
- **Copyright:** Copyright (c) 2026 Ali Daniali
- **How we use it:** **reference implementation** for the QNN/HTP engine
  (mel front-end, ONNX Runtime QNN session setup, KV-cache decode loop).
  Any code copied or adapted from this project MUST carry the per-file
  attribution header above and reproduce the MIT notice.
- **License text (MIT):**

  ```
  Copyright (c) 2026 Ali Daniali

  Permission is hereby granted, free of charge, to any person obtaining a copy
  of this software and associated documentation files (the "Software"), to deal
  in the Software without restriction, including without limitation the rights
  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  copies of the Software, and to permit persons to whom the Software is
  furnished to do so, subject to the following conditions:

  The above copyright notice and this permission notice shall be included in all
  copies or substantial portions of the Software.

  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
  SOFTWARE.
  ```

---

## 3. anywhere-transcript (dan-el-7) — ⚠️ NO LICENSE — DO NOT COPY

- **Source:** https://github.com/dan-el-7/anywhere-transcript
- **Pinned reference commit:** `5c33faf13d04bc0840f22a548e691422c2f3ff01`
- **License:** **none declared** (no `LICENSE`/`COPYING` file anywhere in the tree;
  its README "License notes" section only describes *third-party* licenses,
  not its own code).
- **Legal status:** in the absence of a license, the code is **"all rights
  reserved"** under copyright law. **We must NOT copy code from this project.**
  We may read it for reference, ideas, and architecture only, and must write
  our own implementation.
- **Note:** this project's README itself attributes the QNN/HTP approach to
  `whisper-htp-android` (MIT) — use that as the copyable source instead.

---

## 4. Qualcomm AI Engine Direct SDK (QNN) — Qualcomm Proprietary

- **Source (official):** https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk
- **Community mirror:** https://github.com/qdsp6sw/qualcomm-ai-engine-direct-sdk
  (pinned `e32f4b482c9d0f941eae317e5a5d6dd48d600352`)
- **License:** Qualcomm proprietary (see `LICENSE.pdf` / `NOTICE.txt` in the SDK).
- **How we use it:** **build tooling only** — the `qnn-onnx-converter` and related
  tools are downloaded and executed at model-compile time. No SDK code is
  vendored into this repository; only the *output* (a QNN context binary,
  `qairt_context.bin`) is shipped.
- **Runtime dependency:** `com.qualcomm.qti:qnn-runtime` (Maven Central) and
  `onnxruntime-android-qnn` (Microsoft) — see their respective licenses when
  the QNN engine is added.

---

## 5. FUTO Keyboard (upstream) — FUTO Source First License 1.1-kb

- **Source:** https://gitlab.futo.org/keyboard/android-keyboard (fork base: FUTO Keyboard)
- **License:** FUTO Source First License 1.1-kb — see `LICENSE.md` (root).
- **Note:** non-commercial modification/distribution terms apply to this fork as
  a derivative of FUTO's code. Review `LICENSE.md` before any distribution.
- **AOSP portions:** Apache-2.0 — see `NOTICE` (root).

---

*Last updated: 2026-06-01 — reflects licensing state as of the pinned commits above.*
