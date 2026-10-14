"""Build one HEVC tiled video from 2-4 locally readable, clear video files.

This is an upstream encoder prototype. It never handles F1 credentials, license
responses, encrypted samples, or protected pixels. FFmpeg decodes and aligns
the inputs; one Kvazaar encoder emits one HEVC stream with independent tiles.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
from pathlib import Path


def grid(count: int) -> tuple[int, int]:
    if count == 2:
        return 2, 1
    if count == 3:
        return 3, 1
    if count == 4:
        return 2, 2
    raise ValueError("Exactly 2, 3, or 4 video inputs are required")


def filter_graph(count: int, tile_width: int, tile_height: int, fps: int) -> str:
    columns, _ = grid(count)
    normalized = [
        f"[{i}:v]fps={fps},scale={tile_width}:{tile_height}:flags=lanczos,"
        f"setsar=1,setpts=PTS-STARTPTS[v{i}]"
        for i in range(count)
    ]
    positions = "|".join(
        f"{i % columns * tile_width}_{i // columns * tile_height}"
        for i in range(count)
    )
    inputs = "".join(f"[v{i}]" for i in range(count))
    return ";".join(normalized + [
        f"{inputs}xstack=inputs={count}:layout={positions}:shortest=1,format=yuv420p[out]"
    ])


def run(command: list[str]) -> None:
    subprocess.run(command, check=True)


def build(args: argparse.Namespace) -> dict[str, object]:
    columns, rows = grid(len(args.inputs))
    if args.tile_width < 128 or args.tile_height < 128 or args.fps < 1:
        raise ValueError("Tile dimensions must be at least 128 pixels and fps must be positive")
    if args.tile_width % 64 or args.tile_height % 64:
        raise ValueError("Tile dimensions must be multiples of 64 HEVC CTU pixels")
    inputs = [Path(value).resolve(strict=True) for value in args.inputs]
    if any(not path.is_file() for path in inputs):
        raise ValueError("Every input must be a local video file")
    output = Path(args.output).resolve()
    if output.suffix.lower() != ".mp4" or output in inputs:
        raise ValueError("Output must be a new MP4 path, distinct from every input")
    output.parent.mkdir(parents=True, exist_ok=True)
    width, height = columns * args.tile_width, rows * args.tile_height

    with tempfile.TemporaryDirectory(prefix="owned-hevc-tiles-") as temp:
        raw = Path(temp) / "aligned.yuv"
        hevc = Path(temp) / "tiled.hevc"
        decode = [args.ffmpeg, "-hide_banner", "-loglevel", "error", "-y"]
        for source in inputs:
            decode += ["-i", str(source)]
        decode += ["-filter_complex", filter_graph(len(inputs), args.tile_width, args.tile_height, args.fps),
                   "-map", "[out]", "-an"]
        if args.frames is not None:
            decode += ["-frames:v", str(args.frames)]
        decode += ["-pix_fmt", "yuv420p", "-f", "rawvideo", str(raw)]
        run(decode)
        if raw.stat().st_size == 0:
            raise RuntimeError("No aligned video frames were produced")

        # Kvazaar is the only encoder in this pipeline. Motion vectors remain
        # inside each independently coded HEVC tile.
        run([args.kvazaar, "-i", str(raw), "--input-res", f"{width}x{height}",
             "--input-fps", str(args.fps), "--tiles", f"{columns}x{rows}",
             "--slices", "tiles", "--mv-constraint", "frametile",
             # Kvazaar's default eight-picture B GOP requires an aligned intra period.
             "--period", str(((args.fps * 2 + 7) // 8) * 8),
             # Reject settings beyond this device-oriented level instead of
             # signalling Kvazaar's permissive default HEVC level 6.2.
             "--level", "4.1",
             "--preset", "ultrafast", "-o", str(hevc)])
        run([args.ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
             "-r", str(args.fps), "-i", str(hevc), "-map", "0:v:0", "-c:v", "copy",
             "-tag:v", "hvc1", "-movflags", "+faststart", str(output)])

    probe = subprocess.run(
        [args.ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries",
         "stream=codec_name,width,height,nb_frames", "-of", "json", str(output)],
        check=True, capture_output=True, text=True,
    )
    streams = json.loads(probe.stdout).get("streams", [])
    if len(streams) != 1 or streams[0].get("codec_name") != "hevc" or \
            streams[0].get("width") != width or streams[0].get("height") != height:
        raise RuntimeError("Output does not contain the expected single HEVC video stream")
    return {"output": str(output), "video_streams": 1, "encoder_instances": 1,
            "tile_columns": columns, "tile_rows": rows, "width": width,
            "height": height, "frames": streams[0].get("nb_frames")}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", help="Two to four locally readable, clear video files")
    parser.add_argument("-o", "--output", required=True)
    parser.add_argument("--tile-width", type=int, default=320)
    parser.add_argument("--tile-height", type=int, default=192)
    parser.add_argument("--fps", type=int, default=25)
    parser.add_argument("--frames", type=int, default=None)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    parser.add_argument("--kvazaar", default="kvazaar")
    args = parser.parse_args()
    if args.frames is not None and args.frames < 1:
        parser.error("--frames must be positive")
    try:
        print(json.dumps(build(args), indent=2))
    except (ValueError, RuntimeError, subprocess.CalledProcessError, FileNotFoundError) as failure:
        parser.exit(1, f"Tile stream build failed: {failure}\n")


if __name__ == "__main__":
    main()
