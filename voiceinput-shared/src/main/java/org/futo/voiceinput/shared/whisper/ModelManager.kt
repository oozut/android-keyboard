package org.futo.voiceinput.shared.whisper

import android.content.Context
import org.futo.voiceinput.shared.ggml.Backend
import org.futo.voiceinput.shared.ggml.WhisperEngine
import org.futo.voiceinput.shared.types.ModelLoader


class ModelManager(
    val context: Context
) {
    private data class ModelCacheKey(
        val modelKey: Any,
        val backend: Backend
    )

    private val loadedModels: HashMap<ModelCacheKey, WhisperEngine> = hashMapOf()

    fun obtainModel(model: ModelLoader, backend: Backend): WhisperEngine {
        val key = ModelCacheKey(model.key(context), backend)
        if (!loadedModels.contains(key)) {
            loadedModels[key] = model.loadGGML(context, backend)
        }

        return loadedModels[key]!!
    }

    fun cancelAll() {
        loadedModels.forEach {
            it.value.cancel()
        }
    }

    suspend fun cleanUp() {
        for (model in loadedModels.entries) {
            model.value.cancel()
            model.value.close()
        }

        loadedModels.clear()
    }
}
