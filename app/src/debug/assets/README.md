# Clear HEVC tile fixture

`single-decoder-hevc-tile-proof.mp4` is a 9,277-byte, 320×320, 12-frame clear fixture.
It was made from two independently generated 320×160 HEVC streams using the MIT
licensed [Eyevinn/hi265](https://github.com/Eyevinn/hi265) tools at commit
`c146091d98323071785a76dac2878a3217d99d99`. `hi265retile -verify`
reported pixel-perfect output for both source tiles. The raw merged HEVC stream
was remuxed to MP4 at 25 fps using FFmpeg. SHA-256 of this MP4:
`4dad9dac30cc0d5873730152e7457fd3e79dfc46d875d263e4a46751e6a47d26`.

The fixture is deliberately short and contains no F1 media. Its debug activity
shows whether Media3 initialized a video decoder and rendered a frame on the
device. It does not establish protected playback, live stream joining, or a
production feed source.
