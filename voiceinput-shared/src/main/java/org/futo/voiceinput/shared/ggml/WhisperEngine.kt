package org.futo.voiceinput.shared.ggml

/**
 * Common surface shared by the CPU engine ([WhisperGGML]) and the Vulkan GPU
 * engine ([WhisperGGMLGpu]) so the recognizer chain (ModelManager /
 * MultiModelRunner) can treat both backends interchangeably.
 */
interface WhisperEngine {
    @Throws(BailLanguageException::class, InferenceCancelledException::class)
    suspend fun infer(
        samples: FloatArray,
        prompt: String,
        languages: Array<String>,
        bailLanguages: Array<String>,
        decodingMode: DecodingMode,
        suppressNonSpeechTokens: Boolean,
        partialResultCallback: (String) -> Unit
    ): String

    fun cancel()

    suspend fun close()

    /**
     * The last backend-captured error text from a failed model load / device
     * init. Empty for the CPU engine. Lets the caller classify a failure
     * (e.g. out-of-device-memory vs a corrupt model) instead of collapsing
     * everything into one generic message.
     */
    fun lastError(): String
}
