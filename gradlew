#!/usr/bin/env sh
set -eu
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
GRADLE_VERSION=8.10.2
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/f1multiview-wrapper"
ZIP="$BASE/gradle-${GRADLE_VERSION}-bin.zip"
DIST="$BASE/gradle-${GRADLE_VERSION}"
if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$BASE"
  if [ ! -f "$ZIP" ]; then
    curl -fsSL -o "$ZIP" "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  fi
  rm -rf "$DIST.tmp"
  mkdir -p "$DIST.tmp"
  unzip -q "$ZIP" -d "$DIST.tmp"
  mv "$DIST.tmp/gradle-${GRADLE_VERSION}" "$DIST"
  rm -rf "$DIST.tmp"
fi
exec "$DIST/bin/gradle" "$@"
