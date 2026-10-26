# ------------------------------------------------------------------
# QNN (Qualcomm Hexagon HTP / NPU) voice-input engine — libvoiceinput_qnn.so
#
# Runs Qualcomm AI Hub's precompiled Whisper-Large-V3-Turbo-Quantized
# (w8a16) context binaries on the Hexagon NPU through the ONNX Runtime
# C API + QNN Execution Provider. Ported from whisper-htp-android (MIT):
#   https://github.com/thedevguy/whisper-htp-android
#
# The ORT C API headers are vendored under ort/include (MIT, from the
# onnxruntime-android-qnn AAR). libonnxruntime.so / libQnnHtp.so are NOT
# linked at build time — they ship via the onnxruntime-android-qnn and
# qnn-runtime AARs and are dlopen'd by absolute path at runtime from the
# app's native library dir.
#
# Built only for arm64-v8a (all Snapdragon HTP parts are 64-bit). Every
# other ABI skips this via the guard below; the CPU voice path is untouched.
# ------------------------------------------------------------------
cmake_minimum_required(VERSION 3.22)

if(NOT DEFINED ANDROID_ABI OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(STATUS "qnn-htp: skipping (QNN engine is arm64-v8a only; ABI=${ANDROID_ABI})")
    return()
endif()

add_library(voiceinput_qnn SHARED
        htp_whisper.cpp
        whisper_mel.cpp)

target_compile_features(voiceinput_qnn PRIVATE cxx_std_17)

target_include_directories(voiceinput_qnn PRIVATE
        # ONNX Runtime C API headers, vendored from the onnxruntime-android-qnn
        # AAR (MIT). libonnxruntime.so itself is dlopen'd at runtime.
        ${CMAKE_CURRENT_SOURCE_DIR}/ort/include)

# __fp16 arithmetic for the fp16 tensor helpers (kept for the float variant;
# the w8a16 config we ship does not require it, but the flag is harmless).
target_compile_options(voiceinput_qnn PRIVATE -O3 -march=armv8.2-a+fp16)

find_library(LOG_LIB log)
target_link_libraries(voiceinput_qnn ${LOG_LIB} android dl)
