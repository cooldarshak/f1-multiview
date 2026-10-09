# Own Multiview Engine — Source Migration and Validation Status

**Branch:** `feature/tiledmedia-multiview-rearchitecture`  
**Updated:** 2026-10-09  
**Build/test status:** Passed on commit `10511e329d165a6fcb6334c9ebf9f5cea738f0b8` (GitHub Actions run [#37923600086](https://github.com/cooldarshak/f1-multiview/actions/runs/37923600086)). The run executed `clean testDebugUnitTest assembleDebug`, verified APK alignment/signature/package metadata, and uploaded the signed APK artifact. No hardware runtime test was performed by CI.

## Non-negotiable implementation boundary

The app's implementation must not contain a Tiledmedia/ClearVR SDK, proprietary metadata parser, vendor-derived session contract, or native merger derived from that proprietary path. The implementation is an original engine using Android public APIs and independently validated open-source components. This document records exclusions; it is not a proprietary implementation blueprint.

## Migration completed at source level

- Removed private multiview metadata fields and parsing from the authorized playback response, playback session, and stream model.
- Changed authorized playback selection to accept the first successful manifest/license profile, while retaining F1 authentication, entitlement headers, manifest preparation, and Widevine license requirements.
- Removed ViewModel discovery/waiting logic and the session/selection model that depended on proprietary metadata.
- Removed old tiled/mosaic backend selection and the native CMAF/HEVC merger pipeline.
- Removed the old tiled UI branches. Existing standard feed selection, layouts, resize handles, controls, audio/subtitle/quality menus where supported, logging/screenshot settings, and TV D-pad/focus UI remain in the standard UI.
- Removed the GPAC/CMake native build and packaging wiring from Gradle and the Android debug workflow.
- Removed obsolete implementation files, scripts, and tests for the old path.
- Reworked production orchestration to **fail visibly closed** for more than one visible video feed until the independent-feed own-engine path has passed runtime validation. It does not silently allocate one ExoPlayer per selected feed. Single-feed authorized Media3 playback remains available.

## Three-feed synthetic rendering proof

The isolated debug prototype uses three visually distinct, clear H.264 clips generated locally with Android's encoder/muxer APIs:

1. Three provider-neutral `FeedDescriptor` instances identify the synthetic inputs.
2. Three independent Android `MediaCodec` decoders decode the clips against one shared monotonic playback anchor.
3. Each decoder outputs to its own `SurfaceTexture`.
4. One EGL/OpenGL ES compositor renders the three textures using normalized `FeedViewport`/`ViewportLayout` values.
5. No F1 account, F1 endpoint, protected content, or DRM license is used by this proof harness.

### Measurements exposed by the prototype

- First texture-frame latency from playback start.
- Per-feed texture update rate, update age, maximum observed update gap, and surface timestamp skew.
- Coalesced frame-available notifications, clearly identified as a surface diagnostic rather than an authoritative dropped-frame count.
- Decoder output frames queued, late presentation deadlines, and codec-reported dropped-frame metrics when the active codec exposes such a metric.
- GLES draw-call cadence and maximum draw gap; these are not display-present FPS.
- Process PSS, Java heap usage, process CPU time as percent of one core, and synthetic decoder thread count.

## Validation status and limitations

- [x] Three clear synthetic inputs are wired to three MediaCodec decoder instances and one GLES compositor in source.
- [x] Diagnostics are exposed in the prototype UI/log.
- [x] Old metadata-dependent production routing and native merger build wiring have been removed from the edited source paths.
- [x] Kotlin/Gradle compilation and JVM unit tests pass (`testDebugUnitTest`; `assembleDebug` — GitHub Actions run #37923600086).
- [x] Signed APK alignment, signature, package metadata, and artifact upload pass (APK SHA-256 `56edfa44654b8bcf9dbd3b43a822f10d1e52d6f58a4effcc12708a984a6bd44e`; artifact `11613361909`).
- [ ] Three-feed runtime is smooth on the target Android phone — requires running the prototype on that device.
- [ ] Three-feed runtime is smooth on Android TV — requires running the prototype on that device.
- [ ] Actual first-frame latency, drift, dropped-frame behavior, PSS/heap/CPU, thermal effects, and sustainable decoder count are measured on both devices.
- [ ] Authorized F1 multi-feed integration is completed — intentionally deferred until the synthetic proof passes on hardware.

A successful cloud build will establish compilation and tests only. It cannot establish decoder capacity, frame pacing, thermal stability, or display smoothness on the user's phone or TV. Do not claim the own engine is production-ready until those runtime checks are recorded.
