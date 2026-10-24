package org.futo.voiceinput.shared.ggml

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import java.io.File

/**
 * Which hardware-acceleration backend to use for transcription.
 *
 * The backends are not freely interchangeable because they consume *different
 * model files*: [CPU] and [VULKAN] run whisper.cpp on a ggml `.bin` model, while
 * [QNN] runs a Qualcomm ONNX/context-binary graph (see `FileKind.VoiceInputQnn`
 * and `FileKind.VoiceInputQnnZip`).
 */
enum class Backend {
    CPU,
    VULKAN,
    QNN
}

/**
 * Why a backend cannot run for a language, either because of the device or
 * because of the models assigned to it. Returned by [BackendSupport.unavailableReason]
 * and turned into a warning by the settings UI.
 */
enum class BackendUnavailableReason {
    /** The engine is only built for arm64-v8a and this build is not. */
    UNSUPPORTED_ABI,

    /** This build did not package the engine's native library. */
    LIBRARY_MISSING,

    /** QNN: no Qualcomm SoC, so there is no Hexagon DSP to run on. */
    NOT_QUALCOMM,

    /** QNN: a Snapdragon, but one Qualcomm publishes no Whisper assets for. */
    UNSUPPORTED_CHIPSET,

    /** Vulkan: the driver does not advertise the features whisper.cpp needs. */
    VULKAN_UNAVAILABLE,

    /**
     * Vulkan: the driver advertises everything whisper.cpp asks for, but this
     * GPU family is known to abort inside it — see `GPU_VULKAN_FINDINGS.md`.
     */
    VULKAN_GPU_UNSUPPORTED,

    /** No model this backend can load is assigned to the language. */
    NO_MODEL
}

/**
 * Device-capability queries and backend routing for hardware acceleration.
 *
 * Every check in here is a *capability* check: it answers "could this backend
 * run on this device at all?". Callers additionally have to verify that a
 * compatible model is assigned — see [availableBackends] and [resolveBackend].
 *
 * Three independent pieces of evidence are combined:
 *  - the **model files** a backend needs, because they are not interchangeable;
 *  - the **hardware**, because a QNN model only runs on a Snapdragon whose
 *    Hexagon DSP Qualcomm actually shipped a Whisper asset for
 *    (see [QnnSupport]), and because the Vulkan engine, while it needs a driver
 *    that advertises the features whisper.cpp asks for, is known to abort
 *    *inside* the shaders on Adreno regardless ([isVulkanKnownBroken]); Mali is
 *    not blacklisted — its support is untested rather than known-broken; and
 *  - the presence of the **ABI-matched native library**, because both
 *    accelerator engines are built for arm64-v8a only. An x86_64 emulator that
 *    exposes Vulkan >= 1.2 must therefore *not* be routed to a library that was
 *    never packaged for it (that surfaced as an uncaught
 *    `UnsatisfiedLinkError`).
 *
 * [isDevelopmentBuild] (a debuggable build) relaxes the *offering* of backends so
 * every one stays reachable for testing. Nothing else about the routing changes:
 * see [availableBackends], [unavailableReason] and [resolveBackend].
 */
object BackendSupport {
    private const val ARM64_ABI = "arm64-v8a"

    /** True when the SoC is a Qualcomm Snapdragon (Adreno GPU). */
    fun isSnapdragon(): Boolean {
        // Preferred signal: Build.SOC_MANUFACTURER is "Qualcomm"/"QTI" on API 31+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val socManufacturer = Build.SOC_MANUFACTURER?.lowercase().orEmpty()
            if (socManufacturer.contains("qualcomm") || socManufacturer.contains("qti")) {
                return true
            }
        }

