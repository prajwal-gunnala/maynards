#!/usr/bin/env bash
# Copy the Android engine build into the app. Android only lets an app run files that were installed as
# native libraries, so the two programs are renamed lib*.so and started from nativeLibraryDir.
set -euo pipefail

BIN=${BIN:-/mnt/storage/meshai/build/v3/android-arm64/bin}
NDK=${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/28.2.13676358}
DST=$(dirname "$0")/../android/app/src/main/jniLibs/arm64-v8a
STRIP=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip

mkdir -p "$DST"
rm -f "$DST"/*.so
cp "$BIN/ggml-rpc-server" "$DST/libmesh_rpc.so"
cp "$BIN/llama-server" "$DST/libmesh_server.so"
cp "$BIN"/lib*.so "$DST/"
rm -f "$DST/libllama-bench-impl.so"
"$STRIP" --strip-unneeded "$DST"/*.so
du -sh "$DST"
