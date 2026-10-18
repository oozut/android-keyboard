# Building with Docker / Podman

This document describes how to build the **FUTO Keyboard** Android APK inside a
container, with no Android Studio and no Android SDK installed on the host.

It covers two approaches:

1. **[Self-contained Docker build](#approach-1-self-contained-docker-build)** —
   uses the repo's own `.ci/Dockerfile` (`gradle:8.14.3-jdk21`). Simplest, fully
   reproducible, nothing pre-built required.
2. **[Toolchain container + volumes](#approach-2-toolchain-container--volumes)** —
   a minimal image plus the SDK/NDK on a named volume. This is the setup used
   during development on this box (via Podman + distrobox). Faster rebuilds, SDK
   survives container recreation.

> The commands below show `docker`, but everything works identically with
> **Podman** (`podman`) and **distrobox**; just substitute the binary name.

---

## Prerequisites

| Item | Requirement |
|---|---|
| Disk | **~10 GB** free (SDK ~4 GB, NDK ~2 GB, Gradle caches ~2 GB, APK ~250 MB) |
| Network | Outbound access to `dl.google.com`, `services.gradle.org`, Maven Central, and GitHub |
| Container runtime | Docker **or** Podman |
| Git | With submodule support |

**Important — submodules.** The build depends on 7 git submodules (including
`libs/`, which carries the Mozc IME AAR and the QNN runtime AARs):

```bash
git clone --recursive <repo-url>
# or, if you already cloned without --recursive:
git submodule update --init --recursive
```

---

## Approach 1 — Self-contained Docker build

The repo ships a complete build image in `.ci/Dockerfile`
(`gradle:8.14.3-jdk21` + Android SDK 35 + NDK `28.2.13676358` + build tools +
AWS CLI). Build it and run the build in one shot:

```bash
# 1. Build the image (downloads the SDK + NDK; ~4 GB, takes a while)
docker build -t android-keyboard-ci -f .ci/Dockerfile .

# 2. Build the APK
docker run --rm -v "$PWD":/workspace -w /workspace android-keyboard-ci \
  ./gradlew --no-daemon assembleUnstableDebug
```

The APK lands on your host at:

```
build/outputs/apk/unstable/debug/android-keyboard-unstable-debug.apk
```

> This approach compiles **everything**, including the hardware-acceleration
> engine (see [Hardware-acceleration engine](#hardware-acceleration-engine)),
> as long as the extra shader tooling is present in the image (the base
> `gradle:8.14.3-jdk21` image may need it added — see that section).

---

## Approach 2 — Toolchain container + volumes

The setup that was actually used during development. It keeps the **SDK/NDK on
a named volume** so you can recreate the container freely without re-downloading
~4 GB, and it separates the image (small) from the state (volume).

### 2.1 Image

Minimal base — Ubuntu 24.04 + JDK 17 + the few host tools the build needs. The
SDK is **not** baked in.

`android-toolchain/Dockerfile`:

```dockerfile
FROM docker.io/library/ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-17-jdk-headless \
        build-essential cmake ninja-build \
        unzip curl git ca-certificates \
        python3 file less \
    && rm -rf /var/lib/apt/lists/* \
    && java -version 2>&1 | head -1

WORKDIR /workspace
```

> **JDK:** 17 or 21 both work (AGP 8.10.1 / Gradle 8.14.3 require ≥ 17).
> **`build-essential` / `cmake` / `ninja`** are required for the
> hardware-acceleration engine's shader compilation (see below).

Build it:

```bash
docker build -t android-toolchain:1 android-toolchain/
```

### 2.2 SDK/NDK volume

```bash
docker volume create android-sdk
docker volume create gradle-home

docker run --rm -it \
  -v android-sdk:/opt/android-sdk \
  android-toolchain:1 bash
```

Inside the container, bootstrap the SDK and install the exact versions this
project pins:

```bash
mkdir -p /opt/android-sdk/cmdline-tools
cd /opt/android-sdk
curl -o cli.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q cli.zip && rm cli.zip
mv cmdline-tools latest && mkdir cmdline-tools && mv latest cmdline-tools

yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses
/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0" \
  "cmake;3.22.1" "ndk;28.2.13676358"
```

### 2.3 Run the build

```bash
docker run --rm \
  -v "$PWD":/workspace -w /workspace \
  -v android-sdk:/opt/android-sdk \
  -v gradle-home:/opt/gradle-home \
  -e GRADLE_USER_HOME=/opt/gradle-home \
  android-toolchain:1 \
  ./gradlew --no-daemon assembleUnstableDebug
```

> If the SDK lives elsewhere, point the build at it with a `local.properties`
> in the repo root containing `sdk.dir=/path/to/android-sdk` (or set
> `ANDROID_HOME`).

---

## Hardware-acceleration engine

The repo vendors **whisper.cpp v1.9.3** (under `native/jni/src/whisper-gpu/`)
with its **Vulkan** backend, compiled into `libvoiceinput_gpu.so` (arm64-v8a
only). Its CMake step generates ~319 SPIR-V shaders **at build time**, which
requires extra host tooling **inside the container**:

| Tool | Purpose |
|---|---|
| `glslc` (shaderc) | Compiles `.comp` → SPIR-V shaders |
| `SPIRV-Headers` | SPIR-V C++ headers consumed by ggml-vulkan |
| `g++` (`build-essential`) | Builds the host `vulkan-shaders-gen` tool |

> **glslc gotcha:** Ubuntu 24.04 does not ship a `glslc` binary package (only
> `libshaderc-dev`). Use a newer base (Ubuntu 26.04 / Debian trixie package it
> as `glslc`), or install a prebuilt `glslc` from the
> [shaderc releases](https://github.com/google/shaderc/releases). The Vulkan C
> headers/libs come from the NDK sysroot; `vulkan.hpp` + SPIR-V headers are
> vendored in `native/jni/src/whisper-gpu/third_party/`.

If the shader tooling is missing, the CMake configure step fails fast with a
`find_package(Vulkan COMPONENTS glslc)` error — install `glslc` and retry.

The QNN (Hexagon HTP) runtime for Snapdragon devices is pulled from Maven
(`com.qualcomm.qti:qnn-runtime`, `com.microsoft.onnxruntime:onnxruntime-android-qnn`)
and requires no build-time tooling beyond the NDK.

---

## Build commands & flavors

```bash
./gradlew assembleUnstableDebug      # default flavor, debug-signed, installable
./gradlew assembleStableDebug
./gradlew assembleStableRelease      # requires keystore.properties
./gradlew assemblePlaystoreRelease
```

| Flavor | `applicationId` | Notes |
|---|---|---|
| `unstable` (default) | `org.futo.inputmethod.latin.unstable` | dev build |
| `stable` | `org.futo.inputmethod.latin` | release-track |
| `playstore` | `org.futo.inputmethod.latin.playstore` | Google Play |

**Signing:**
- **Debug** builds sign with the committed `java/shared.keystore` — no key setup
  needed, the APK installs directly.
- **Release** builds need a `keystore.properties` file (with `keyAlias`,
  `storePassword`, `storeFile`, `keyPassword`); without it the APK is produced
  unsigned.

The APK outputs follow the pattern
`build/outputs/apk/<flavor>/<debug|release>/android-keyboard-<flavor>-<debug|release>.apk`.

---

## Version pins (do not drift)

| Component | Version | Where |
|---|---|---|
| Gradle | 8.14.3 | `gradle/wrapper/gradle-wrapper.properties` |
| AGP | 8.10.1 | `build.gradle` |
| Kotlin | 2.1.0 | `build.gradle` |
| JDK | 17 (or 21) | container |
| compileSdk / targetSdk | 35 | `build.gradle` |
| minSdk | 24 | `build.gradle` |
| NDK | 28.2.13676358 | `build.gradle:380` |
| CMake | 3.22.1 | AGP default |

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `fatal: No names found` / missing `updateLocales` | Submodules not initialized — run `git submodule update --init --recursive`. |
| `Could not resolve ... :mozc-release:` | `libs/` submodule empty — init submodules. |
| `find_package(Vulkan COMPONENTS glslc)` fails | `glslc` not installed in the container — see [Hardware-acceleration engine](#hardware-acceleration-engine). |
| Gradle can't write to Gradle home | Volume ownership mismatch — chown the volume dir to the container user (`uid 1000`). |
| `undefined symbol: vkGetPhysicalDeviceFeatures2` at link | Linking against API-24 `libvulkan.so` stub; the wrapper already pins the API-28 stub — don't override `Vulkan_LIBRARY`. |
