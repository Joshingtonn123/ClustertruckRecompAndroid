#!/bin/bash
# android/deps/build_unicorn.sh: unicorn (aarch64 only) for Android arm64 into android/deps/out/gen/lib - the
# generator's emulator (android/deps/build_gen_deps.sh does the rest). Its CMake runs QEMU's configure, a Unix shell
# script: on Windows run this in WSL, as android/deps/build_ffmpeg.sh:
#   wsl -d Ubuntu-26.04 -- bash android/deps/build_unicorn.sh
# Needs the Linux NDK (~/android-ndk-r30, as build_ffmpeg.sh fetches it) and cmake / ninja (on PATH, or in
# ~/lasrecomp-tools: python3 -m venv ~/lasrecomp-tools && ~/lasrecomp-tools/bin/pip install cmake ninja).
set -euo pipefail
cd "$(dirname "$0")"
D=$PWD
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
NDK=${ANDROID_NDK_LINUX:-${ANDROID_NDK:-$SDK/ndk/30.0.16248370}}
CMAKE_DIR=${ANDROID_CMAKE_DIR:-$SDK/cmake/3.30.4/bin}
[ -d "$NDK" ] || { echo "no Linux NDK at $NDK"; exit 1; }
export PATH="$CMAKE_DIR:$HOME/lasrecomp-tools/bin:$PATH"
command -v cmake >/dev/null && command -v ninja >/dev/null || { echo "cmake / ninja are required (Android SDK CMake or system packages)"; exit 1; }
# QEMU's configure insists on a pkg-config binary, though unicorn needs no system package: an empty one that finds none
if ! command -v pkg-config >/dev/null; then
    mkdir -p "$HOME/lasrecomp-tools/bin"
    printf '#!/bin/sh\nexit 1\n' > "$HOME/lasrecomp-tools/bin/pkg-config"
    chmod 755 "$HOME/lasrecomp-tools/bin/pkg-config"
fi
SRC=$D/src/unicorn-2.1.4.tar.gz
echo "ea8863f095a0136388694e5a6063afd9bb7650e30243dd6251af59c5ce5601f4  $SRC" | sha256sum -c --quiet
W=$HOME/lasrecomp-unicorn  # (the Linux file system: QEMU's configure is slow on /mnt)
rm -rf "$W" && mkdir -p "$W/src" && tar -xzf "$SRC" -C "$W/src" --strip-components 1
# (QEMU's configure gets the NDK's clang without its target, so its test programs fail and it decides there is no
# __int128 - aarch64 has it, and int128.h's fallback then clashes with the compiler's own type)
sed -i 's/^int128=no$/int128=yes/' "$W/src/qemu/configure"
cmake -S "$W/src" -B "$W/build" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 \
    -DUNICORN_ARCH=aarch64 -DUNICORN_BUILD_TESTS=OFF -DUNICORN_INSTALL=OFF -DBUILD_SHARED_LIBS=ON
cmake --build "$W/build" --parallel "${BUILD_JOBS:-2}"
mkdir -p "$D/out/gen/lib"
cp -L "$(ls "$W"/build/libunicorn.so* | head -1)" "$D/out/gen/lib/libunicorn.so"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$D/out/gen/lib/libunicorn.so"
ls -la "$D/out/gen/lib/libunicorn.so"
