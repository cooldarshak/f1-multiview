#!/usr/bin/env bash
set -euo pipefail

# Local-only TME validation gate.
# This script deliberately does NOT invoke GitHub Actions, gh, git push, PR creation,
# or any remote mutation. It is safe to run repeatedly during Copilot agent work.

EXPECTED_BRANCH="work/tme-native-complete"
FORBIDDEN_BRANCHES=("main" "feature/tiledmedia-multiview-rearchitecture")

fail() {
  echo "LOCAL_TME_VALIDATE: FAIL: $*" >&2
  exit 1
}

command -v git >/dev/null 2>&1 || fail "git is required"
BRANCH="$(git branch --show-current)"
[[ "$BRANCH" == "$EXPECTED_BRANCH" ]] || fail "must run on $EXPECTED_BRANCH; current branch is '$BRANCH'"

for forbidden in "${FORBIDDEN_BRANCHES[@]}"; do
  [[ "$BRANCH" != "$forbidden" ]] || fail "refusing to run on protected branch '$forbidden'"
done

if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
  fail "refusing to run inside GitHub Actions"
fi

if git diff --quiet --exit-code && git diff --cached --quiet --exit-code; then
  echo "LOCAL_TME_VALIDATE: working tree clean"
else
  echo "LOCAL_TME_VALIDATE: uncommitted changes detected; validation will test the current working tree"
fi

echo "LOCAL_TME_VALIDATE: branch=$BRANCH"
echo "LOCAL_TME_VALIDATE: CI=disabled"
echo "LOCAL_TME_VALIDATE: remote mutation=disabled"

if [[ "${SKIP_GPAC_SMOKE:-0}" != "1" ]]; then
  if command -v gpac >/dev/null 2>&1 && command -v ffmpeg >/dev/null 2>&1 && command -v kvazaar >/dev/null 2>&1; then
    chmod +x scripts/tme-gpac-smoke.sh
    scripts/tme-gpac-smoke.sh
  else
    echo "LOCAL_TME_VALIDATE: GPAC/Kvazaar smoke prerequisites unavailable; skipping smoke test."
    echo "Set up gpac, ffmpeg and kvazaar to run the compressed-domain smoke test."
  fi
else
  echo "LOCAL_TME_VALIDATE: GPAC smoke explicitly skipped via SKIP_GPAC_SMOKE=1"
fi

if [[ -z "${ANDROID_NDK_HOME:-}" && -z "${ANDROID_NDK:-}" ]]; then
  echo "LOCAL_TME_VALIDATE: ANDROID_NDK_HOME/ANDROID_NDK is not set; native Android build may fail."
fi

[[ -x ./gradlew ]] || chmod +x ./gradlew
./gradlew --no-daemon testDebugUnitTest assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
[[ -s "$APK" ]] || fail "debug APK not produced: $APK"

if command -v unzip >/dev/null 2>&1; then
  unzip -l "$APK" | grep -q 'lib/arm64-v8a/libf1tme.so' \
    || fail "APK does not contain lib/arm64-v8a/libf1tme.so"
fi

echo "LOCAL_TME_VALIDATE: PASS"
echo "APK=$APK"
