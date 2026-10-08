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
ZLIB_CC="$TOOLCHAIN/aarch64-linux-android${API}-clang"
ZLIB_CXX="$TOOLCHAIN/aarch64-linux-android${API}-clang++"
CC="clang"
CXX="clang++"
AR="$TOOLCHAIN/llvm-ar"
RANLIB="$TOOLCHAIN/llvm-ranlib"
STRIP="$TOOLCHAIN/llvm-strip"
CROSS_PREFIX="$TOOLCHAIN/aarch64-linux-android${API}-"

for tool in "$ZLIB_CC" "$ZLIB_CXX" "$AR" "$RANLIB" "$STRIP"; do
  test -x "$tool" || { echo "Missing NDK tool $tool" >&2; exit 2; }
done

SRC="/tmp/gpac-v26.07.0-tme"
ZLIB_SRC="/tmp/zlib-v1.3.1"
ZLIB_ROOT="/tmp/zlib-android"
rm -rf "$SRC" "$ZLIB_SRC" "$ZLIB_ROOT"
git clone --depth 1 --branch v26.07.0 https://github.com/gpac/gpac.git "$SRC"
git clone --depth 1 --branch v1.3.1 https://github.com/madler/zlib.git "$ZLIB_SRC"

rm -rf "$ROOT"
mkdir -p "$ROOT"

cmake -S "$ZLIB_SRC" -B "$ZLIB_SRC/build"   -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"   -DANDROID_ABI=arm64-v8a   -DANDROID_PLATFORM="android-$API"   -DCMAKE_BUILD_TYPE=Release   -DBUILD_SHARED_LIBS=OFF   -DCMAKE_INSTALL_PREFIX="$ZLIB_ROOT"
cmake --build "$ZLIB_SRC/build" --parallel "$(nproc)"
cmake --install "$ZLIB_SRC/build"

mkdir -p "$SRC/extra_lib/include/zlib" "$SRC/extra_lib/lib/gcc"
cp "$ZLIB_ROOT/include/zlib.h" "$ZLIB_ROOT/include/zconf.h" "$SRC/extra_lib/include/zlib/"
cp "$ZLIB_ROOT/lib/libz.a" "$SRC/extra_lib/lib/gcc/"

cat > /tmp/f1-zlib-probe.c <<'EOF'
#include <string.h>
#include <stdio.h>
#include <zlib.h>
int main(void) {
    if (strcmp(zlibVersion(), ZLIB_VERSION)) return 1;
    puts(zlibVersion());
    return 0;
}
EOF
"$ZLIB_CC" -I"$SRC/extra_lib/include/zlib"   -L"$SRC/extra_lib/lib/gcc"   /tmp/f1-zlib-probe.c -lz -o /tmp/f1-zlib-probe

pushd "$SRC" >/dev/null
export AR RANLIB STRIP
if ! ./configure \
  --target-os=android \
  --cpu=aarch64 \
  --cross-prefix="$CROSS_PREFIX" \
  --cc="$CC" \
  --cxx="$CXX" \
  --extra-cflags="-I$ZLIB_ROOT/include" \
  --extra-ldflags="-L$ZLIB_ROOT/lib" \
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
  --use-zlib="$ZLIB_ROOT" \
  --disable-crypto \
  --disable-compositor \
  --disable-vout \
  --disable-aout \
  --disable-qjs \
  --disable-sdl \
  --disable-x11 \
  --disable-dvb4linux \
  --disable-ffmpeg
then
  cat config.log >&2 || true
  exit 1
fi

make -j"$(nproc)" lib
make install-lib
popd >/dev/null

test -f "$ROOT/lib/libgpac.a"
test -f "$ROOT/include/gpac/filters.h"
echo "GPAC_ROOT=$ROOT"
