# ------------------------------------------------------------------
# Hardware-accelerated voice-input engine
#
# Self-contained build of whisper.cpp v1.9.3 (upstream, ggml monorepo)
# compiled into a single shared library "voiceinput_gpu". This is
# deliberately SEPARATE from FUTO's entangled pre-Vulkan ggml/whisper
# tree that is compiled into libjni_latinime.so: the CPU voice path is
# untouched and this library is only loaded by the app when the user
# enables hardware acceleration for a model.
#
# Acceleration backends (selected with VOICEINPUT_HW_ACCEL_BACKEND):
#   - vulkan  (default): ggml-vulkan, for Mali/other GPUs.
#   - hexagon (opt-in) : Qualcomm's official ggml-hexagon DSP/HTP backend
#                        (HVX/HMX), for Snapdragon/Adreno devices. Requires
#                        the proprietary Qualcomm Hexagon SDK and toolchain.
#
# Built only for arm64-v8a. 32-bit (armeabi-v7a) Android has effectively
# no Vulkan/DSP support worth shipping. Other ABIs keep building the CPU
# path as before.
# ------------------------------------------------------------------
cmake_minimum_required(VERSION 3.22)

if(NOT DEFINED ANDROID_ABI OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(STATUS "whisper-gpu: skipping (hardware engine is arm64-v8a only; ABI=${ANDROID_ABI})")
    return()
endif()

# Make host CMake packages (SPIRV-Headers etc.) findable even though the
# NDK toolchain restricts most find_* modes to its sysroot.
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)

# ------------------------------------------------------------------
# Backend selection
# ------------------------------------------------------------------
set(VOICEINPUT_HW_ACCEL_BACKEND "vulkan" CACHE STRING
    "whisper-gpu: hardware acceleration backend (vulkan|hexagon)")
set_property(CACHE VOICEINPUT_HW_ACCEL_BACKEND PROPERTY STRINGS vulkan hexagon)

if(VOICEINPUT_HW_ACCEL_BACKEND STREQUAL "hexagon")
    # ggml-hexagon's CMake does message(FATAL_ERROR) if HEXAGON_SDK_ROOT is
    # missing; fail early with a clear message instead. HEXAGON_TOOLS_ROOT
    # may be derived automatically from the SDK's hexagon_sdk.json.
    if(NOT DEFINED HEXAGON_SDK_ROOT OR NOT IS_DIRECTORY "${HEXAGON_SDK_ROOT}")
        message(FATAL_ERROR
            "whisper-gpu: VOICEINPUT_HW_ACCEL_BACKEND=hexagon requires "
            "HEXAGON_SDK_ROOT to point at a Qualcomm Hexagon SDK installation")
    endif()
    set(GGML_HEXAGON ON  CACHE BOOL "ggml: enable Hexagon backend" FORCE)
    set(GGML_VULKAN  OFF CACHE BOOL "ggml: use Vulkan" FORCE)
    message(STATUS "whisper-gpu: backend = hexagon (HEXAGON_SDK_ROOT=${HEXAGON_SDK_ROOT})")
else()
    set(GGML_VULKAN  ON  CACHE BOOL "ggml: use Vulkan" FORCE)
    set(GGML_HEXAGON OFF CACHE BOOL "ggml: enable Hexagon backend" FORCE)
    message(STATUS "whisper-gpu: backend = vulkan")
endif()

# ------------------------------------------------------------------
# Vulkan link-level fix (Android API levels) -- Vulkan backend only
#
# ggml-vulkan calls Vulkan 1.1 core functions (vkGetPhysicalDeviceFeatures2,
# vkGetPhysicalDeviceProperties2, ...). The NDK's libvulkan.so stub only
# exports those from API level 28 (Android 9) onward; below that it exposes
# the Vulkan 1.0 surface only. This app's minSdk is 24, so AGP's
# find_library(Vulkan) resolves the API-24 stub and the final link fails with
# "undefined symbol: vkGetPhysicalDeviceFeatures2".
#
# The GPU engine is inherently for Vulkan-1.2-capable devices (API 29+), and
# its use is gated at runtime in Kotlin, so it is safe to link against the
# API-28 stub while the CPU path keeps supporting older devices. Pre-seed
# Vulkan_LIBRARY before ggml's find_package(Vulkan) runs (find_library does
# not re-search an already-cached variable).
# ------------------------------------------------------------------
if(GGML_VULKAN AND ANDROID)
    get_filename_component(_ndk_llvm_bin "${CMAKE_C_COMPILER}" DIRECTORY)  # .../llvm/prebuilt/<host>/bin
    get_filename_component(_ndk_prebuilt  "${_ndk_llvm_bin}" DIRECTORY)    # .../llvm/prebuilt/<host>
    if(NOT CMAKE_LIBRARY_ARCHITECTURE)
        set(CMAKE_LIBRARY_ARCHITECTURE "aarch64-linux-android")
    endif()
    set(_vk28 "${_ndk_prebuilt}/sysroot/usr/lib/${CMAKE_LIBRARY_ARCHITECTURE}/28/libvulkan.so")
    if(EXISTS "${_vk28}")
        set(Vulkan_LIBRARY "${_vk28}" CACHE FILEPATH "Vulkan library (API 28 stub for 1.1 symbols)" FORCE)
        message(STATUS "whisper-gpu: linking Vulkan against API-28 stub: ${_vk28}")
    else()
        message(WARNING "whisper-gpu: API-28 libvulkan stub not found at ${_vk28}; falling back to NDK default")
    endif()
