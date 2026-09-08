package org.futo.voiceinput.shared.ggml

import androidx.annotation.Keep
import kotlinx.coroutines.withContext
import java.nio.Buffer

/**
 * Classifies the native init-time error log captured by the GPU bridge into a
 * typed exception: out-of-memory gets a distinct [ModelOutOfMemoryException]
 * (so the UI can suggest a smaller model / CPU), everything else falls back to
 * the generic [InvalidModelException] (corrupt/incomplete model).
 */
private fun classifyLoadFailure(error: String): Exception {
    val normalized = error.lowercase()
    val outOfMemoryMarkers = listOf(
        "outofdevicememory",     // VK_ERROR_OUT_OF_DEVICE_MEMORY ("ErrorOutOfDeviceMemory")
        "out of device memory",
        "outofmemory",
        "out of memory",
        "out of host memory",
        "failed to allocate",
        "failed to create ggml context",
        "failed to find a compatible buffer type",
        "device memory allocation",
        "failed to init conv allocator",
        "failed to init encoder allocator",
        "failed to init cross allocator",
        "failed to init decoder allocator"
    )
    // A device can pass the Kotlin capability gate (Vulkan >= 1.2) and still
    // fail to initialise the backend at runtime (no usable device, unsupported
    // driver, etc.). In that case we fall back to the CPU engine rather than
    // crashing or misreporting a corrupt model.
    val gpuUnavailableMarkers = listOf(
        "vulkan 1.2 required",
        "featurenotpresent",         // VK_ERROR_FEATURE_NOT_PRESENT
        "device not found",
        "no devices found",
        "unsupported device",
        "errorinitializationfailed", // VK_ERROR_INITIALIZATION_FAILED
        "failed to create vulkan",
        "failed to create instance",
        "failed to create device"
    )

    return when {
        outOfMemoryMarkers.any { normalized.contains(it) } -> ModelOutOfMemoryException(error)
        gpuUnavailableMarkers.any { normalized.contains(it) } -> GpuUnavailableException(error)
        else -> InvalidModelException()
    }
}

/**
 * Vulkan GPU engine wrapper.
 *
 * Mirrors the JNI surface of [WhisperGGML] (CPU) but registers under a
 * different native class and lives in its own shared library
 * (libvoiceinput_gpu.so), so the CPU path is never disturbed. The native side
 * hardcodes use_gpu = true and lets ggml pick the Vulkan backend.
 */
@Keep
class WhisperGGMLGpu(
    modelBuffer: Buffer
) : WhisperEngine {
    private var handle: Long = 0L
    init {
        handle = openFromBufferNative(modelBuffer)

        if (handle == 0L) {
            throw InvalidModelException()
        }

        // The native side keeps the state alive on a failed load so we can
        // read back the captured error and classify it (OOM vs corrupt) rather
        // than collapsing everything into a single generic failure.
        if (!loadedNative(handle)) {
            val error = lastErrorNative(handle)
            closeNative(handle)
            handle = 0L
            throw classifyLoadFailure(error)
        }
    }

    private var partialResultCallback: (String) -> Unit = { }

    @Keep
    private fun invokePartialResult(text: String) {
        partialResultCallback(text.trim())
    }

    // empty languages = autodetect any language
    // 1 language = will force that language
    // 2 or more languages = autodetect between those languages
    @Throws(BailLanguageException::class, InferenceCancelledException::class)
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
            throw IllegalStateException("WhisperGGMLGpu has already been closed, cannot infer")
        }
        this@WhisperGGMLGpu.partialResultCallback = partialResultCallback

        val result = inferNative(
            handle,
            samples,
            prompt,
            languages,
            bailLanguages,
            decodingMode.value,
            suppressNonSpeechTokens
        ).trim()

        if (result.contains("<>CANCELLED<>")) {
            if (result.contains("flag")) {
                throw InferenceCancelledException()
            } else if (result.contains("lang=")) {
                val language = result.split("lang=")[1]
                throw BailLanguageException(language)
            } else {
                throw IllegalStateException("Cancelled for unknown reason")
            }
        } else {
            return@withContext result
        }
    }

    override fun cancel() {
        if (handle == 0L) return
        cancelNative(handle)
    }

    override suspend fun close() = withContext(inferenceContext) {
        if (handle != 0L) {
            closeNative(handle)
        }
        handle = 0L
    }

    override fun lastError(): String {
        if (handle == 0L) return ""
        return lastErrorNative(handle)
    }

    private external fun openNative(path: String): Long
    private external fun openFromBufferNative(buffer: Buffer): Long
    private external fun loadedNative(handle: Long): Boolean
    private external fun inferNative(handle: Long, samples: FloatArray, prompt: String, languages: Array<String>, bailLanguages: Array<String>, decodingMode: Int, suppressNonSpeechTokens: Boolean): String
    private external fun cancelNative(handle: Long)
    private external fun closeNative(handle: Long)
    private external fun lastErrorNative(handle: Long): String

    companion object {
        init {
            System.loadLibrary("voiceinput_gpu")
        }
    }
}
