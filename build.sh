#!/usr/bin/env bash
# Linux / macOS / Git Bash equivalent of build.ps1 (the Windows script has more hand-holding: it can download the
# whole toolchain for you). Here you provide the toolchain.
#
#   ./build.sh                       YouTube edition, debug-signed APK in dist/
#   ./build.sh core                  the edition without YouTube
#   ./build.sh youtube --install     ...and install it with adb (one device connected, or ANDROID_SERIAL set)
#
# You need: JDK 17+, the Android SDK with NDK 27.3.13750724 and CMake 3.22.1 (ANDROID_HOME set), and the VITURE SDK
# extracted into SDK/ (SDK/android/armeabi-v7a/libglasses.so). See README, "Build the YouTube edition".
set -eu
cd "$(dirname "$0")"

EDITION=youtube; INSTALL=0
for a in "$@"; do
    case "$a" in
        core|youtube) EDITION=$a ;;
        --install) INSTALL=1 ;;
        *) echo "usage: ./build.sh [core|youtube] [--install]"; exit 1 ;;
    esac
done

[ -f SDK/android/armeabi-v7a/libglasses.so ] || {
    echo "The VITURE glasses SDK is missing. Download it from VITURE and extract it into SDK/ so that"
    echo "SDK/android/armeabi-v7a/libglasses.so exists (it is proprietary and not part of this repository)."
    exit 1
}
[ -n "${ANDROID_HOME:-}" ] || { echo "Set ANDROID_HOME to your Android SDK (with NDK 27.3.13750724 and CMake 3.22.1)."; exit 1; }
echo "sdk.dir=$ANDROID_HOME" > local.properties

if [ ! -f third_party/ffmpeg/android-libs/armeabi-v7a/libavcodec.a ]; then
    NDK="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort | tail -1 || true)"
    if [ -n "$NDK" ]; then
        echo "== Building the TrueHD / DTS decoders (once, a few minutes)"
        tools/build_ffmpeg.sh "$NDK" 26 || echo "FFmpeg build failed; continuing without TrueHD / DTS decoding."
    else
        echo "NDK not found under $ANDROID_HOME/ndk: skipping the FFmpeg build."
    fi
fi

TASK="assemble$(echo "${EDITION:0:1}" | tr a-z A-Z)${EDITION:1}Debug"
echo "== Building the $EDITION edition"
./gradlew --no-daemon -q "$TASK"
mkdir -p dist
APK="app/build/outputs/apk/$EDITION/debug/simple-3dof-tv-$EDITION-debug.apk"
cp "$APK" dist/
echo "Built dist/$(basename "$APK")"

if [ "$INSTALL" = 1 ]; then
    adb install -r "dist/$(basename "$APK")"
else
    echo "Install it with: adb install -r dist/$(basename "$APK")"
fi