endif()

# ------------------------------------------------------------------
# ggml options (upstream defaults are fine except what we pin here)
# ------------------------------------------------------------------
# BUILD_SHARED_LIBS is the real .so/.a switch for the ggml targets
# (GGML_STATIC only adds -static link flags for ggml's own tools).
# Force OFF so ggml-base/cpu/<accel> build as static archives and are
# linked into the single self-contained libvoiceinput_gpu.so.
set(BUILD_SHARED_LIBS        OFF CACHE BOOL "build shared libraries" FORCE)
set(GGML_STATIC              ON  CACHE BOOL "ggml: static link libraries" FORCE)
set(GGML_NATIVE              OFF CACHE BOOL "ggml: optimize the build for the current system" FORCE)
set(GGML_OPENMP              OFF CACHE BOOL "ggml: use OpenMP" FORCE)
set(GGML_CCACHE              OFF CACHE BOOL "ggml: use ccache if available" FORCE)
set(GGML_BUILD_TESTS         OFF CACHE BOOL "ggml: build tests" FORCE)
set(GGML_BUILD_EXAMPLES      OFF CACHE BOOL "ggml: build examples" FORCE)
set(GGML_BACKEND_DL          OFF CACHE BOOL "ggml: build backends as dynamic libraries" FORCE)
# Only the CPU + the selected acceleration backend are wanted; everything else stays off.
set(GGML_CUDA                OFF CACHE BOOL "" FORCE)
set(GGML_HIP                 OFF CACHE BOOL "" FORCE)
set(GGML_METAL               OFF CACHE BOOL "" FORCE)
set(GGML_SYCL                OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN_RUN_TESTS    OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN_VALIDATE     OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN_DEBUG        OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN_MEMORY_DEBUG OFF CACHE BOOL "" FORCE)

# ggml emits -Werror-style fatal warnings only if asked; it must never
# treat warnings as errors inside this project.
set(GGML_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)

add_subdirectory(ggml)

if(GGML_VULKAN)
    # ggml-vulkan.cpp includes <vulkan/vulkan.hpp> (the C++ bindings), which the
    # Android NDK does NOT ship -- its sysroot only has the C API vulkan/vulkan.h.
    # Vulkan-Hpp is a separate header project; we vendor the header that matches
    # the NDK's Vulkan headers (1.3.275) under third_party/vulkan-hpp so the build
    # is self-contained and needs no host package. Keep it off the PUBLIC include
    # path: only ggml-vulkan's own translation unit needs it.
    target_include_directories(ggml-vulkan PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}/third_party/vulkan-hpp)
    # ggml-vulkan.cpp also does __has_include(<spirv/unified1/spirv.hpp>) to
    # reflect SPIR-V (cooperative-matrix path). A host NDK cross-compile never
    # searches /usr/include, so vendor the Khronos-layout SPIR-V headers too.
    target_include_directories(ggml-vulkan PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}/third_party/spirv-headers)
endif()

# ------------------------------------------------------------------
# whisper.cpp v1.9.3 core (ASR engine)
# ------------------------------------------------------------------
add_library(whisper-gpu-core STATIC
        whisper/src/whisper.cpp)

target_include_directories(whisper-gpu-core PUBLIC
        whisper/include
        ggml/include)

target_compile_definitions(whisper-gpu-core PRIVATE
        WHISPER_VERSION=\"1.9.3\")

# Link against the aggregate `ggml` target: it PUBLIC-links ggml-base plus
# every enabled backend (ggml-cpu, ggml-vulkan / ggml-hexagon) AND carries the
# backend registry (ggml-backend-reg.cpp) which defines ggml_backend_reg_count /
# ggml_backend_init_by_type / etc. -- linking the sub-libraries directly
# omits that registry and fails at link time. `ggml` also PUBLIC-defines
# GGML_USE_VULKAN / GGML_USE_HEXAGON, so whisper.cpp sees the active backend.
target_link_libraries(whisper-gpu-core PUBLIC ggml)

# ------------------------------------------------------------------
# JNI bridge + shared library
# ------------------------------------------------------------------
add_library(voiceinput_gpu SHARED
        org_futo_voiceinput_WhisperGGMLGpu.cpp)

target_compile_features(voiceinput_gpu PRIVATE cxx_std_17)

target_link_libraries(voiceinput_gpu
        whisper-gpu-core
        log
        dl
        android)

# ------------------------------------------------------------------
# Hexagon skels: package the DSP-side libraries into the output dir
# ------------------------------------------------------------------
# ggml-hexagon builds libggml-htp-v{73,75,79,81}.so via ExternalProject into
# the ggml-hexagon build dir. They must ship alongside libvoiceinput_gpu.so
# (the backend dlopens "file:///libggml-htp-v<N>.so" at runtime) AND be signed
# with a Qualcomm certificate for production devices (unsigned-PD enable is
# attempted at runtime but only works where the OS permits it).
#
# TODO(signing): production DSP skel signing is handled OUTSIDE cmake.
if(GGML_HEXAGON)
    file(GLOB _htp_skels
            "${CMAKE_CURRENT_BINARY_DIR}/ggml/src/ggml-hexagon/libggml-htp-v*.so")
    add_custom_target(voiceinput_htp_skels ALL
            DEPENDS ${_htp_skels}
            COMMENT "whisper-gpu: collecting Hexagon HTP skels")
    add_dependencies(voiceinput_gpu voiceinput_htp_skels)
endif()
