# Partial / Streaming Transcription Plan

Branch: `PartialTranscription`

## Goal

Start transcribing **while the user is still talking**, in small (~15 s) chunks,
instead of waiting for the recording to stop and transcribing the whole buffer
in one shot. The user sees text appear incrementally and the final result is
ready almost immediately after they stop.

Non-goals (for now): true token-level "live subtitles", multi-pass re-scoring,
punctuation post-processing.

---

## What already exists (important)

The partial-result pipeline is already wired end-to-end; we do **not** need to
add a new callback chain:

```
whisper.cpp partial_text_callback (native, token-level)
  -> WhisperGGML.invokePartialResult(text)
  -> WhisperEngine.infer(..., partialResultCallback)
  -> MultiModelRunner.run -> ModelInferenceCallback.partialResult
  -> AudioRecognizer.runnerCallback.partialResult -> AudioRecognizerListener.partialResult
  -> RecognizerView.partialResult
  -> VoiceInputActionWindow.partialResult -> inputTransaction.updatePartial(sanitized)
```

- CPU engine (`WhisperGGML`, FUTO whisper fork): has token-level
  `partial_text_callback` and supports `initial_prompt` (prompt carry). ✅
- Vulkan engine (`WhisperGGMLGpu`, upstream v1.9.3): **no** token-level partial
  callback (has `new_segment_callback`), but supports `initial_prompt` +
  `carry_initial_prompt`. Chunk-level partial (emit after each chunk) is fine.
- QNN engine (`QnnWhisperEngine`): single-shot **30 s window**; `infer` pads to
  30 s; no partial within decode; `buildPrompt()` currently **ignores** the
  `prompt` arg (prompt carry not implemented yet).
- Models are preloaded once (`preloadModels()`); `infer` is re-entrant on all
  backends, so repeated calls cost no model reload.

The gap is purely in **`AudioRecognizer`**: it only calls `runModel()` in
`onFinishRecording()`, i.e. after the user stops. We need a streaming loop.

---

## Design

### 1. Configuration (new)

Add to `RecordingSettings` (or a small `StreamingConfiguration`):

| Key | Type | Default | Meaning |
|---|---|---|---|
| `streamingEnabled` | bool | `true` | turn chunked transcription on/off |
| `streamingChunkSeconds` | int | `15` | audio seconds per chunk |
| `streamingPromptWords` | int | `200` | max words of prior transcript passed as prompt |

New settings key `STREAMING_TRANSCRIPTION` (`booleanPreferencesKey("streaming_transcription")`,
default true) + a toggle in the voice-input settings page. Threaded through
`VoiceInputAction.loadSettings()` -> `RecordingSettings` (or a new
`StreamingSettings`) -> `AudioRecognizer`.

### 2. `AudioRecognizer` — streaming loop

Add a `streamingJob` coroutine that runs **alongside** `recorderJob`:

- Shared state: `recordedSamples` (atomic int of valid floats in the buffer,
  currently `floatSamples.position()`), `processedSamples` (how much audio has
  already been transcribed), `runningTranscript: String`, and the resolved
  language/model (see §4).
- Loop (while `isRecording`):
  1. Wait until `recordedSamples - processedSamples >= chunkSeconds * 16000`.
  2. Snapshot audio slice `[processedSamples, processedSamples + chunkSamples)`
     **under a lock** (see §3), copying it into a fresh `FloatArray`.
  3. `infer(slice, prompt = tail(runningTranscript, N words))` on the existing
     single inference context.
  4. Append result to `runningTranscript`; advance `processedSamples` by the
     chunk size; emit `listener.partialResult(runningTranscript)`.
- On `finish()`:
  1. Stop recorder, cancel/join the streaming loop after the in-flight chunk.
  2. Transcribe the remaining tail `[processedSamples, recordedSamples)`.
  3. Emit `listener.finished(runningTranscript)`.

First chunk (cold start): chunk 0 transcribes `[0, 15s)` with prompt =
glossary. Later chunks pass the prior transcript as prompt (continuity). This
is the standard incremental-decoding pattern and matches whisper's
`initial_prompt` semantics (prompt tokens are treated as already-generated
history, so the model emits only the *new* words — no duplication).

### 3. Thread safety (critical)

Today `recordingJob` runs on `Dispatchers.Default` and mutates `floatSamples`
(including `expandSpaceIfAllowed()` which **reallocates** the buffer). The
streaming loop would race on `position()` and on reallocation.

Fix:
- Replace `floatSamples.position()` bookkeeping with a `@Volatile var
  recordedSamples` (or `AtomicInteger`), incremented by the recorder.
