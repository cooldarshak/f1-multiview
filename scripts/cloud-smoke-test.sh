#!/usr/bin/env bash
set -euo pipefail

APK="$GITHUB_WORKSPACE/artifacts/f1-multiview-debug.apk"
OUT="$GITHUB_WORKSPACE/test-artifacts"
PKG="app.f1multiview"
ACTIVITY="$PKG/.MainActivity"

mkdir -p "$OUT"

cleanup() {
  set +e
  adb logcat -d -v threadtime > "$OUT/logcat.txt"
  adb shell dumpsys activity activities > "$OUT/activity.txt"
  adb shell dumpsys meminfo "$PKG" > "$OUT/meminfo.txt"
  adb exec-out screencap -p > "$OUT/final.png"
}
trap cleanup EXIT

adb install -r "$APK"
adb shell am force-stop "$PKG"
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 8

PID="$(adb shell pidof "$PKG" | tr -d '' || true)"
test -n "$PID"

adb shell dumpsys activity activities | grep -q "$PKG"
adb exec-out screencap -p > "$OUT/launch.png"

sleep 10
PID_AFTER="$(adb shell pidof "$PKG" | tr -d '' || true)"
test -n "$PID_AFTER"

adb logcat -d -v threadtime > "$OUT/logcat-precheck.txt"

if grep -Eiq 'FATAL EXCEPTION|AndroidRuntime: FATAL|Process: '"$PKG"'.*, PID:.*\n.*FATAL' "$OUT/logcat-precheck.txt"; then
  echo "Fatal Android exception detected" >&2
  exit 1
fi

echo "Cloud smoke test passed: APK installed, launcher activity started, process remained alive for 18s."
