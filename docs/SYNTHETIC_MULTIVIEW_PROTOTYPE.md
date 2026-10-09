# Synthetic Multiview Prototype

**Branch:** `feature/tiledmedia-multiview-rearchitecture`  
**Scope:** isolated debug-only clear-content runtime proof. It is not the production F1 playback path.

## What the harness exercises

- Generates three distinct short clear H.264 MP4 clips locally. It does not call F1 APIs, access credentials, use network media, or use DRM.
- Uses one `MediaExtractor` and one Android `MediaCodec` decoder per clip.
- Sends the three decoder outputs to three `SurfaceTexture` inputs owned by one GLES compositor.
- Draws LEFT, CENTER, and RIGHT into normalized one-third viewports. Distinct luma/chroma patterns and moving markers make a missing or swapped input visible.
- Displays per-input texture updates, first-frame latency, update gaps, surface timestamps, draw cadence, decoder metrics, process CPU, PSS and heap use.
- Produces an explicit on-device `WARMING`, `PASS`, or `FAIL` verdict. PASS requires evidence from all three decoder outputs, all three SurfaceTexture inputs, and the compositor.

## Runtime acceptance gate

After at least 15 seconds of playback, the harness requires:

- Exactly LEFT, CENTER, and RIGHT are represented.
- Each decoder configured and queued at least 30 output frames.
- Each corresponding SurfaceTexture observed at least 30 updates and a first frame.
- Each input's most recent update is no older than 1.5 seconds and its maximum observed update gap is at most 2 seconds.
- The compositor recorded at least 60 draw calls, at least 8 draw calls/second, and no draw gap above 2 seconds.
- No feed has reported a decoder or pipeline error.

These are initial diagnostic thresholds, not a universal performance guarantee. A FAIL exposes the failed criterion; errors must not be hidden to manufacture PASS. GLES draw rate is not display-present FPS, and SurfaceTexture updates are not an authoritative decoder-drop metric.

## Lifecycle behavior

Decoder workers are interrupted when stopping to expedite park/sleep exit, then joined before compositor input surfaces are released. A stop timeout is logged and must be investigated. Back/reopen and pause/resume still require on-device validation.

## How to run when device evidence is needed

1. Install the latest validated debug APK.
2. Open Settings and choose **Run Test**.
3. Keep the harness open for at least 20 seconds after the feeds begin.
4. Capture the final verdict and diagnostics, including any `FAIL` lines. Do not infer success from seeing three colors momentarily.
5. Repeat after Back/reopen and pause/resume; test on the Samsung phone and Android TV device when available.

## What this proves and what it does not

A PASS proves that this device sustained three independent **clear synthetic** MediaCodec decode outputs into three SurfaceTexture inputs and one GLES compositor under the stated thresholds. It does not prove authorized F1 stream resolution, Widevine operation, protected-surface composition, live-feed synchronization, or production stability.

The production engine continues to fail closed for more than one protected video feed. No independent ExoPlayer fallback is enabled, and the existing authorized single-feed path remains unchanged.