        // Fallback signals: ro.hardware / board platform contain a Qualcomm
        // code name (msm/sdm/sm8xxx) or "qcom".
        val hardware = Build.HARDWARE?.lowercase().orEmpty()
        if (hardware.contains("qcom") || hardware.contains("qualcomm")) {
            return true
        }
        val board = Build.BOARD?.lowercase().orEmpty()
        return listOf(
            "msm", "sdm", "sm8", "sm7", "sm6", "sm4", "sm2",
            "kalama", "pineapple", "lahaina", "waipio", "taro", "monaco",
            "holi", "sc8", "qcs"
        ).any { board.contains(it) }
    }

    /**
     * True when the GPU is an ARM Mali (Exynos / MediaTek).
     *
     * Deliberately *not* used by the Vulkan gate: Mali's ggml-vulkan support is
     * unvalidated, not known-broken (unlike Adreno), so it stays selectable. Kept
     * as a capability query for diagnostics/tests.
     */
    fun isMali(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val socManufacturer = Build.SOC_MANUFACTURER?.lowercase().orEmpty()
            if (socManufacturer.contains("mediatek") || socManufacturer.contains("samsung")) {
                return true
            }
        }
        val hardware = Build.HARDWARE?.lowercase().orEmpty()
        val board = Build.BOARD?.lowercase().orEmpty()
        val haystack = "$hardware $board"
        return listOf("exynos", "mt6", "mt8", "mt9", "s5e").any { haystack.contains(it) }
    }

    /** Both accelerator engines are only built for arm64-v8a. */
    fun isArm64(): Boolean = Build.SUPPORTED_ABIS.any { it == ARM64_ABI }

    /**
     * True for a development build of the app (`android:debuggable`) rather than
     * one that ships to users.
     *
     * Development builds offer every backend so unsupported configurations can be
     * reproduced on purpose ([availableBackends]) and so an explicit choice is
     * honoured instead of being degraded ([resolveBackend]). The warning shown
     * next to the picker ([unavailableReason]) is the only thing that says the
     * selection will not work.
     */
    fun isDevelopmentBuild(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * True when the Vulkan whisper.cpp engine *could* run here: an arm64 build
     * with `libvoiceinput_gpu.so` packaged, on a device that advertises every
     * Vulkan feature whisper.cpp's backend requires (see [GpuSupport.isAvailable]),
     * on a GPU family that is not known to abort inside it.
     *
     * The feature query alone is not sufficient: it is static, so it cannot tell
     * whether the *driver* is correct. A Galaxy S23 (Adreno 740) advertises all of
     * it and then aborts inside `ggml_vk_guess_split_k()` on the first matmul
     * dispatch (`GPU_VULKAN_FINDINGS.md`). Since that check happens at dispatch
     * rather than at device creation, no capability probe can predict it — hence
     * Adreno is treated as unsupported here rather than merely warned about.
     *
     * Mali is *not* excluded: ggml-vulkan carries no Mali blacklist and upstream
     * reports success on Valhall parts (the Galaxy A53's Exynos/Mali-G68 among
     * them), so it is offered as untested, to be settled by a device test. A
     * development build keeps Adreno reachable anyway for reproduction (see
     * [isDevelopmentBuild]).
     */
    fun isVulkanAvailable(context: Context): Boolean =
        isVulkanDeviceCapable(context) &&
                (!isVulkanKnownBroken() || isDevelopmentBuild(context))

    /**
     * The static part of [isVulkanAvailable]: features advertised by the driver,
     * ignoring whether the GPU family is known to be broken.
     */
    private fun isVulkanDeviceCapable(context: Context): Boolean =
        isArm64() && GpuSupport.isAvailable(context) && hasNativeLibrary(context, "voiceinput_gpu")

    /**
     * True when the Vulkan whisper.cpp path is known to abort on this GPU.
     *
     * Only Adreno/Snapdragon qualifies: Qualcomm's driver rejects ggml's compute
     * pipelines (`createComputePipeline: ErrorUnknown` — first the cooperative
     * matrix path, then `quantize_q8_1_x4`), an upstream-confirmed dead end. Mali
     * has no such record, so [isVulkanAvailable] refuses Adreno outside a
     * development build but *offers* Mali. See `GPU_VULKAN_FINDINGS.md`.
     */
    fun isVulkanKnownBroken(): Boolean = isSnapdragon()

    /**
     * True when the QNN (Hexagon HTP) engine can run here: a Snapdragon SoC from
     * [QnnSupport]'s allow-list, on an arm64 build with `libvoiceinput_qnn.so` and
     * the matching HTP skeleton packaged.
     */
    fun isQnnAvailable(context: Context): Boolean = qnnDeviceReason(context) == null

    /**
     * Why [backend] will not run for a language, or `null` when it will.
     *
     * The settings UI turns this into one warning line, so an unsupported backend
     * that a development build still offers ([availableBackends]) explains itself
     * instead of failing silently.
     */
    fun unavailableReason(
        context: Context,
        backend: Backend,
        hasGgmlModel: Boolean,
        hasQnnModel: Boolean
    ): BackendUnavailableReason? = when (backend) {
        Backend.CPU -> when {
            hasGgmlModel -> null
            else -> BackendUnavailableReason.NO_MODEL
        }

        Backend.VULKAN -> when {
            !hasGgmlModel -> BackendUnavailableReason.NO_MODEL
            !isArm64() -> BackendUnavailableReason.UNSUPPORTED_ABI
            !hasNativeLibrary(context, "voiceinput_gpu") -> BackendUnavailableReason.LIBRARY_MISSING
            !GpuSupport.isAvailable(context) -> BackendUnavailableReason.VULKAN_UNAVAILABLE
            isVulkanKnownBroken() -> BackendUnavailableReason.VULKAN_GPU_UNSUPPORTED
            else -> null
        }

        Backend.QNN -> when {
            !hasQnnModel -> BackendUnavailableReason.NO_MODEL
            else -> qnnDeviceReason(context)
        }
    }

    /**
     * The backends the user may pick, given which compatible models are assigned:
     * [CPU] plus — when the device supports it — [VULKAN] for a ggml model, and
     * [QNN] for a Qualcomm model. Empty when nothing can run (e.g. a QNN model on
     * a non-Snapdragon device); the UI uses this to decide which tabs to show.
     *
     * A development build always gets all three, supported or not, so an
     * unsupported configuration stays reproducible; the picker then shows
     * [unavailableReason] for whichever one is selected.
     */
    fun availableBackends(
        context: Context,
        hasGgmlModel: Boolean,
        hasQnnModel: Boolean
    ): List<Backend> {
        if (isDevelopmentBuild(context)) {
            return Backend.entries.toList()
        }

        return buildList {
            if (hasGgmlModel) {
                add(Backend.CPU)
                if (isVulkanAvailable(context)) add(Backend.VULKAN)
            }
            if (hasQnnModel && isQnnAvailable(context)) add(Backend.QNN)
        }
    }

    /** The fastest backend this device supports, ignoring which models are assigned. */
    fun preferredBackend(context: Context): Backend = when {
        isQnnAvailable(context) -> Backend.QNN
        isVulkanAvailable(context) -> Backend.VULKAN
        else -> Backend.CPU
    }
    /**
     * Resolves the backend to actually run with, given the request and which
     * models are assigned.
     *
     * The backend has to match the model: a Qualcomm model only runs on [QNN],
     * a ggml model only on [CPU] or [VULKAN]. So a QNN model selected on a
     * device that cannot run it is returned as-is, letting the loader raise a
     * clear "hardware acceleration unavailable" error instead of the app
     * silently running something else. A request for a backend that the device
     * cannot run, or that has no matching model, degrades to what can actually
     * run rather than failing.
     *
     * A development build is the exception: when the requested backend has a
     * model to load, the request is honoured verbatim even if the device cannot
     * support it, so the failure the user is looking for is the one they actually
     * get. A request with no matching model still falls through to the logic
     * below, because there would be nothing to run and the model-driven answer is
     * more useful.
     */
    fun resolveBackend(
        context: Context,
        requested: Backend,
        hasGgmlModel: Boolean,
        hasQnnModel: Boolean
    ): Backend {
        if (isDevelopmentBuild(context)) {
            val hasModelForRequest = when (requested) {
                Backend.QNN -> hasQnnModel
                Backend.CPU, Backend.VULKAN -> hasGgmlModel
            }
            if (hasModelForRequest) return requested
        }

        if (hasQnnModel && (requested == Backend.QNN || !hasGgmlModel)) {
            return Backend.QNN
        }

        if (hasGgmlModel) {
            return if (requested == Backend.VULKAN && isVulkanAvailable(context)) {
                Backend.VULKAN
            } else {
                Backend.CPU
            }
        }

        // No ggml model at all: honour an explicit QNN request so the loader can
        // report the real problem, otherwise there is nothing to run.
        return if (requested == Backend.QNN) Backend.QNN else Backend.CPU
    }

    /**
     * Default choice for a locale the user has never configured.
     *
     * Importing a Qualcomm model is a deliberate act (the model is shared across
     * all languages) and it has no CPU alternative, so an assigned QNN model is
     * preferred when the device supports it. Vulkan and CPU stay strictly opt-in
     * otherwise, which also keeps existing installs on the behaviour they had
     * before the picker.
     */
    fun defaultBackend(context: Context, hasGgmlModel: Boolean, hasQnnModel: Boolean): Backend {
        if (hasQnnModel && isQnnAvailable(context)) {
            return Backend.QNN
        }
        return resolveBackend(context, Backend.CPU, hasGgmlModel, hasQnnModel)
    }

    /**
     * Device-side check for QNN, independent of the assigned models. Ordered so
     * the reported reason is the most fundamental thing that is missing.
     */
    private fun qnnDeviceReason(context: Context): BackendUnavailableReason? {
        if (!isSnapdragon()) return BackendUnavailableReason.NOT_QUALCOMM
        if (!isArm64()) return BackendUnavailableReason.UNSUPPORTED_ABI

        val htpVersion = QnnSupport.matchedHtpVersion()
            ?: return BackendUnavailableReason.UNSUPPORTED_CHIPSET

        if (!hasNativeLibrary(context, "voiceinput_qnn")) {
            return BackendUnavailableReason.LIBRARY_MISSING
        }
        // The engine dlopens these, so a missing Hexagon skeleton for this
        // chipset's architecture would only surface as an opaque QNN error at load
        // time. Check for it up front instead. (`useLegacyPackaging` is on, so the
        // .so files really are extracted into nativeLibraryDir.)
        if (!hasNativeLibrary(context, "QnnHtpV${htpVersion}Skel")) {
            return BackendUnavailableReason.LIBRARY_MISSING
        }

        return null
    }

    private fun hasNativeLibrary(context: Context, name: String): Boolean =
        File(context.applicationInfo.nativeLibraryDir, "lib$name.so").exists()
}
