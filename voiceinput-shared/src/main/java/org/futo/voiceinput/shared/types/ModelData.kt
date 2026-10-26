package org.futo.voiceinput.shared.types

import android.content.Context
import androidx.annotation.StringRes
import org.futo.voiceinput.shared.ggml.Backend
import org.futo.voiceinput.shared.ggml.GpuUnavailableException
import org.futo.voiceinput.shared.ggml.QnnWhisperEngine
import org.futo.voiceinput.shared.ggml.WhisperEngine
import org.futo.voiceinput.shared.ggml.WhisperGGML
import org.futo.voiceinput.shared.ggml.WhisperGGMLGpu
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel


// Taken from https://github.com/tensorflow/tflite-support/blob/483c45d002cbed57d219fae1676a4d62b28fba73/tensorflow_lite_support/java/src/java/org/tensorflow/lite/support/common/FileUtil.java#L158
/**
 * Loads a file from the asset folder through memory mapping.
 *
 * @param context Application context to access assets.
 * @param filePath Asset path of the file.
 * @return the loaded memory mapped file.
 * @throws IOException if an I/O error occurs when loading the file model.
 */
@Throws(IOException::class)
private fun loadMappedFile(context: Context, filePath: String): MappedByteBuffer {
    context.assets.openFd(filePath).use { fileDescriptor ->
        FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        }
    }
}

/**
 * Tries the GPU engine first, but if the device's Vulkan backend cannot
 * actually initialise (the capability gate passed but init still threw — e.g.
 * no usable device or an unsupported driver), falls back to the CPU engine
 * instead of crashing or misreporting a corrupt model. Out-of-memory and
 * corrupt-model failures are NOT swallowed here; they propagate as typed
 * errors so the UI can show the right message.
 */
private fun loadGpuOrFallback(buffer: MappedByteBuffer): WhisperEngine {
    return try {
        WhisperGGMLGpu(buffer)
    } catch (e: GpuUnavailableException) {
        WhisperGGML(buffer)
    } catch (e: LinkageError) {
        // The ABI gate ([BackendSupport.isArm64]) should have prevented this
        // (libvoiceinput_gpu.so is arm64-v8a only), but if System.loadLibrary
        // still fails we degrade to the CPU engine rather than crash.
        WhisperGGML(buffer)
    }
}

/**
 * Builds a QNN engine, turning a missing/unloadable native library into a typed
 * [GpuUnavailableException]. `UnsatisfiedLinkError` is a [LinkageError], which
 * callers that only handle the three typed model-load failures would otherwise
 * let escape and crash the app.
 */
private fun loadQnn(block: () -> WhisperEngine): WhisperEngine {
    return try {
        block()
    } catch (e: LinkageError) {
        throw GpuUnavailableException("QNN native library unavailable: ${e.message}")
    }
}

/**
 * Loads a ggml `.bin` model for the resolved backend.
 *
 * ggml models can only run on the CPU or Vulkan paths of whisper.cpp. If the
 * resolved backend is [Backend.QNN] (Snapdragon), a ggml model cannot use the
 * QNN engine — QNN requires a Qualcomm context binary instead — so it degrades
 * to the CPU path. Vulkan is deliberately not offered on Adreno, whose driver
 * rejects ggml's compute shaders; Mali is untested rather than known-broken.
 */
private fun loadGgmlForBackend(buffer: MappedByteBuffer, backend: Backend): WhisperEngine {
    return when (backend) {
        Backend.VULKAN -> loadGpuOrFallback(buffer)
        Backend.CPU, Backend.QNN -> WhisperGGML(buffer)
    }
}

// Maybe add `val languages: Set<Language>`
interface ModelLoader {
    @get:StringRes
    val name: Int

    /**
     * Backend this model *must* run on, or `null` when it can run on any backend
     * that consumes ggml models ([Backend.CPU] / [Backend.VULKAN]). Qualcomm
     * models override this with [Backend.QNN], which lets the app route them
     * correctly without having to special-case each loader type.
     */
    val requiredBackend: Backend? get() = null

    fun exists(context: Context): Boolean
    fun getRequiredDownloadList(context: Context): List<String>

    fun loadGGML(context: Context, backend: Backend): WhisperEngine

    fun key(context: Context): Any
}

internal class ModelBuiltInAsset(
    override val name: Int,
    val ggmlFile: String
) : ModelLoader {
    override fun exists(context: Context): Boolean {
        return true
    }

    override fun getRequiredDownloadList(context: Context): List<String> {
        return listOf()
    }

    override fun loadGGML(context: Context, backend: Backend): WhisperEngine {
        val file = loadMappedFile(context, ggmlFile)
        return loadGgmlForBackend(file, backend)
    }

    override fun key(context: Context): Any {
        return "BuiltIn$ggmlFile"
    }
}

