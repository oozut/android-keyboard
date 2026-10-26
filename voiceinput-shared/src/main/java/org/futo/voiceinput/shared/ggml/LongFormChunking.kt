package org.futo.voiceinput.shared.ggml

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Overlap-and-merge helpers for engines whose native graph only accepts one
 * fixed-length window, so audio longer than that window has to be decoded in
 * pieces and stitched back together.
 *
 * The QNN/HTP engine is the case this exists for: its mel front end truncates
 * to 30 s (`native/jni/src/qnn-htp/whisper_mel.cpp`, `SAMPLES_30S`) and the
 * encoder input tensor is a fixed `{1, melBins, 3000}`. Decoding therefore runs
 * over [planChunks] and the transcripts are joined by [mergeOverlap], which
 * removes the duplicated words produced by the overlap instead of repeating
 * them in the user's text.
 *
 * The pure-Kotlin implementations live here (rather than in the JNI bridge) so
 * the behaviour is identical for every backend and can be reasoned about
 * without a device.
 */
object LongFormChunking {
    const val SAMPLE_RATE: Int = 16_000

    /**
     * Samples per chunk: 29 s at 16 kHz. Deliberately shorter than the 30 s
     * window the HTP graph can take, so no chunk is ever silently truncated,
     * even if the mel length or sample rate shifts slightly.
     */
    const val DEFAULT_CHUNK_SAMPLES: Int = 29 * SAMPLE_RATE

    /**
     * Samples shared by two neighbouring chunks: 5 s at 16 kHz. Wide enough
     * that a word cut in half at a chunk boundary is still transcribed
     * completely by at least one of the two chunks.
     */
    const val DEFAULT_OVERLAP_SAMPLES: Int = 5 * SAMPLE_RATE

    /**
     * Upper bound on how many words the de-duplication may drop. Used to derive
     * [maxOverlapWords] from the overlap duration; ~4 words/s is already above
     * normal dictation pace, and the bound only exists to stop a pathological
     * self-repeating transcript from eating legitimate text.
     */
    private const val WORDS_PER_SECOND: Double = 4.0

