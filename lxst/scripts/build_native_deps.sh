#!/usr/bin/env bash
# Cross-compile libcodec2.so and libopus.so for Android ABIs using NDK r28c.
#
# NDK r28+ is required for Google Play 16 KB page-size compliance: older
# prebuilts were 4 KB-aligned (p_align 0x1000) and were flagged by Play as
# "Recompile your app with 16 KB native library alignment" plus the
# "compiled with an older Android NDK" crash advisory. r28 aligns LOAD
# segments to 0x4000 by default; the linker flags below keep that explicit.
#
# Usage: ./build_native_deps.sh [abi]
#   abi: armeabi-v7a | arm64-v8a | all (default: armeabi-v7a)
#
# Prerequisites: Android NDK r28c at $ANDROID_HOME/ndk/28.2.13676358
#                git, cmake, make

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LXST_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JNILIBS_DIR="$LXST_DIR/src/main/jniLibs"
BUILD_ROOT="/tmp/lxst-native-build"

NDK_VERSION="28.2.13676358"
NDK_DIR="${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK path}/ndk/$NDK_VERSION"
CMAKE_TOOLCHAIN="$NDK_DIR/build/cmake/android.toolchain.cmake"
API_LEVEL=24  # Match minSdk in build.gradle.kts

# 16 KB page-size compatibility (required by Google Play for targetSdk 35+).
# NDK r28 defaults to this, but pass it explicitly so a downgraded NDK fails
# loudly in review rather than silently shipping 4 KB-aligned prebuilts.
PAGE_SIZE_FLAGS=(
    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
)

OPUS_VERSION="v1.5.2"
OPUS_REPO="https://github.com/xiph/opus.git"

CODEC2_VERSION="1.2.0"
CODEC2_REPO="https://github.com/drowe67/codec2.git"

TARGET_ABI="${1:-armeabi-v7a}"

# ── Helpers ──────────────────────────────────────────────────────────────────

log() { echo "==> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

# CMake Release output keeps .debug/.symtab; strip so the AAR (and every
# consumer APK) doesn't carry ~1 MB of unused symbols per library. Matches the
# previously shipped prebuilts, which were stripped.
LLVM_STRIP="$(find "$NDK_DIR/toolchains/llvm/prebuilt" -name llvm-strip | head -1)"
[[ -x "$LLVM_STRIP" ]] || die "llvm-strip not found under $NDK_DIR"

strip_lib() { "$LLVM_STRIP" --strip-unneeded "$1"; }

check_prereqs() {
    [[ -d "$NDK_DIR" ]] || die "NDK $NDK_VERSION not found at $NDK_DIR"
    [[ -f "$CMAKE_TOOLCHAIN" ]] || die "CMake toolchain not found"
    command -v cmake >/dev/null || die "cmake not found"
    command -v git >/dev/null || die "git not found"
}

# Map ABI to NDK architecture name
abi_to_arch() {
    case "$1" in
        armeabi-v7a) echo "armv7-a" ;;
        arm64-v8a)   echo "aarch64" ;;
        x86)         echo "x86" ;;
        x86_64)      echo "x86_64" ;;
        *) die "Unknown ABI: $1" ;;
    esac
}

build_for_abi() {
    local abi="$1"
    log "Building for ABI: $abi"

    local out_dir="$JNILIBS_DIR/$abi"
    mkdir -p "$out_dir"

    build_opus "$abi" "$out_dir"
    build_codec2 "$abi" "$out_dir"

    log "Done: $out_dir"
    ls -lh "$out_dir"/*.so
}

# ── Opus ─────────────────────────────────────────────────────────────────────

build_opus() {
    local abi="$1" out_dir="$2"
    local src_dir="$BUILD_ROOT/opus"
    local build_dir="$BUILD_ROOT/opus-build-$abi"

    if [[ ! -d "$src_dir" ]]; then
        log "Cloning Opus $OPUS_VERSION..."
        git clone --depth 1 --branch "$OPUS_VERSION" "$OPUS_REPO" "$src_dir"
    fi

    log "Building Opus for $abi..."
    rm -rf "$build_dir"
    mkdir -p "$build_dir"

    cmake -S "$src_dir" -B "$build_dir" \
        -DCMAKE_TOOLCHAIN_FILE="$CMAKE_TOOLCHAIN" \
        -DANDROID_ABI="$abi" \
        -DANDROID_NATIVE_API_LEVEL="$API_LEVEL" \
        -DANDROID_STL=c++_shared \
        "${PAGE_SIZE_FLAGS[@]}" \
        -DCMAKE_BUILD_TYPE=Release \
        -DOPUS_BUILD_SHARED_LIBRARY=ON \
        -DOPUS_BUILD_TESTING=OFF \
        -DOPUS_BUILD_PROGRAMS=OFF \
        -DOPUS_INSTALL_PKG_CONFIG_MODULE=OFF \
        -DBUILD_SHARED_LIBS=ON

    cmake --build "$build_dir" --parallel "$(nproc)"

    cp "$build_dir/libopus.so" "$out_dir/libopus.so"
    strip_lib "$out_dir/libopus.so"
    log "Opus built: $out_dir/libopus.so"
}

# ── Codec2 ───────────────────────────────────────────────────────────────────

build_codec2() {
    local abi="$1" out_dir="$2"
    local src_dir="$BUILD_ROOT/codec2"
    local build_dir="$BUILD_ROOT/codec2-build-$abi"

    if [[ ! -d "$src_dir" ]]; then
        log "Cloning Codec2 $CODEC2_VERSION..."
        git clone --depth 1 --branch "$CODEC2_VERSION" "$CODEC2_REPO" "$src_dir"
    fi

    log "Building Codec2 for $abi..."
    rm -rf "$build_dir"
    mkdir -p "$build_dir"

    # Codec2's CMake needs some hand-holding for cross-compilation:
    # - Disable all extras (lpcnet, tests, programs)
    # - Build only the shared library
    cmake -S "$src_dir" -B "$build_dir" \
        -DCMAKE_TOOLCHAIN_FILE="$CMAKE_TOOLCHAIN" \
        -DANDROID_ABI="$abi" \
        -DANDROID_NATIVE_API_LEVEL="$API_LEVEL" \
        -DANDROID_STL=c++_shared \
        "${PAGE_SIZE_FLAGS[@]}" \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=ON \
        -DLPCNET=OFF \
        -DUNITTEST=OFF \
        -DINSTALL_EXAMPLES=OFF \
        -DCMAKE_CROSSCOMPILING=ON

    cmake --build "$build_dir" --target codec2 --parallel "$(nproc)"

    # Find the built .so (could be in src/ subdirectory)
    local built_so
    built_so=$(find "$build_dir" -name "libcodec2.so*" -type f ! -name "*.so.*" | head -1)
    if [[ -z "$built_so" ]]; then
        # Might be a symlink chain — find the real file
        built_so=$(find "$build_dir" -name "libcodec2.so" | head -1)
    fi
    [[ -n "$built_so" ]] || die "libcodec2.so not found in $build_dir"

    # Follow symlinks to get actual file
    cp -L "$built_so" "$out_dir/libcodec2.so"
    strip_lib "$out_dir/libcodec2.so"
    log "Codec2 built: $out_dir/libcodec2.so"
}

# ── Main ─────────────────────────────────────────────────────────────────────

check_prereqs
mkdir -p "$BUILD_ROOT"

if [[ "$TARGET_ABI" == "all" ]]; then
    for abi in armeabi-v7a arm64-v8a; do
        build_for_abi "$abi"
    done
else
    build_for_abi "$TARGET_ABI"
fi

log "All builds complete!"
