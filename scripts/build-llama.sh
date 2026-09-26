#!/usr/bin/env bash
# Build llama.cpp for the phones (Android arm64) and for this laptop, from one commit.
# ggml RPC only works between identical builds, so both always come from the same checkout.
#
#   scripts/build-llama.sh            # both
#   scripts/build-llama.sh android    # phones only
#   scripts/build-llama.sh host       # laptop only
#   scripts/build-llama.sh emulator   # x86_64 Android, for testing on the emulator only
set -euo pipefail

LLAMA_SRC=${LLAMA_SRC:-/mnt/storage/meshai/build/third_party/llama.cpp}
LLAMA_COMMIT=${LLAMA_COMMIT:-66fba63}
OUT=${OUT:-/mnt/storage/meshai/build/v3}
NDK=${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/28.2.13676358}   # r28+ aligns to 16 KB pages
JOBS=${JOBS:-$(nproc)}
WHAT=${1:-all}

TARGETS=(ggml-rpc-server llama-server llama-bench)

if [ ! -d "$LLAMA_SRC/.git" ]; then
  git clone https://github.com/ggml-org/llama.cpp "$LLAMA_SRC"
fi
if [ "$(git -C "$LLAMA_SRC" rev-parse --short=7 HEAD)" != "$LLAMA_COMMIT" ]; then
  git -C "$LLAMA_SRC" fetch -q origin
  git -C "$LLAMA_SRC" checkout -q --detach "$LLAMA_COMMIT"
fi
echo "llama.cpp $(git -C "$LLAMA_SRC" log -1 --format='%h %cs')"

if command -v ninja >/dev/null; then GEN=Ninja; else GEN="Unix Makefiles"; fi

COMMON=(
  -DCMAKE_BUILD_TYPE=Release
  -DBUILD_SHARED_LIBS=ON
  -DGGML_RPC=ON
  -DLLAMA_CURL=OFF
  -DLLAMA_OPENSSL=OFF
  -DLLAMA_BUILD_TESTS=OFF
  -DLLAMA_BUILD_EXAMPLES=OFF
  -DLLAMA_BUILD_TOOLS=ON
)

build_android() {
  local dir=$OUT/android-arm64
  # dotprod + i8mm: both are on the iQOO 15 (SM8850) and the kernels use them.
  local arch="-march=armv8.2-a+dotprod+i8mm"
  cmake -S "$LLAMA_SRC" -B "$dir" -G "$GEN" "${COMMON[@]}" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 \
    -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF \
    -DCMAKE_C_FLAGS="$arch" -DCMAKE_CXX_FLAGS="$arch"
  cmake --build "$dir" --target "${TARGETS[@]}" -j"$JOBS"

  # Android 15+ devices need every shared object aligned to 16 KB.
  local readelf="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
  for f in "$dir"/bin/*; do
    if "$readelf" -lW "$f" 2>/dev/null | awk '/LOAD/ {print $NF}' | grep -qv 0x4000; then
      echo "not 16 KB aligned: $f" >&2; exit 1
    fi
  done
  echo "android: $dir/bin"
}

build_emulator() {
  local dir=$OUT/android-x86_64
  cmake -S "$LLAMA_SRC" -B "$dir" -G "$GEN" "${COMMON[@]}" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=x86_64 -DANDROID_PLATFORM=android-30 \
    -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF
  cmake --build "$dir" --target "${TARGETS[@]}" -j"$JOBS"
  echo "emulator: $dir/bin"
}

build_host() {
  local dir=$OUT/host
  cmake -S "$LLAMA_SRC" -B "$dir" -G "$GEN" "${COMMON[@]}"
  cmake --build "$dir" --target "${TARGETS[@]}" -j"$JOBS"
  echo "host: $dir/bin"
}

case $WHAT in
  android) build_android ;;
  host) build_host ;;
  emulator) build_emulator ;;
  all) build_android; build_host ;;
  *) echo "usage: $0 [all|android|host|emulator]" >&2; exit 2 ;;
esac
