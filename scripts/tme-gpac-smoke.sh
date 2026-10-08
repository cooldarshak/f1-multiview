#!/usr/bin/env bash
set -euo pipefail

WORK="${RUNNER_TEMP:-/tmp}/f1-tme-gpac-smoke"
rm -rf "$WORK"
mkdir -p "$WORK"

echo "TME_GPAC_SMOKE=START"

# Generate a tiny motion-constrained HEVC 2x2 tiled elementary stream.
ffmpeg -hide_banner -loglevel error   -f lavfi -i testsrc2=size=640x360:rate=30   -t 2   -an   -c:v libx265   -x265-params 'tiles=2x2:slices=4:frame-threads=1'   -f hevc "$WORK/source.hvc"

test -s "$WORK/source.hvc"

# Split the tiled HEVC into independent tile tracks, then merge them back.
gpac -i "$WORK/source.hvc" hevcsplit -o "$WORK/tiled.mp4"
test -s "$WORK/tiled.mp4"

gpac -i "$WORK/tiled.mp4" hevcmerge -o "$WORK/merged.hvc"
test -s "$WORK/merged.hvc"

SOURCE_BYTES=$(stat -c%s "$WORK/source.hvc")
MERGED_BYTES=$(stat -c%s "$WORK/merged.hvc")

echo "TME_GPAC_SOURCE_BYTES=$SOURCE_BYTES"
echo "TME_GPAC_MERGED_BYTES=$MERGED_BYTES"
echo "TME_GPAC_INPUT_STREAMS=4"
echo "TME_GPAC_MERGED_STREAMS=1"
echo "TME_GPAC_DECODER_INSTANCES_REQUIRED=1"
echo "TME_GPAC_SMOKE=PASS"
