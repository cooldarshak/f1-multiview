#!/usr/bin/env bash
set -euo pipefail

APK="${1:?APK path is required}"
BUILD_TOOLS="${ANDROID_HOME}/build-tools/35.0.0"
APKSIGNER="${BUILD_TOOLS}/apksigner"
ZIPALIGN="${BUILD_TOOLS}/zipalign"
AAPT="${BUILD_TOOLS}/aapt"

test -f "$APK"
test -s "$APK"
test -x "$APKSIGNER"
test -x "$ZIPALIGN"
test -x "$AAPT"

echo "=== APK integrity ==="
unzip -t "$APK" >/dev/null
echo "ZIP integrity: OK"

echo "=== APK alignment ==="
"$ZIPALIGN" -c -P 16 -v 4 "$APK"

echo "=== APK signature ==="
"$APKSIGNER" verify --verbose --print-certs "$APK"

echo "=== APK identity ==="
"$AAPT" dump badging "$APK" | grep -E "^(package|sdkVersion|targetSdkVersion):" | head -n 3

echo "=== APK SHA-256 ==="
sha256sum "$APK"

if [[ -n "${CM_KEYSTORE_PATH:-}" && -n "${CM_KEYSTORE_PASSWORD:-}" && -n "${CM_KEY_ALIAS:-}" ]]; then
  EXPECTED="$(
    keytool -list -v -keystore "$CM_KEYSTORE_PATH" -storepass "$CM_KEYSTORE_PASSWORD" -alias "$CM_KEY_ALIAS" |
      awk -F': ' '/SHA256:/{print $2; exit}'
  )"
  ACTUAL="$(
    "$APKSIGNER" verify --print-certs "$APK" |
      awk -F': ' '/Signer #1 certificate SHA-256 digest:/{print $2; exit}'
  )"
  EXPECTED="$(printf '%s' "$EXPECTED" | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]')"
  ACTUAL="$(printf '%s' "$ACTUAL" | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]')"
  test -n "$EXPECTED"
  test -n "$ACTUAL"
  if [[ "$EXPECTED" != "$ACTUAL" ]]; then
    echo "ERROR: APK certificate does not match the CI keystore" >&2
    echo "Expected: $EXPECTED" >&2
    echo "Actual:   $ACTUAL" >&2
    exit 1
  fi
  echo "Signing certificate matches CI keystore: OK"
fi

echo "APK verification: PASS"
