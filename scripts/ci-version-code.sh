#!/usr/bin/env bash
set -euo pipefail

# One deterministic version for every CI provider building the same commit.
# Commit time is monotonic for normal source progression and remains within
# Android's practical versionCode range for the lifetime of this project.
COMMIT_EPOCH="$(git show -s --format=%ct HEAD)"
if ! [[ "$COMMIT_EPOCH" =~ ^[0-9]+$ ]]; then
  echo "Unable to derive versionCode from commit timestamp" >&2
  exit 1
fi

if (( COMMIT_EPOCH <= 0 || COMMIT_EPOCH >= 2100000000 )); then
  echo "Derived versionCode $COMMIT_EPOCH is outside the safe Android range" >&2
  exit 1
fi

echo "$COMMIT_EPOCH"
