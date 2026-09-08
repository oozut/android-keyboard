package org.futo.voiceinput.shared.ggml

import android.content.Context
import android.os.Build

/**
 * Which hardware-acceleration backend to use for transcription.
 *
 * The backends are mutually exclusive because they consume *different model
 * files*: [CPU] and [VULKAN] run whisper.cpp on a ggml `.bin` model, while
 * [QNN] runs a Qualcomm context binary (see `FileKind.VoiceInputQnn`).
 */
enum class Backend {
    CPU,
    VULKAN,
    QNN
}

/**
 * Device-type validation for hardware-acceleration routing.
 *
 * The GPU (Vulkan) path in whisper.cpp is known-broken on Adreno (Snapdragon)
 * and Mali GPUs — see `GPU_VULKAN_FINDINGS.md`. Qualcomm devices are instead
 * routed to QNN; other devices use Vulkan only when the device exposes
 * Vulkan >= 1.2, and everything else falls back to CPU.
 */
object BackendSupport {

    /** True when the SoC is a Qualcomm Snapdragon (Adreno GPU). */
    fun isSnapdragon(): Boolean {
        // Preferred signal: Build.SOC_MANUFACTURER is "Qualcomm"/"QTI" on API 31+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val socManufacturer = Build.SOC_MANUFACTURER.lowercase()
            if (socManufacturer.contains("qualcomm") || socManufacturer.contains("qti")) {
                return true
            }
        }

        // Fallback signals: ro.hardware / board platform contain a Qualcomm
        // code name (msm/sdm/sm8xxx) or "qcom".
        val hardware = Build.HARDWARE.lowercase()
        if (hardware.contains("qcom") || hardware.contains("qualcomm")) {
            return true
        }
        val board = Build.BOARD.lowercase()
        return listOf(
            "msm", "sdm", "sm8", "sm7", "sm6", "sm4", "sm2",
            "kalama", "pineapple", "lahaina", "waipio", "taro", "monaco",
            "holi", "sc8", "qcs"
        ).any { board.contains(it) }
    }

    /** True when the GPU is an ARM Mali (Exynos / MediaTek) — Vulkan is broken there too. */
    fun isMali(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val socManufacturer = Build.SOC_MANUFACTURER.lowercase()
            if (socManufacturer.contains("mediatek") || socManufacturer.contains("samsung")) {
                return true
            }
        }
        val hardware = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        val haystack = "$hardware $board"
        return listOf("exynos", "mt6", "mt8", "mt9", "s5e").any { haystack.contains(it) }
    }

    /**
     * Resolves which backend to use, honouring the user's hardware-acceleration
     * preference and the device type.
     *
     *  - preference off → [Backend.CPU]
     *  - Snapdragon → [Backend.QNN] (QNN context-binary models; the Vulkan
     *    whisper.cpp path crashes on Adreno)
     *  - Mali → [Backend.CPU] (Vulkan broken)
     *  - other devices with Vulkan >= 1.2 → [Backend.VULKAN]
     *  - otherwise → [Backend.CPU]
     */
    fun resolveBackend(context: Context, preferHardware: Boolean): Backend {
        if (!preferHardware) {
            return Backend.CPU
        }
        if (isSnapdragon()) {
            return Backend.QNN
        }
        if (isMali()) {
            return Backend.CPU
        }
        return if (GpuSupport.isAvailable(context)) Backend.VULKAN else Backend.CPU
    }
}