@Throws(IOException::class)
private fun tryOpenDownloadedModel(file: File): MappedByteBuffer {
    val fis = file.inputStream()
    val channel = fis.channel

    return channel.map(
        FileChannel.MapMode.READ_ONLY,
        0, channel.size()
    ).load()
}

@Throws(IOException::class)
private fun Context.tryOpenDownloadedModel(pathStr: String): MappedByteBuffer {
    return tryOpenDownloadedModel(File(filesDir, pathStr))
}

internal class ModelDownloadable(
    override val name: Int,
    val ggmlFile: String,
    val checksum: String
) : ModelLoader {
    override fun exists(context: Context): Boolean {
        return getRequiredDownloadList(context).isEmpty()
    }

    override fun getRequiredDownloadList(context: Context): List<String> {
        return listOf(ggmlFile).filter {
            !File(context.filesDir, it).exists()
        }
    }

    override fun loadGGML(context: Context, backend: Backend): WhisperEngine {
        val file = context.tryOpenDownloadedModel(ggmlFile)
        return loadGgmlForBackend(file, backend)
    }

    override fun key(context: Context): Any {
        return "Downloadable$ggmlFile$checksum"
    }
}

public class ModelFileFile(
    override val name: Int,
    val file: File,
) : ModelLoader {
    override fun exists(context: Context): Boolean {
        return file.exists()
    }

    override fun getRequiredDownloadList(context: Context): List<String> {
        return listOf()
    }

    override fun loadGGML(context: Context, backend: Backend): WhisperEngine {
        val file = tryOpenDownloadedModel(file)
        return loadGgmlForBackend(file, backend)
    }

    override fun key(context: Context): Any {
        return "File${file.absolutePath}"
    }
}

/**
 * Loader for a standalone Qualcomm QNN context binary (a `.qnn.bin` produced by
 * `qnn-context-binary-generator`).
 *
 * The QNN engine runs Whisper's encoder and decoder as two separate ONNX
 * Runtime sessions, so a single context binary cannot drive it: there is no way
 * to split one `.qnn.bin` back into the two graphs. This loader therefore fails
 * with a clear message pointing at the `precompiled_qnn_onnx` archive that
 * [ModelQnnZip] handles. It is kept as a distinct type because the importer
 * still recognizes the file kind, but it is never expected to load.
 */
public class ModelQnnFileFile(
    override val name: Int,
    val file: File,
) : ModelLoader {
    override val requiredBackend: Backend? get() = Backend.QNN

    override fun exists(context: Context): Boolean {
        return file.exists()
    }

    override fun getRequiredDownloadList(context: Context): List<String> {
        return listOf()
    }

    override fun loadGGML(context: Context, backend: Backend): WhisperEngine {
        if (backend != requiredBackend) {
            throw GpuUnavailableException(
                "QNN context binary requires a Snapdragon device (resolved ${backend.name})"
            )
        }

        throw GpuUnavailableException(
            "A single QNN context binary cannot be split into the encoder/decoder " +
                "ONNX graphs this engine runs; import the precompiled_qnn_onnx ZIP instead"
        )
    }

    override fun key(context: Context): Any {
        return "QnnFile${file.absolutePath}"
    }
}

/**
 * Loader for a Qualcomm QNN context-binary pair imported from Qualcomm AI Hub's
 * `precompiled_qnn_onnx` archive. The importer extracts the archive to a
 * directory and records the two ONNX graph files (`encoder.onnx` +
 * `decoder.onnx`); ONNX Runtime's QNN EP resolves each graph's sibling
 * `*_qairt_context.bin` context binary by the relative path embedded in the
 * graph. Same runtime restrictions as [ModelQnnFileFile].
 */
public class ModelQnnZip(
    override val name: Int,
    val encoderOnnxFile: File,
    val decoderOnnxFile: File,
) : ModelLoader {
    override val requiredBackend: Backend? get() = Backend.QNN

    override fun exists(context: Context): Boolean {
        return encoderOnnxFile.exists() && decoderOnnxFile.exists()
    }

    override fun getRequiredDownloadList(context: Context): List<String> {
        return listOf()
    }

    override fun loadGGML(context: Context, backend: Backend): WhisperEngine {
        if (backend != requiredBackend) {
            throw GpuUnavailableException(
                "QNN context binaries require a Snapdragon device (resolved ${backend.name})"
            )
        }

        return loadQnn { QnnWhisperEngine(context, encoderOnnxFile, decoderOnnxFile) }
    }

    override fun key(context: Context): Any {
        return "QnnZip${encoderOnnxFile.absolutePath}:${decoderOnnxFile.absolutePath}"
    }
}