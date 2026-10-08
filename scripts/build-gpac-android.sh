#!/usr/bin/env bash
set -euo pipefail

ROOT="${1:-$(pwd)/.native/gpac}"
API="${ANDROID_API:-29}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}"
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
  echo "ANDROID_NDK_HOME is required" >&2
  exit 2
fi

HOST=$(uname -s | tr '[:upper:]' '[:lower:]')
case "$HOST" in
  linux*) PREBUILT="linux-x86_64" ;;
  darwin*) PREBUILT="darwin-x86_64" ;;
  *) echo "Unsupported host: $HOST" >&2; exit 2 ;;
esac

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$PREBUILT/bin"
CC="$TOOLCHAIN/aarch64-linux-android${API}-clang"
CXX="$TOOLCHAIN/aarch64-linux-android${API}-clang++"
AR="$TOOLCHAIN/llvm-ar"
RANLIB="$TOOLCHAIN/llvm-ranlib"
STRIP="$TOOLCHAIN/llvm-strip"

for tool in "$CC" "$CXX" "$AR" "$RANLIB" "$STRIP"; do
  test -x "$tool" || { echo "Missing NDK tool $tool" >&2; exit 2; }
done

SRC="/tmp/gpac-v26.07.0-tme"
rm -rf "$SRC"
git clone --depth 1 --branch v26.07.0 https://github.com/gpac/gpac.git "$SRC"

rm -rf "$ROOT"
mkdir -p "$ROOT"

pushd "$SRC" >/dev/null
./configure \
  --target-os=android \
  --cpu=aarch64 \
  --cc="$CC" \
  --cxx="$CXX" \
  --ar="$AR" \
  --ranlib="$RANLIB" \
  --strip="$STRIP" \
  --prefix="$ROOT" \
  --libdir=lib \
  --static-build \
  --static-modules \
  --isomedia-only \
  --enable-log \
  --enable-threads \
  --enable-hevcmerge \
  --enable-hevcsplit \
  --enable-tileagg \
  --enable-fin \
  --enable-mp4dmx \
  --disable-crypto \
  --disable-compositor \
  --disable-vout \
  --disable-aout \
  --disable-qjs \
  --disable-sdl \
  --disable-x11 \
  --disable-dvb4linux \
  --disable-ffmpeg

make -j"$(nproc)" lib
make install-lib
popd >/dev/null

test -f "$ROOT/lib/libgpac.a"
test -f "$ROOT/include/gpac/filters.h"
echo "GPAC_ROOT=$ROOT"
