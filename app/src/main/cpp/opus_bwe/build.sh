#!/bin/sh
# Rebuilds ../../jniLibs/arm64-v8a/libopusbwe.so. OPUS = an Opus 1.6 source tree, OPUS_BUILD = its
# Android arm64 build (cmake -DOPUS_OSCE=ON, NDK toolchain, android-28, CMAKE_C_FLAGS=
# "-DENABLE_OSCE_BWE -ffile-prefix-map=<OPUS>=opus": no local paths in the assertion strings).
set -e
CC="$ANDROID_NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android28-clang.cmd"
"$CC" -shared -fPIC -O2 -std=gnu99 -DHAVE_CONFIG_H -DENABLE_OSCE -DENABLE_OSCE_BWE -DENABLE_DEEP_PLC \
  -I"$OPUS_BUILD" -I"$OPUS/include" -I"$OPUS" -I"$OPUS/dnn" -I"$OPUS/celt" -I"$OPUS/silk" \
  -ffile-prefix-map="$OPUS"=opus -ffile-prefix-map="$OPUS_BUILD"=build \
  -Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,--exclude-libs,ALL -s \
  -o "$(dirname "$0")/../../jniLibs/arm64-v8a/libopusbwe.so" "$(dirname "$0")/opus_bwe_jni.c" "$OPUS_BUILD/libopus.a" -lm
