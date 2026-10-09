# Synthetic Multiview Prototype

**Branch:** `feature/tiledmedia-multiview-rearchitecture`  
**Status:** implementation draft; no build, unit test, or device run has been performed.

## Scope

This isolated debug harness tests client-side composition of two independent, clear video feeds. It does not call F1 APIs, use F1 credentials, or handle DRM-protected content. It is separate from the production playback path and must not remove or replace existing resize/layout behavior.

## Added implementation

- `SyntheticMultiviewClipFactory.kt` generates two local H.264 MP4 clips using Android's encoder API. Their luma patterns and moving markers differ so the output can demonstrate that both sources are present.
- `SyntheticFeedDecoder.kt` owns one `MediaExtractor` and one `MediaCodec` decoder per clip, targets a supplied Surface, paces frames using presentation timestamps, loops the clip, and emits diagnostic status.
- `IndependentFeedCompositorView.kt` owns one GLES view/context and two `SurfaceTexture` inputs, draws each decoder output in a side-by-side viewport, and counts render ticks.
- `SyntheticMultiviewPrototypeActivity.kt` generates the clips, starts both decoders, and displays status.
- The manifest registers the activity as internal/non-exported and landscape-only.
- Debug Settings exposes a “RUN TEST” entry. The regular playback screen and resizable layouts remain untouched.
- Surface lifecycle handling notifies decoder owners before releasing their output Surfaces.

## Implementation commits

- `e63c0473` — local synthetic H.264 fixture generator
- `f88c05e1` — independent-feed GLES compositor
- `cef540ef` — MediaExtractor/MediaCodec decoder
- `7ca0bcb7` — isolated prototype activity
- `94c40bc5` — debug Settings entry

## Required validation

1. Ask for approval before triggering a build.
2. Verify workflow status, unit tests, `assembleDebug`, APK signature, and artifact upload.
3. Run Settings → Run Test. Confirm both clips are generated locally with no network access.
4. Confirm both decoder names are reported, both feeds produce frames, both distinct patterns are visible, and the render counter advances.
5. Stress Back/reopen and pause/resume; verify no crash, stale surface, or leaked decoder.
6. Capture first-frame latency, frame pacing, dropped frames, CPU/memory, and thermal behavior on the target Samsung phone and Android TV device.
7. Only after this clear-content proof, compare GPAC and/or GStreamer. A successful synthetic test does not prove authorized F1 feed compatibility, live synchronization, DRM-secure composition, or production performance.

## Risks not yet resolved by runtime evidence

- AVC encoder support for flexible YUV input and 320×180 varies by device. Unsupported fixture generation must fail visibly.
- EGL/SurfaceTexture recreation and decoder shutdown ordering require runtime stress tests.
- This prototype currently uses two equal-width viewports; it is not the final resizable layout engine.
- No performance conclusions are valid until tested on target hardware.
