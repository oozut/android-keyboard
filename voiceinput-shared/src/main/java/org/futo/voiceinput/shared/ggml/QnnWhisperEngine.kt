package org.futo.voiceinput.shared.ggml

import android.content.Context
import android.system.Os
import android.util.Base64
import androidx.annotation.Keep
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Qualcomm QNN (Hexagon HTP / NPU) engine.
 *
 * Runs Qualcomm AI Hub's precompiled Whisper-Large-V3-Turbo-Quantized (w8a16)
 * context binaries through ONNX Runtime's QNN Execution Provider. The native
 * side (libvoiceinput_qnn.so) holds the mel front end, the ORT sessions and a
 * fixed-shape KV-cache greedy decode loop (see native/jni/src/qnn-htp/).
 *
 * The model is a single-shot 30-second window (not the streaming VAD loop the
 * CPU engine uses): the mel front end pads/truncates its PCM to 30 s, runs
 * encoder + a greedy decode seeded with the prompt, and detokenizes the result.
 *
 * Because that window is a hard limit of the precompiled graph, [infer] decodes
 * long recordings in 29 s chunks with a 5 s overlap and de-duplicates the
 * overlapping text (see [LongFormChunking]); everything past 30 s used to be
 * dropped silently.
 */
@Keep
class QnnWhisperEngine(
    context: Context,
    private val encoderOnnxFile: File,
    private val decoderOnnxFile: File
) : WhisperEngine {

    companion object {
        private const val VARIANT_TURBO_W8A16 = 2
        private const val MEL_BINS = 128

        /**
         * Long-form chunking. Mirrors [LongFormChunking]'s defaults: the graph
         * only accepts 30 s of mel, so decode 29 s at a time and overlap by 5 s
         * to keep words on the boundary intact.
         */
        private const val CHUNK_SAMPLES = LongFormChunking.DEFAULT_CHUNK_SAMPLES
        private const val OVERLAP_SAMPLES = LongFormChunking.DEFAULT_OVERLAP_SAMPLES

        // Whisper large-v3 vocab special tokens (DIFFER from the v2/small ids).
        private const val SOT = 50258            // <|startoftranscript|>
        private const val TRANSCRIBE = 50360     // <|transcribe|>
        private const val NOTIMESTAMPS = 50364   // <|notimestamps|>
        private const val LANG_FIRST = 50259     // <|en|>; 100 language tokens follow
        /** Prompt sentinel: the native decoder detects the language from <|sot|> logits. */
        private const val AUTO_DETECT_LANGUAGE = -1
        // <|endoftext|>: the first control token. BPE *content* tokens are
        // 0..EOT-1, so only those are eligible for the decoder prompt.
        private const val EOT = 50257

        // ISO 639-1 code -> index into the whisper language-token block
        // (token id = LANG_FIRST + index). Order matches whisper.cpp g_lang.
        private val LANG_INDEX: Map<String, Int> = mapOf(
            "en" to 0, "zh" to 1, "de" to 2, "es" to 3, "ru" to 4, "ko" to 5,
            "fr" to 6, "ja" to 7, "pt" to 8, "tr" to 9, "pl" to 10, "ca" to 11,
            "nl" to 12, "ar" to 13, "sv" to 14, "it" to 15, "id" to 16, "hi" to 17,
            "fi" to 18, "vi" to 19, "he" to 20, "uk" to 21, "el" to 22, "ms" to 23,
            "cs" to 24, "ro" to 25, "da" to 26, "hu" to 27, "ta" to 28, "no" to 29,
            "th" to 30, "ur" to 31, "hr" to 32, "bg" to 33, "lt" to 34, "la" to 35,
            "mi" to 36, "ml" to 37, "cy" to 38, "sk" to 39, "te" to 40, "fa" to 41,
            "lv" to 42, "bn" to 43, "sr" to 44, "az" to 45, "sl" to 46, "kn" to 47,
            "et" to 48, "mk" to 49, "br" to 50, "eu" to 51, "is" to 52, "hy" to 53,
            "ne" to 54, "mn" to 55, "bs" to 56, "kk" to 57, "sq" to 58, "sw" to 59,
            "gl" to 60, "mr" to 61, "pa" to 62, "si" to 63, "km" to 64, "sn" to 65,
            "yo" to 66, "so" to 67, "af" to 68, "oc" to 69, "ka" to 70, "be" to 71,
            "tg" to 72, "sd" to 73, "gu" to 74, "am" to 75, "yi" to 76, "lo" to 77,
            "uz" to 78, "fo" to 79, "ht" to 80, "ps" to 81, "tk" to 82, "nn" to 83,
            "mt" to 84, "sa" to 85, "lb" to 86, "my" to 87, "bo" to 88, "tl" to 89,
            "mg" to 90, "as" to 91, "tt" to 92, "haw" to 93, "ln" to 94, "ha" to 95,
            "ba" to 96, "jw" to 97, "su" to 98, "yue" to 99
        )

        init {
            System.loadLibrary("voiceinput_qnn")
        }
    }

    private var handle: Long = 0L
    @Volatile private var cancelled = false
    private var partialResultCallback: (String) -> Unit = { }

    // Whisper large-v3-turbo detokenizer vocab (base64 UTF-8 bytes per id).
    private val vocab: Array<ByteArray>

    private class PromptVocab(val byText: Map<String, Int>, val maxBytes: Int)

    /**
     * Byte-level BPE vocabulary as ISO-8859-1 strings (1 char per byte), so the
     * initial prompt / glossary can be tokenized by greedy longest match. Only
     * content tokens (`id < EOT`) are indexed, so a control token can never be
     * emitted into the decoder prompt. Built lazily: the CPU path never uses it.
     */
    private val promptVocab: PromptVocab by lazy {
        val byText = HashMap<String, Int>()
        var maxBytes = 0
        for (id in 0 until minOf(EOT, vocab.size)) {
            val bytes = vocab[id]
            if (bytes.isEmpty()) continue
            val key = String(bytes, Charsets.ISO_8859_1)
            if (byText.putIfAbsent(key, id) == null) maxBytes = maxOf(maxBytes, bytes.size)
        }
        PromptVocab(byText, maxBytes)
    }

    init {
        // The HTP stub locates its Skel library via ADSP_LIBRARY_PATH; without
        // it QNN device creation fails with QNN_DEVICE_ERROR_INVALID_CONFIG.
        Os.setenv("ADSP_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir, true)

        // Unpack the bundled mel filterbank + detokenizer vocab to files.
        val assetDir = File(context.filesDir, "qnn").apply { mkdirs() }
        val filtersFile = copyAssetToFile(context, "mel_filters_128.bin", File(assetDir, "mel_filters_128.bin"))
        val vocabFile = copyAssetToFile(context, "whisper_vocab_v3.json", File(assetDir, "whisper_vocab_v3.json"))

        if (!nativeMelInit(filtersFile.absolutePath, MEL_BINS)) {
            throw GpuUnavailableException("Failed to load the QNN mel filterbank")
        }

        handle = nativeInit(
            context.applicationInfo.nativeLibraryDir,
            encoderOnnxFile.absolutePath,
            decoderOnnxFile.absolutePath,
            VARIANT_TURBO_W8A16
        )
        if (handle == 0L) {
            val error = lastError().ifBlank {
                "QNN session init failed (see logcat tag QnnWhisper)"
            }
            throw GpuUnavailableException(error)
        }

        vocab = loadVocab(vocabFile)
    }

    private fun copyAssetToFile(context: Context, assetName: String, dest: File): File {
        if (!dest.exists() || dest.length() == 0L) {
            context.assets.open(assetName).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dest
    }

    private fun loadVocab(file: File): Array<ByteArray> {
        val arr = JSONArray(file.readText())
        return Array(arr.length()) { Base64.decode(arr.getString(it), Base64.DEFAULT) }
    }

    private fun detokenize(ids: IntArray): String {
        val bytes = ByteArrayOutputStream()
        for (id in ids) if (id in vocab.indices) bytes.write(vocab[id])
        return bytes.toString(Charsets.UTF_8.name())
    }

    /**
     * Greedy longest-match tokenization of [prompt] against the byte-level BPE
     * vocabulary. This is not whisper's merge-rank BPE (the vocab asset carries
     * no merges), but it maps the personal-dictionary glossary to valid content
     * tokens instead of dropping it, which is what the CPU path seeds the
     * decoder with. Bytes with no single-token match are skipped defensively.
     */
    private fun tokenizePrompt(prompt: String): IntArray {
        if (prompt.isEmpty()) return IntArray(0)
        val vocab = promptVocab
        if (vocab.byText.isEmpty()) return IntArray(0)

        val text = String(prompt.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        val ids = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            var length = minOf(vocab.maxBytes, text.length - i)
            var matched = false
            while (length >= 1) {
                val id = vocab.byText[text.substring(i, i + length)]
                if (id != null) {
                    ids.add(id)
                    i += length
                    matched = true
                    break
                }
                length--
            }
            if (!matched) i++
        }
        return ids.toIntArray()
    }

    /**
     * Builds the decoder prompt: the fixed task prefix (`<|sot|>` + language +
     * `<|transcribe|>` + `<|notimestamps|>`) followed by the tokenized initial
     * prompt / glossary, matching how whisper.cpp seeds `initial_prompt`.
     *
     * With more than one allowed language the prompt carries
     * [AUTO_DETECT_LANGUAGE] and the native decoder fills in the language token
     * from the `<|sot|>` logits (the same single-step detection whisper.cpp
     * uses). A single language is pinned, so behaviour is unchanged for the
     * common one-language case.
     */
    private fun buildPrompt(languages: Array<String>, prompt: String): IntArray {
        val allowed = languages.ifEmpty { arrayOf("en") }
        val langToken = if (allowed.size == 1) {
            LANG_FIRST + (LANG_INDEX[allowed[0]] ?: 0)
        } else {
            AUTO_DETECT_LANGUAGE
        }
        val specials = intArrayOf(SOT, langToken, TRANSCRIBE, NOTIMESTAMPS)
        return specials + tokenizePrompt(prompt)
    }

    /**
     * Language-token ids the decoder may choose from during auto-detection.
     * Empty/unknown codes fall back to English, matching [buildPrompt].
     */
    private fun languageTokens(languages: Array<String>): IntArray {
        val ids = languages.mapNotNull { LANG_INDEX[it] }.distinct()
        return if (ids.isEmpty()) intArrayOf(LANG_FIRST) else IntArray(ids.size) { LANG_FIRST + ids[it] }
    }

    @Keep
    private fun invokePartialResult(text: String) {
        partialResultCallback(text.trim())
    }

    /**
     * Runs QNN inference over [samples], in 29 s chunks with a 5 s overlap for
     * anything longer than one window (see [LongFormChunking]); the overlapping
     * text is de-duplicated while the chunks are stitched together.
     *
     * [prompt] (the personal-dictionary glossary) is tokenized into the decoder
     * seed. The precompiled QNN graph is greedy and single-language per run, so
     * [bailLanguages] (cross-model language switching) and [decodingMode] (beam
     * search) are not honoured here — they only take effect on the ggml path —
     * and [suppressNonSpeechTokens] is fixed by the graph. Multiple allowed
     * languages are resolved by detecting the language from the decoder's first
     * step instead of pinning the first locale.
     */
    override suspend fun infer(
        samples: FloatArray,
        prompt: String,
        languages: Array<String>,
        bailLanguages: Array<String>,
        decodingMode: DecodingMode,
        suppressNonSpeechTokens: Boolean,
        partialResultCallback: (String) -> Unit
    ): String = withContext(inferenceContext) {
        if (handle == 0L) {
            throw IllegalStateException("QnnWhisperEngine has already been closed, cannot infer")
        }
        this@QnnWhisperEngine.partialResultCallback = partialResultCallback
        cancelled = false

        if (samples.isEmpty()) return@withContext ""

        val promptIds = buildPrompt(languages, prompt)
        val allowedLanguageTokens = languageTokens(languages)
        val chunks = LongFormChunking.planChunks(samples.size, CHUNK_SAMPLES, OVERLAP_SAMPLES)
        val maxOverlapWords = LongFormChunking.maxOverlapWords(OVERLAP_SAMPLES)

        // Reused across chunks to keep a multi-minute dictation from allocating
        // ~2 MB per 29 s window.
        val pcmBuffer = FloatArray(minOf(CHUNK_SAMPLES, samples.size))
        var transcript = ""

        for (chunk in chunks) {
            if (cancelled) throw InferenceCancelledException()

            val pcm = when {
                // Short recording: hand the recorded buffer straight through.
                chunk.start == 0 && chunk.length == samples.size -> samples
                chunk.length == pcmBuffer.size -> pcmBuffer
                else -> FloatArray(chunk.length)
            }
            if (pcm !== samples) {
                System.arraycopy(samples, chunk.start, pcm, 0, chunk.length)
            }

            val mel = nativeMel(pcm)
            if (mel == null) {
                if (cancelled) throw InferenceCancelledException()
                throw InvalidModelException()
            }

            val tokens = nativeTranscribe(handle, mel, promptIds, allowedLanguageTokens, FloatArray(2))
            if (tokens == null) {
                if (cancelled) throw InferenceCancelledException()
                throw InvalidModelException()
            }

            val text = detokenize(tokens).trim()
            if (text.isEmpty()) continue

            transcript = LongFormChunking.mergeOverlap(transcript, text, maxOverlapWords)

            // The HTP decoder is not streaming, so the only progress we can
            // report is one update per finished chunk. Skip it for short
            // recordings, where the final result arrives immediately after.
            if (chunks.size > 1) {
                invokePartialResult(transcript)
            }
        }

        if (cancelled) throw InferenceCancelledException()
        transcript.trim()
    }

    override fun cancel() {
        if (handle == 0L) return
        cancelled = true
        nativeCancel(handle)
    }

    override suspend fun close() = withContext(inferenceContext) {
        if (handle != 0L) {
            nativeFree(handle)
        }
        handle = 0L
    }

    override fun lastError(): String {
        if (handle == 0L) return ""
        return nativeLastError()
    }

    private external fun nativeInit(libDir: String, encoderPath: String, decoderPath: String, variant: Int): Long
    private external fun nativeFree(handle: Long)
    private external fun nativeCancel(handle: Long)
    private external fun nativeLastError(): String
    private external fun nativeMelInit(filtersPath: String, nMel: Int): Boolean
    private external fun nativeMel(pcm: FloatArray): ShortArray?
    private external fun nativeTranscribe(handle: Long, mel: ShortArray, promptIds: IntArray, languageTokens: IntArray, metrics: FloatArray): IntArray?
}
