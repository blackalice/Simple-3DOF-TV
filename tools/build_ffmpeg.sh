#!/usr/bin/env bash
# Builds the FFmpeg static libraries that libffmpegJNI links (armeabi-v7a, LGPL, audio decoders only).
#
#   tools/build_ffmpeg.sh <path-to-android-ndk> [android-api-level]
#   e.g.  tools/build_ffmpeg.sh .toolchain/sdk/ndk/27.3.13750724
#
# What it does: downloads FFmpeg n6.0 into third_party/ffmpeg (git-ignored) if it is not there, configures a minimal
# build (no GPL, no nonfree, no programs, no demuxers: only the three decoders below plus libswresample) and installs
# libavcodec.a, libavutil.a and libswresample.a into third_party/ffmpeg/android-libs/armeabi-v7a/.
# app/src/main/cpp/CMakeLists.txt then builds libffmpegJNI.so from them when they exist (see DEVELOPMENT.md).
#
# Decoders: truehd and mlp (Dolby TrueHD / MLP), dca (DTS, DTS-HD). Everything else stays on the Pocket TV's own codecs.
# This is the same recipe as Media3's decoder_ffmpeg/build_ffmpeg.sh, reduced to one ABI and three decoders.
set -eu

NDK="${1:?usage: build_ffmpeg.sh <android-ndk-path> [api-level]}"
NDK="$(cd "$NDK" && pwd)"      # accepts Windows-style paths (C:/...) under Git Bash
API="${2:-26}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FFMPEG_TAG="n6.0"
DECODERS=(truehd mlp dca)
SRC="$ROOT/third_party/ffmpeg"

# FFmpeg's configure needs a POSIX temp dir: Windows-style TMP/TEMP values (drive letter and backslashes) break it
# under Git Bash.
mkdir -p "$ROOT/third_party/tmp"
export TMPDIR="$ROOT/third_party/tmp"
unset TMP TEMP

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64 ;;
    Darwin*)              HOST=darwin-x86_64 ;;
    *)                    HOST=linux-x86_64 ;;
esac
TOOLS="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
[ -d "$TOOLS" ] || { echo "NDK toolchain not found at $TOOLS"; exit 1; }
CLANG="$TOOLS/armv7a-linux-androideabi${API}-clang"
[ -e "$CLANG" ] || { echo "No $CLANG: this NDK does not support API $API"; exit 1; }

# make: the NDK bundles one for Windows
if ! command -v make >/dev/null 2>&1; then
    if [ -x "$NDK/prebuilt/$HOST/bin/make.exe" ]; then PATH="$NDK/prebuilt/$HOST/bin:$PATH"; export PATH
    else echo "make not found"; exit 1; fi
fi

if [ ! -f "$SRC/configure" ]; then
    echo "Downloading FFmpeg $FFMPEG_TAG ..."
    mkdir -p "$ROOT/third_party"
    TARBALL="$ROOT/third_party/ffmpeg-$FFMPEG_TAG.tar.gz"
    [ -f "$TARBALL" ] || curl -fsSL -o "$TARBALL" "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/$FFMPEG_TAG.tar.gz"
    rm -rf "$ROOT/third_party/FFmpeg-$FFMPEG_TAG" "$SRC"
    tar -xzf "$TARBALL" -C "$ROOT/third_party"
    mv "$ROOT/third_party/FFmpeg-$FFMPEG_TAG" "$SRC"
fi

OPTIONS=(
    --target-os=android --enable-static --disable-shared
    --disable-doc --disable-programs --disable-everything
    --disable-avdevice --disable-avformat --disable-swscale --disable-postproc --disable-avfilter
    --disable-symver --disable-v4l2-m2m --disable-vulkan --disable-network
    --disable-gpl --disable-nonfree
    --enable-swresample
)
# Licence: LGPL v2.1 or later (no --enable-gpl, --enable-version3 or --enable-nonfree)
for d in "${DECODERS[@]}"; do OPTIONS+=("--enable-decoder=$d"); done

cd "$SRC"
JOBS="$(nproc 2>/dev/null || echo 4)"
./configure \
    --libdir=android-libs/armeabi-v7a \
    --arch=arm --cpu=armv7-a \
    --cross-prefix="$TOOLS/armv7a-linux-androideabi${API}-" \
    --nm="$TOOLS/llvm-nm" --ar="$TOOLS/llvm-ar" --ranlib="$TOOLS/llvm-ranlib" --strip="$TOOLS/llvm-strip" \
    --extra-cflags="-march=armv7-a -mfloat-abi=softfp" \
    --extra-ldflags="-Wl,--fix-cortex-a8" \
    "${OPTIONS[@]}"
make -j"$JOBS"
make install-libs
make clean

echo
echo "Built: $(ls "$SRC/android-libs/armeabi-v7a"/*.a | xargs -n1 basename | tr '\n' ' ')"