    /** Half-open sample range `[start, end)` of the input audio. */
    data class Chunk(val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    /**
     * Splits [totalSamples] of audio into chunks of at most [chunkSamples] that
     * overlap by [overlapSamples].
     *
     * The tail chunk is kept at full length whenever possible (a 30 s encoder
     * run costs the same for 3 s and 29 s of audio), but its start never moves
     * backwards past the last hop, so the output is strictly ordered and every
     * chunk after the first begins where the previous one's overlap starts.
     *
     * Audio shorter than one chunk yields a single chunk covering everything.
     */
    fun planChunks(
        totalSamples: Int,
        chunkSamples: Int = DEFAULT_CHUNK_SAMPLES,
        overlapSamples: Int = DEFAULT_OVERLAP_SAMPLES
    ): List<Chunk> {
        require(chunkSamples > 0) { "chunkSamples must be positive" }
        require(overlapSamples >= 0 && overlapSamples < chunkSamples) {
            "overlapSamples must be in [0, chunkSamples)"
        }
        if (totalSamples <= 0) return emptyList()
        if (totalSamples <= chunkSamples) return listOf(Chunk(0, totalSamples))

        val hop = chunkSamples - overlapSamples
        val chunks = ArrayList<Chunk>()
        var start = 0
        while (start + chunkSamples < totalSamples) {
            chunks.add(Chunk(start, start + chunkSamples))
            start += hop
        }

        // Final window: prefer a full-length one that ends at the end of the
        // audio, but never start before the hop we have already reached.
        val tailStart = max(start, totalSamples - chunkSamples)
        chunks.add(Chunk(tailStart, totalSamples))
        return chunks
    }

    /**
     * How many words at most may be treated as "the same words heard twice"
     * when stitching [overlapSamples] of shared audio.
     */
    fun maxOverlapWords(
        overlapSamples: Int = DEFAULT_OVERLAP_SAMPLES,
        sampleRate: Int = SAMPLE_RATE
    ): Int {
        if (overlapSamples <= 0 || sampleRate <= 0) return 0
        val seconds = overlapSamples.toDouble() / sampleRate.toDouble()
        return max(0, (seconds * WORDS_PER_SECOND).toInt())
    }

    /**
     * Appends [next] to [accumulated], dropping the words that both transcripts
     * cover because they were decoded from overlapping audio.
     *
     * The overlap is located by finding the longest run of words where the end
     * of [accumulated] equals the start of [next] (case-insensitive, ignoring
     * surrounding punctuation; CJK runs are compared character by character).
     * Matching is limited to [maxOverlapWords] words so a repeated phrase in the
     * middle of dictation is not collapsed.
     *
     * If no overlap is found the texts are concatenated as-is: a duplicated
     * word is easier to fix by hand than words silently removed from the middle
     * of a sentence. The one exception is a word the window boundary cut in
     * half ("inform" | "information"): the fragment breaks the alignment of
     * every word behind it, so it is dropped and the search retried, which
     * leaves the complete word from the next chunk in place.
     */
    fun mergeOverlap(accumulated: String, next: String, maxOverlapWords: Int): String {
        var left = accumulated.trim()
        val right = next.trim()
        if (right.isEmpty()) return left
        if (left.isEmpty()) return right
        if (maxOverlapWords <= 0) return concatenate(left, right)

        var leftWords = splitWords(left)
        val rightWords = splitWords(right)
        if (leftWords.isEmpty() || rightWords.isEmpty()) return concatenate(left, right)

        val limit = min(maxOverlapWords, min(leftWords.size, rightWords.size))
        var matched = longestOverlap(leftWords, rightWords, limit)

        if (matched == 0 && isFragment(leftWords.last(), rightWords, limit)) {
            val repairedLeft = dropLastWord(left)
            val repairedWords = splitWords(repairedLeft)
            val repairedMatch = longestOverlap(repairedWords, rightWords, limit)
            // Only trust the repair when it actually explains something: either
            // the overlap appears once the fragment is gone, or the fragment
            // was the whole transcript.
            if (repairedMatch > 0 || repairedWords.isEmpty()) {
                left = repairedLeft
                leftWords = repairedWords
                matched = repairedMatch
            }
        }

        if (leftWords.isNotEmpty() && matched >= rightWords.size) return left

        // Re-joined with [concatenate] rather than a plain " ", so scripts that
        // whisper writes without spaces (CJK) stay space-free.
        val remainder = rightWords.subList(matched, rightWords.size)
            .fold("") { acc, word -> concatenate(acc, word) }
        return concatenate(left, remainder)
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * Splits [text] into whitespace-delimited words, further splitting CJK runs
     * into single characters so the overlap search works on scripts that are
     * written without spaces.
     */
    private fun splitWords(text: String): List<String> {
        val words = ArrayList<String>()
        for (token in text.split(WHITESPACE)) {
            if (token.isEmpty()) continue
            val latin = StringBuilder()
            for (c in token) {
                if (isCjk(c)) {
                    if (latin.isNotEmpty()) {
                        words.add(latin.toString())
                        latin.setLength(0)
                    }
                    words.add(c.toString())
                } else {
                    latin.append(c)
                }
            }
            if (latin.isNotEmpty()) words.add(latin.toString())
        }
        return words
    }

    /**
     * Length of the longest run of words where the end of [a] equals the start
     * of [b], capped at [limit]. Zero when the transcripts share no words, which
     * is the signal that the overlap has to be re-aligned or given up on.
     */
    private fun longestOverlap(a: List<String>, b: List<String>, limit: Int): Int {
        val max = min(limit, min(a.size, b.size))
        for (k in max downTo 1) {
            if (wordsMatch(a.subList(a.size - k, a.size), b.subList(0, k))) return k
        }
        return 0
    }

    /**
     * Compares two equal-length word runs case-insensitively, ignoring leading
     * and trailing punctuation (whisper hyphenates and punctuates the same
     * phrase differently depending on which side of a window it lands on).
     * A word that normalizes to nothing never matches, so punctuation-only
     * tokens cannot fake an overlap.
     */
    private fun wordsMatch(a: List<String>, b: List<String>): Boolean {
        for (i in a.indices) {
            val na = normalizeWord(a[i])
            val nb = normalizeWord(b[i])
            if (na.isEmpty() || nb.isEmpty() || na != nb) return false
        }
        return true
    }

    private fun dropLastWord(text: String): String {
        val lastSpace = text.indexOfLast { it.isWhitespace() }
        return if (lastSpace < 0) "" else text.substring(0, lastSpace).trimEnd()
    }

    /**
     * True when [word] looks like the beginning of one of [nextWords], i.e. the
     * chunk boundary cut a word in half ("inform" | "information"). A four
     * character stem is required so ordinary short words ("the" | "there") are
     * kept, and the fragment must not end in punctuation, which a cut word never
     * does.
     */
    private fun isFragment(word: String, nextWords: List<String>, limit: Int): Boolean {
        if (word.isEmpty() || !word.last().isLetterOrDigit()) return false
        val stem = normalizeWord(word)
        if (stem.length < 4) return false
        for (candidate in nextWords.subList(0, min(limit, nextWords.size))) {
            val full = normalizeWord(candidate)
            if (full.length > stem.length && full.startsWith(stem)) return true
        }
        return false
    }

    private fun normalizeWord(word: String): String {
        val lower = word.lowercase(Locale.ROOT)
        var start = 0
        var end = lower.length
        while (start < end && !lower[start].isLetterOrDigit()) start++
        while (end > start && !lower[end - 1].isLetterOrDigit()) end--
        return if (start == 0 && end == lower.length) lower else lower.substring(start, end)
    }

    /**
     * Joins two pieces of transcript with a single space, except between two
     * CJK characters where whisper itself emits no spaces.
     */
    private fun concatenate(left: String, right: String): String {
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        val needsSpace = !(isCjk(left.last()) && isCjk(right.first()))
        return if (needsSpace) "$left $right" else left + right
    }

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return (code in 0x2E80..0x9FFF) ||   // CJK radicals, kana, unified ideographs
                (code in 0xAC00..0xD7AF) ||  // Hangul syllables
                (code in 0xF900..0xFAFF) ||  // CJK compatibility ideographs
                (code in 0xFF00..0xFFEF)     // full-width forms
    }
}