- Guard all `floatSamples` read/write + reallocation with a single
  `synchronized(lock)`. The streaming loop copies its slice to a new
  `FloatArray` under the lock, then runs `infer` **outside** the lock so
  recording never blocks on inference.
- Recording stays on its own thread (`AudioRecord.read` is blocking); inference
  stays on the existing single-thread `inferenceContext`.

### 4. `MultiModelRunner` — streaming API

`run()` currently re-resolves language/model every call (bail-language
switching). For streaming, resolve once and reuse:

- Add `openStream(runConfig, decodingConfig, callback)` returning a handle that
  caches the chosen `WhisperEngine` + resolved language + glossary prompt.
- `streamChunk(samples, prompt, callback)` runs one `infer` on the cached
  engine; language detection happens on chunk 0 and is reused after.
- Keep `run()` for the non-streaming fallback (and for a possible final
  full-buffer re-transcription).

### 5. Backend behavior matrix

| Backend | Chunk support | Prompt carry | Within-chunk partial |
|---|---|---|---|
| CPU (`WhisperGGML`) | full (any length) | yes (`initial_prompt`) | yes (token-level) |
| Vulkan (`WhisperGGMLGpu`) | full | yes (`carry_initial_prompt`) | no (segment only) |
| QNN (`QnnWhisperEngine`) | fixed 30 s window | **to add** | no |

QNN specifics (user's S23 Ultra uses QNN):
- `infer` pads/truncates to 30 s, so a 15 s chunk runs the 30 s encoder (no
  speed win for short chunks, but results still appear incrementally).
- **Prompt carry must be added**: pass the prompt string into the native
  `buildPrompt`/decode loop and seed the KV cache with the prompt tokens (small
  change in `qnn-htp/htp_whisper.cpp`). Until then, QNN chunks transcribe
  independently (accuracy may drop across chunk boundaries).
- Recommended QNN streaming shape: re-transcribe accumulated audio every 15 s
  up to 30 s, then slide a 30 s window with prompt carry.

### 6. UI

- Partial results already flow into the target editor via
  `inputTransaction.updatePartial()`. No UI change required for the core
  feature.
- Add the `streaming_transcription` settings toggle (reuse the existing
  settings-page patterns).
- Optionally set `shouldShowInlinePartialResult = true` later if we want an
  inline bubble instead of (or in addition to) live editor updates.

---

## Phasing

### Phase 1 — streaming loop + CPU/Vulkan (no native changes)
- `AudioRecognizer` streaming loop, atomic sample counter + lock, tail pass.
- `MultiModelRunner.openStream/streamChunk` + language caching.
- Config plumbing + settings toggle.
- `infer` prompt carry wired through the existing `prompt` arg (CPU/Vulkan
  already support it).
- Fallback: when `streamingEnabled == false`, behavior is byte-for-byte the
  current single-pass flow.

### Phase 2 — QNN prompt carry
- Native change in `qnn-htp/htp_whisper.cpp`: tokenize prompt into the decoder
  KV cache seed so chunk N continues from chunk N-1's text.
- Kotlin: pass `prompt` through `QnnWhisperEngine.infer` -> native.

### Phase 3 — accuracy & polish
- VAD-aligned chunk boundaries (cut on silence instead of fixed 15 s) to avoid
  mid-word splits.
- Optional overlap + suffix/prefix merge.
- Decide whether a final full-buffer re-transcription is worth the extra
  latency (probably off by default — the user wants faster, not slower).
- Trim: keep or drop depending on results.

---

## Risks / trade-offs

- **Boundary errors**: a word split across a 15 s boundary can be garbled.
  Mitigations: prompt carry (primary), VAD-aligned boundaries (phase 3).
- **Prompt carry duplication/hallucination**: whisper can occasionally repeat
  or drift off the prompt. Mitigation: cap prompt length, and keep an internal
  "no prompt carry" escape hatch if quality regresses.
- **QNN 30 s window**: no latency win for short chunks until prompt carry +
  sliding window are done; accuracy may briefly be worse than CPU.
- **Concurrency bugs**: the lock must cover buffer reallocation. Snapshot
  copies must happen outside `infer`.
- **Language detection**: only runs on chunk 0; a language switch mid-dictation
  won't re-bail (acceptable for v1; note it).

## Test plan

1. Build inside `android-dbx` distrobox: `./gradlew assembleUnstableDebug`.
2. Install on S23 Ultra (QNN path); dictate > 30 s; confirm:
   - first partial text appears ~15 s in,
   - text updates per chunk,
   - final result correct and not duplicated.
3. Same on a CPU/Mali device (Vulkan-off, CPU path) and with
   `streaming_transcription` toggled OFF (must match current behavior).
4. Watch logcat for races/crashes in the recorder/streaming handoff.
