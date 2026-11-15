#!/usr/bin/env bash
set -euo pipefail

ABI="\${1:?Usage: build-ffmpeg-android.sh <arm64-v8a|armeabi-v7a|x86_64>}"
FFMPEG_VERSION="9.0.2"
API_LEVEL="26"
NDK="\${ANDROID_NDK_HOME:-\${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" ]]; then
  echo "ANDROID_NDK_HOME or ANDROID_NDK_ROOT must point to the Android NDK." >&2
  exit 1
fi

case "$ABI" in
  arm64-v8a) TARGET="aarch64-linux-android"; ARCH="aarch64"; CPU="armv8-a" ;;
  armeabi-v7a) TARGET="armv7a-linux-androideabi"; ARCH="arm"; CPU="armv7-a" ;;
  x86_64) TARGET="x86_64-linux-android"; ARCH="x86_64"; CPU="x86-64" ;;
  *) echo "Unsupported ABI: $ABI" >&2; exit 2 ;;
esac

HOST_TAG="linux-x86_64"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
SYSROOT="$TOOLCHAIN/sysroot"
CC="$TOOLCHAIN/bin/\${TARGET}\${API_LEVEL}-clang"
CXX="$TOOLCHAIN/bin/\${TARGET}\${API_LEVEL}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
NM="$TOOLCHAIN/bin/llvm-nm"
STRIP="$TOOLCHAIN/bin/llvm-strip"

for tool in "$CC" "$CXX" "$AR" "$RANLIB" "$NM"; do
  test -x "$tool" || { echo "Missing NDK tool: $tool" >&2; exit 3; }
done

ROOT="$(cd "$(dirname "\${BASH_SOURCE[0]}")" && pwd)"
WORK="$ROOT/ffmpeg/work"
SOURCE="$WORK/ffmpeg-$FFMPEG_VERSION"
BUILD="$WORK/build-$ABI"
PREFIX="$ROOT/ffmpeg/prebuilt/$ABI"
mkdir -p "$WORK" "$BUILD" "$PREFIX"

TARBALL="$WORK/ffmpeg-$FFMPEG_VERSION.tar.xz"
SIGNATURE="$TARBALL.asc"
if [[ ! -s "$TARBALL" ]]; then
  curl --fail --location --retry 3 "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" --output "$TARBALL"
fi
if [[ ! -s "$SIGNATURE" ]]; then
  curl --fail --location --retry 3 "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz.asc" --output "$SIGNATURE"
fi

if ! gpg --list-keys FCF986EA15E6E293A5644F10B4322F04D67658D8 >/dev/null 2>&1; then
  curl --fail --location https://ffmpeg.org/ffmpeg-devel.asc | gpg --batch --import
fi
gpg --batch --verify "$SIGNATURE" "$TARBALL"

if [[ ! -f "$SOURCE/configure" ]]; then
  rm -rf "$SOURCE"
  tar -xJf "$TARBALL" -C "$WORK"
fi

rm -rf "$BUILD" "$PREFIX"
mkdir -p "$BUILD" "$PREFIX"
cd "$BUILD"

EXTRA_CFLAGS="-O3 -fPIC -fstack-protector-strong"
EXTRA_LDFLAGS="-Wl,-z,max-page-size=16384"
ARCH_FLAGS=()
if [[ "$ABI" == "armeabi-v7a" ]]; then
  ARCH_FLAGS+=(--enable-neon --enable-thumb)
fi

"$SOURCE/configure" \
  --prefix="$PREFIX" \
  --target-os=android \
  --arch="$ARCH" \
  --cpu="$CPU" \
  --enable-cross-compile \
  --sysroot="$SYSROOT" \
  --cc="$CC" \
  --cxx="$CXX" \
  --ar="$AR" \
  --ranlib="$RANLIB" \
  --nm="$NM" \
  --strip="$STRIP" \
  --extra-cflags="$EXTRA_CFLAGS" \
  --extra-ldflags="$EXTRA_LDFLAGS" \
  --enable-jni \
  --enable-mediacodec \
  --enable-network \
  --enable-static \
  --disable-shared \
  --enable-pic \
  --disable-programs \
  --disable-doc \
  --disable-debug \
  --disable-encoders \
  --disable-muxers \
  --disable-filters \
  --disable-postproc \
  --disable-devices \
  --disable-indevs \
  --disable-outdevs \
  --disable-autodetect \
  --disable-symver \
  "\${ARCH_FLAGS[@]}"

make -j"$(nproc)"
make install

for library in avformat avcodec swscale avutil; do
  test -s "$PREFIX/lib/lib\${library}.a" || {
    echo "FFmpeg did not produce lib\${library}.a for $ABI" >&2
    exit 4
  }
done
test -s "$PREFIX/include/libavcodec/mediacodec.h"
test -s "$PREFIX/include/libavcodec/jni.h"
printf '%s\n' "$FFMPEG_VERSION" > "$PREFIX/FFMPEG_VERSION"
printf 'FFmpeg %s built for %s with MediaCodec/JNI support\n' "$FFMPEG_VERSION" "$ABI"
