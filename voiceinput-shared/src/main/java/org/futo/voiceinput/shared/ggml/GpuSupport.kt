package org.futo.voiceinput.shared.ggml

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Device capability gate for the Vulkan GPU engine.
 *
 * This mirrors, feature for feature, the checks ggml-vulkan performs before it
 * will initialise a device, because those are the only Vulkan requirements
 * whisper.cpp's GPU path has. From `ggml/src/ggml-vulkan/ggml-vulkan.cpp`
 * (whisper.cpp v1.9.3, vendored under `native/jni/src/whisper-gpu/`):
 *
 *  - `ggml_vk_instance_init()` requires `vk::enumerateInstanceVersion() >=
 *    VK_API_VERSION_1_2`, otherwise it throws
 *    `vk::SystemError(eErrorFeatureNotPresent, "Vulkan 1.2 required")`.
 *    Android exposes the instance version as
 *    [PackageManager.FEATURE_VULKAN_HARDWARE_VERSION].
 *
 *  - `ggml_vk_create_device()` requires the Vulkan 1.1 feature
 *    `VkPhysicalDeviceVulkan11Features::storageBuffer16BitAccess` (plus the
 *    `VK_KHR_16bit_storage` extension), otherwise it throws
 *    `std::runtime_error("Unsupported device")`. That feature is what Android's
 *    Vulkan *feature level* 1 (`android.hardware.vulkan.level >= 1`) guarantees,
 *    so [PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL] stands in for it.
 *
 *  - A compute-capable queue family is needed for every shader it dispatches;
 *    Android reports that as
 *    [PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE].
 *
 * Everything else ggml looks at (`shaderFloat16`, cooperative matrix, integer
 * dot product, subgroup size control, bfloat16/float8) is strictly optional —
 * ggml falls back when the feature is missing, so it must *not* be part of this
 * gate.
 *
 * What this gate deliberately cannot tell you is whether the *driver* is
 * correct: it is a static feature query, and the ggml device init that follows
 * it compiles and dispatches real shaders. Devices have passed this check and
 * then aborted inside `ggml_vk_guess_split_k()` — which runs on a *matmul
 * dispatch*, not during init — on a driver bug (see `GPU_VULKAN_FINDINGS.md`).
 * So it stays a necessary-but-not-sufficient filter, and the picker warns that
 * Vulkan is unsupported on Adreno, where that is known to happen
 * ([BackendSupport.isVulkanKnownBroken]).
 *
 * Kept in its own object (not a companion of WhisperGGMLGpu) so that calling
 * [isAvailable] does not trigger [WhisperGGMLGpu]'s class initializer, which
 * loads libvoiceinput_gpu.so via System.loadLibrary.
 */
object GpuSupport {
    /** VK_MAKE_API_VERSION(0, 1, 2, 0) — the version ggml-vulkan requires. */
    private const val VK_API_VERSION_1_2 = 0x402000

    /** Vulkan 1.1 feature level, i.e. `storageBuffer16BitAccess` is guaranteed. */
    private const val VULKAN_FEATURE_LEVEL_1_1 = 1

    fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // Vulkan 1.1 loader symbols unavailable below API 28.
            return false
        }

        val packageManager = context.packageManager
        return hasVulkanV1_2(packageManager) &&
                hasStorageBuffer16BitAccess(packageManager) &&
                hasComputeQueue(packageManager)
    }

    /**
     * True when the device exposes a Vulkan 1.2 driver.
     *
     * `hasSystemFeature(name, version)` compares the device's packed Vulkan API
     * version against the requested one, so this returns true only when the
     * device truly reports >= 1.2.
     */
    private fun hasVulkanV1_2(packageManager: PackageManager): Boolean =
        packageManager.hasSystemFeature(
            PackageManager.FEATURE_VULKAN_HARDWARE_VERSION,
            VK_API_VERSION_1_2
        )

    /**
     * True when the driver implements the Vulkan 1.1 feature set, which is where
     * `storageBuffer16BitAccess` comes from.
     */
    private fun hasStorageBuffer16BitAccess(packageManager: PackageManager): Boolean =
        packageManager.hasSystemFeature(
            PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL,
            VULKAN_FEATURE_LEVEL_1_1
        )

    /** True when the device has a compute-capable queue family. */
    private fun hasComputeQueue(packageManager: PackageManager): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE)
}
