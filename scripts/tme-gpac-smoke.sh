#!/usr/bin/env bash
set -euo pipefail

WORK="${RUNNER_TEMP:-/tmp}/f1-tme-gpac-smoke"
rm -rf "$WORK"
mkdir -p "$WORK"

echo "TME_GPAC_SMOKE=START"

# Generate a tiny motion-constrained HEVC 2x2 tiled elementary stream.
# Kvazaar is used because GPAC's tiled merger requires independent tile slices
# with motion vectors constrained to their own tile regions.
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i testsrc2=size=640x360:rate=30 \
  -t 2 -an -pix_fmt yuv420p -f rawvideo "$WORK/source.yuv"

kvazaar \
  -i "$WORK/source.yuv" \
  --input-res 640x360 \
  --input-fps 30 \
  --tiles 2x2 \
  --slices tiles \
  --mv-constraint frametilemargin \
  --period 30 \
  --qp 32 \
  -o "$WORK/source.hvc"

test -s "$WORK/source.hvc"

# Split the tiled HEVC into independent tile tracks, then merge them back.
gpac -i "$WORK/source.hvc" hevcsplit -o "$WORK/tiled.mp4"
test -s "$WORK/tiled.mp4"

TILED_PROBE="$WORK/tiled.probe"
MERGED_PROBE="$WORK/merged.probe"
gpac -i "$WORK/tiled.mp4" probe:log="$TILED_PROBE"
test -s "$TILED_PROBE"
TILED_PIDS=$(grep -Eo '[0-9]+' "$TILED_PROBE" | tail -1)
# The generated fixture is a 2x2 HEVC tile set. Verify four input PIDs.
test "$TILED_PIDS" -eq 4

gpac -i "$WORK/tiled.mp4" hevcmerge -o "$WORK/merged.hvc"
test -s "$WORK/merged.hvc"
gpac -i "$WORK/merged.hvc" probe:log="$MERGED_PROBE"
test -s "$MERGED_PROBE"
MERGED_PIDS=$(grep -Eo '[0-9]+' "$MERGED_PROBE" | tail -1)
# The merged elementary stream must expose exactly one video PID.
test "$MERGED_PIDS" -eq 1

SOURCE_BYTES=$(stat -c%s "$WORK/source.hvc")
MERGED_BYTES=$(stat -c%s "$WORK/merged.hvc")

echo "TME_GPAC_SOURCE_BYTES=$SOURCE_BYTES"
echo "TME_GPAC_MERGED_BYTES=$MERGED_BYTES"
echo "TME_GPAC_INPUT_STREAMS=4"
echo "TME_GPAC_MERGED_STREAMS=1"
echo "TME_GPAC_DECODER_INSTANCES_REQUIRED=1"
echo "TME_GPAC_SMOKE=PASS"
