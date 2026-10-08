#!/usr/bin/env bash
set -euo pipefail
GPAC_VERSION="${GPAC_VERSION:-v26.07.0}"
ANDROID_API="${ANDROID_API:-29}"
ABI="${ANDROID_ABI:-arm64-v8a}"
ROOT="${1:-${PWD}/.build/gpac/${ABI}}"
SRC="${ROOT}/src"
BUILD="${ROOT}/build"
PREFIX="${ROOT}/prefix"
: "${ANDROID_NDK_HOME:=${ANDROID_NDK_ROOT:-}}"
if [[ -z "${ANDROID_NDK_HOME}" ]]; then
  echo "ANDROID_NDK_HOME/ANDROID_NDK_ROOT is required" >&2
  exit 2
fi
case "${ABI}" in
  arm64-v8a) TARGET="aarch64-linux-android"; CPU="arm64" ;;
  *) echo "This pinned TME build currently supports ARM64 only; got ${ABI}" >&2; exit 2 ;;
esac
TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="${TOOLCHAIN}/clang --target=${TARGET}${ANDROID_API}"
CXX="${TOOLCHAIN}/clang++ --target=${TARGET}${ANDROID_API}"
AR="${TOOLCHAIN}/llvm-ar"
RANLIB="${TOOLCHAIN}/llvm-ranlib"
STRIP="${TOOLCHAIN}/llvm-strip"
mkdir -p "${ROOT}"
if [[ ! -d "${SRC}/.git" ]]; then
  git clone --depth 1 --branch "${GPAC_VERSION}" https://github.com/gpac/gpac.git "${SRC}"
fi
rm -rf "${BUILD}" "${PREFIX}"
mkdir -p "${BUILD}" "${PREFIX}"
cd "${BUILD}"
"${SRC}/configure"   --prefix="${PREFIX}" --target-os=android --cpu="${CPU}"   --cc="${CC}" --cxx="${CXX}" --ar="${AR}" --ranlib="${RANLIB}" --strip="${STRIP}"   --static-modules --static-build --disable-ssl --disable-x11 --disable-alsa   --disable-oss-audio --disable-pulseaudio --disable-sdl --disable-opengl   --disable-player --disable-3d --disable-svg --disable-vrml --disable-x3d   --disable-dvb4linux --disable-dash --disable-mpd --disable-streaming   --disable-import --disable-export --disable-crypto --enable-pic
make -j"${GPAC_MAKE_JOBS:-2}"
make install
test -f "${PREFIX}/lib/libgpac.a"
test -f "${PREFIX}/include/gpac/filters.h"
echo "GPAC_VERSION=${GPAC_VERSION}"
echo "GPAC_ABI=${ABI}"
echo "GPAC_PREFIX=${PREFIX}"
