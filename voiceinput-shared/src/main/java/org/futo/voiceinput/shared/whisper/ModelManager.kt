package org.futo.voiceinput.shared.whisper

import android.content.Context
import android.util.Log
import org.futo.voiceinput.shared.ggml.Backend
import org.futo.voiceinput.shared.ggml.WhisperEngine
import org.futo.voiceinput.shared.types.ModelLoader
import java.util.concurrent.ConcurrentHashMap


class ModelManager(
    val context: Context
) {
    private data class ModelCacheKey(
        val modelKey: Any,
        val backend: Backend
    )

    companion object {
        /**
         * QNN ZIP models are per-locale copies of the same multilingual Whisper
         * graph; language is selected with a prompt token, not by loading
         * different weights. More importantly, each loaded HTP context takes
         * hundreds of MB of DSP memory, and opening a second one while the
         * first is still cached fails (QNN error 1002) and wedges the HTP
         * backend until the process restarts. Cache one QNN engine and reuse it
         * for every locale in the voice-input session.
         */
        private const val QNN_SHARED_MODEL_KEY = "QnnMultilingualWhisper"
    }

    // ConcurrentHashMap + computeIfAbsent so parallel preloads on
    // Dispatchers.Default (MultiModelRunner.preload) share one load per key
    // instead of racing on an unsynchronized HashMap.
    private val loadedModels =
        ConcurrentHashMap<ModelCacheKey, WhisperEngine>()

    fun obtainModel(model: ModelLoader, backend: Backend): WhisperEngine {
        val modelKey = model.key(context)
        val key = ModelCacheKey(
            if (backend == Backend.QNN) QNN_SHARED_MODEL_KEY else modelKey,
            backend
        )
        val cached = loadedModels.containsKey(key)
        Log.i("VoiceInputSelect", "obtainModel backend=$backend cached=$cached key=$modelKey")
        return loadedModels.computeIfAbsent(key) {
            try {
                model.loadGGML(context, backend).also {
                    Log.i("VoiceInputSelect", "loadedModel backend=$backend key=$modelKey")
                }
            } catch (e: Throwable) {
                Log.e("VoiceInputSelect", "loadModel FAILED backend=$backend key=$modelKey", e)
                throw e
            }
        }
    }

    fun cancelAll() {
        loadedModels.values.forEach {
            it.cancel()
        }
    }

    suspend fun cleanUp() {
        // Snapshot + clear before closing: a model inserted while we close the
        // snapshot is not closed twice, and the map stays safe to read.
        val models = loadedModels.values.toList()
        loadedModels.clear()

        for (model in models) {
            model.cancel()
            model.close()
        }
    }
}
