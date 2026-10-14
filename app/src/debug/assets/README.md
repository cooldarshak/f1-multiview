# Clear HEVC tile fixture

`single-decoder-hevc-tile-proof.mp4` is a 3,418-byte, 640×192, 12-frame clear fixture.
The local `tools/build_owned_tile_stream.py` pipeline decoded two independently
generated HEVC inputs, aligned their frames, and encoded the resulting picture
with **one Kvazaar HEVC encoder** configured for two independent tiles. FFmpeg
remuxed that single coded stream to MP4. `hi265inspect` confirmed HEVC PPS
`tiles=true` and two slice segments per picture. SHA-256 of this MP4:
`0d94a5100a85184d63447dd58164a7ea8a7576058712a259e6baf42429de8ca3`.

The fixture is deliberately short and contains no F1 media. Its debug activity
shows whether Media3 initialized a video decoder and rendered a frame on the
device. The pipeline currently accepts only local clear inputs. It does not
establish protected playback, live stream joining, or a production feed source.
