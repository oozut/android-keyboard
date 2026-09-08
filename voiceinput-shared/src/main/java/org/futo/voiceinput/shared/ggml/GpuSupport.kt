package org.futo.voiceinput.shared.ggml

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Device capability gate for the Vulkan GPU engine.
 *
 * The GPU library links against Vulkan 1.1 core functions
 * (vkGetPhysicalDeviceFeatures2 etc.) which only exist in Android's
 * libvulkan.so from API level 28 (Android 9), and ggml-vulkan additionally
 * requires a Vulkan 1.2 device (API 29). Loading the GPU library on an older
 * device would fail, so the GPU path is only ever offered/used when this
 * check passes; otherwise transcription falls back to the CPU engine.
 *
 * Kept in its own object (not a companion of WhisperGGMLGpu) so that calling
 * [isAvailable] does not trigger [WhisperGGMLGpu]'s class initializer, which
 * loads libvoiceinput_gpu.so via System.loadLibrary.
 */
object GpuSupport {
    fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // Vulkan 1.1 loader symbols unavailable below API 28.
            return false
        }

        // ggml-vulkan hard-requires Vulkan 1.2 (it throws during backend init
        // otherwise). hasSystemFeature(name, version) compares the device's
        // packed Vulkan API version against the requested one, so this returns
        // true only when the device truly exposes >= 1.2.
        return context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_VULKAN_HARDWARE_VERSION,
            0x402000 // VK_API_VERSION_1_2
        )
    }
}
