#!/usr/bin/env bash
# Copy the Android engine build into the app. Android only lets an app run files that were installed as
# native libraries, so the two programs are renamed lib*.so and started from nativeLibraryDir.
set -euo pipefail

OUT=${OUT:-/mnt/storage/meshai/build/v3}
NDK=${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/28.2.13676358}
LIBS=$(dirname "$0")/../android/app/src/main/jniLibs
STRIP=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip

copy() {  # copy <build bin dir> <abi>
  local bin=$1 dst=$LIBS/$2
  mkdir -p "$dst"
  rm -f "$dst"/*.so
  cp "$bin/ggml-rpc-server" "$dst/libmesh_rpc.so"
  cp "$bin/llama-server" "$dst/libmesh_server.so"
  cp "$bin"/lib*.so "$dst/"
  rm -f "$dst/libllama-bench-impl.so"
  "$STRIP" --strip-unneeded "$dst"/*.so
  du -sh "$dst"
}

copy "$OUT/android-arm64/bin" arm64-v8a
# the emulator build, if there is one (scripts/build-llama.sh emulator)
[ -x "$OUT/android-x86_64/bin/llama-server" ] && copy "$OUT/android-x86_64/bin" x86_64
true
