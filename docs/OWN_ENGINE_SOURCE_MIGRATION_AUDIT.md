# Own Multiview Engine — Source Migration Audit

**Branch:** `feature/tiledmedia-multiview-rearchitecture`  
**Audit date:** 2026-10-09  
**Build status:** Not run by this change. No build was triggered.

## Scope and hard exclusions

This audit uses only the repository source, Android public APIs, and the project plan. It does not inspect or infer proprietary SDK internals. Tiledmedia/ClearVR SDKs, private metadata, implementation details, and derived blueprints are excluded. The intended engine is an original implementation based on Android `MediaCodec`, `SurfaceTexture`, EGL/OpenGL ES, public media formats, and independently validated open-source components.

## Findings from the current source

### 1. Production playback is still metadata-dependent

The existing runtime has a chain of dependencies that must be removed before the own engine can own production multiview:

- `ui/App.kt` waits on `ui.tmeDiscoveryPending` before scheduling playback and chooses the high-feed-count UI path based on backend kinds named for the old implementation.
- `viewmodel/MultiViewViewModel.kt` resolves the main stream to obtain a private TME payload, stores a `TiledMultiviewSession`, and selects logical feeds from that payload.
- `data/f1tv/AuthorizedF1TvGateway.kt` continues across authorized playback profiles looking specifically for `tmeJson`, rather than simply selecting a valid authorized manifest and entitlement.
- `media/UnifiedMultiviewEngine.kt` parses `TmePlayback`, selects old TME-named backends, and blocks multiple visible video feeds when the payload is absent.
- `core/playback/TiledMultiviewSession.kt` is currently an adapter around TME-specific data types, not a provider-neutral session.
- `app/build.gradle.kts`, `.github/workflows/android-debug.yml`, `app/src/main/cpp/CMakeLists.txt`, and `scripts/build-gpac-android.sh` still build/package the GPAC experiment.
- TME-specific CMAF/native classes and parser tests remain in the source tree.

This is not acceptable as the final architecture. Renaming these classes would not resolve the dependency.

### 2. The current native merge experiment is not a general independent-camera compositor

The existing native experiment uses a compressed HEVC merge operation. Its own capability check rejects independent F1 feed URLs because separate camera angles are different pictures, not spatial tiles of one picture. The current native build also disables the GPAC compositor/video-output/audio-output components. It must not be treated as a working independent-feed engine or enabled by removing its current gate.

### 3. The provider-neutral contract is only a foundation

- `media/OwnMultiviewEngineContract.kt` defines provider-neutral feed descriptors, normalized viewports/layouts, phases, and per-feed status.
- `media/SessionTimelineSynchronizer.kt` defines a deterministic master/follower correction policy and has unit tests.
- These files do not, by themselves, decode, synchronize, composite, or present video and are not yet the production playback path.

## Synthetic two-feed rendering proof path

The debug-only `SyntheticMultiviewPrototypeActivity` is the first runtime proof harness. It uses no F1 endpoint, account credential, Widevine license, or DRM-protected content.

The current source changes make it a two-feed test:

1. Generate two visually distinct local clear H.264 MP4 fixtures using Android's encoder and muxer APIs.
2. Read both fixtures through `MediaExtractor`.
3. Decode them with two separate Android `MediaCodec` decoder instances.
4. Queue both decoder outputs against one shared monotonic playback anchor.
5. Deliver each decoder to its own `SurfaceTexture` input and draw both textures through one GLES compositor/output surface.

### Metrics now displayed/logged

- Per-feed first texture-frame latency from prototype start.
- Per-feed texture update count/rate, update age, and maximum observed update gap.
- Surface timestamp/PTS skew between the feeds (a diagnostic, not by itself a synchronization verdict).
- Coalesced frame-available notifications. These are not an authoritative count of decoder or display drops.
- Decoder output frames queued and presentation deadlines missed by at least one 15-fps frame interval.
- Codec-reported dropped-frame metric if the active codec exposes a metric containing a dropped-frame counter; otherwise it is explicitly shown as unavailable.
- GLES draw-call cadence and maximum draw gap. This is not display-present FPS.
- Process PSS, Java heap usage, process CPU time as percent of one core, and active synthetic decoder thread count.

The distinction between measured values and estimates is intentional. In particular, texture callback coalescing and late presentation deadlines must not be mislabeled as hardware decoder drop counts. Where Android does not expose an authoritative counter, the UI says so.

## Required migration order

1. Run the two-feed synthetic proof on the target Android phone and Android TV device; record first-frame latency, PTS skew, codec metrics, PSS/heap/CPU, thermal behavior, and whether frames visibly stall or drop.
2. Fix defects in the synthetic decode/render path before routing any F1 stream into it.
3. Replace the production TME discovery/session contract with provider-neutral `FeedDescriptor`, timeline, viewport, and diagnostics types. Remove profile-selection logic that searches for TME metadata.
4. Integrate the own engine with the existing feed selection, main-feed selection, resizing, layouts, audio/control surfaces, subtitles/quality where supported, and TV remote focus/navigation. Keep unsupported operations visible rather than silently falling back.
5. Only then test authorized F1 feeds through the ordinary F1 sign-in, entitlement, manifest, and Widevine path. A clear synthetic proof does not prove that protected output or F1 stream topology is compatible.
6. Measure the actual decoder capacity on each target device. A single logical session or compositor does not imply one physical decoder; independent encoded feeds may need separate decoder instances.
7. Remove the obsolete TME-specific source, native library, build wiring, scripts, and tests as part of the production migration. Do not claim this removal is complete until source and dependency scans confirm it.

## Current acceptance status

- [x] Isolated clear synthetic fixtures and MediaCodec decode path exist.
- [x] One GLES output compositor consumes independent decoder surfaces.
- [x] Prototype source updated from three feeds to two feeds.
- [x] Added explicit timing, texture, process-resource, and codec-metric diagnostics.
- [ ] Build and JVM tests verified — intentionally not run because builds require explicit user approval.
- [ ] Runtime proof on the target phone and Android TV verified.
- [ ] Production TME-dependent control flow removed.
- [ ] Own engine integrated with production feed UI and authorized F1 feeds.
- [ ] Real-device decoder capacity, drift, dropped-frame behavior, and DRM output validated.

This audit records progress and remaining work; it does not claim the production architecture is corrected or the prototype has passed runtime validation.
