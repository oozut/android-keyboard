package org.futo.voiceinput.shared.whisper

import android.content.Context
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

    // ConcurrentHashMap + computeIfAbsent so parallel preloads on
    // Dispatchers.Default (MultiModelRunner.preload) share one load per key
    // instead of racing on an unsynchronized HashMap.
    private val loadedModels =
        ConcurrentHashMap<ModelCacheKey, WhisperEngine>()

    fun obtainModel(model: ModelLoader, backend: Backend): WhisperEngine {
        val key = ModelCacheKey(model.key(context), backend)
        return loadedModels.computeIfAbsent(key) { model.loadGGML(context, backend) }
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
