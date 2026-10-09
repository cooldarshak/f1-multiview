# F1 MultiView: Own Multiview Engine Plan

**Target branch:** `feature/tiledmedia-multiview-rearchitecture`  
**Direction:** Build an original multiview engine from open standards and open-source components. Tiledmedia/ClearVR is explicitly out of scope: no SDK, no proprietary metadata dependency, no proprietary implementation study, and no Tiledmedia-derived design blueprint.

## 1. Findings that change the plan

### Explicit exclusion: Tiledmedia/ClearVR

Do not inspect, reuse, reverse-engineer, or design around the Tiledmedia/ClearVR assets in the official APK. Do not search for its private metadata, attempt to reproduce its internal SDK contracts, or make TME discovery a project milestone. The official APK is not an input to our architecture work. Use public Android APIs, open standards, and independently maintained open-source projects instead.

### Existing branch implementation

The branch already has a GPAC-backed native experiment:
- `app/src/main/cpp/f1tme_jni.cpp` creates a GPAC `hevcmerge` filter graph.
- Its inputs are declared as HEVC, marked as mergeable, and assigned crop/tile positions.
- The Kotlin boundary accepts `TmeTileSource` and `TmeAlignedSegment` values.
- The current source contract therefore expects already-aligned HEVC access units and tile metadata. It is not yet a general-purpose engine that can take arbitrary independent F1 camera manifests and turn them into a tiled stream.
- The current API-discovery gate means the native experiment does not start when F1's ordinary playback response lacks TME metadata.

**Do not simply remove the gate and feed arbitrary camera streams into `hevcmerge`.** GPAC documents `hevcmerge` as a merger for spatial tiles of one motion-constrained HEVC video: inputs must be synchronised and their codec parameter sets/tile geometry must be compatible. Separate onboard camera angles are different pictures, not spatial tiles of one picture.

A second important finding: `scripts/build-gpac-android.sh` currently enables the HEVC tile filters but explicitly disables GPAC's `compositor`, `vout`, and `aout`. Therefore the current native GPAC build cannot yet serve as a general decoded-video mosaic engine. That is a build/configuration decision we must address deliberately, not assume away.

## 2. GitHub research: what we can reuse

### A. F1OpenViewer — MIT, useful for F1 integration and sync behavior
Repository: https://github.com/npanu420/F1OpenViewer

Useful references:
- In-house F1 TV API client and playback-resolution separation.
- Master-feed synchronization: small drift is corrected gradually; larger drift is corrected by seeking.
- Resizable layouts and feed-selection UX.
- The project is Electron/React with Shaka Player, so its whole playback stack is not a drop-in Android engine. Reuse or port only the specific MIT-licensed modules that fit, preserving attribution and the MIT license notice.

### B. F1AppleTV — GPL-3.0, strong feature/edge-case reference
Repository: https://github.com/NoahFetz/F1AppleTV

Useful references:
- A mature F1 TV feature set: simultaneous feeds, layout selection, saved setups, main-feed selection, muted followers, and TV-remote-oriented interaction patterns.
- Its current playback implementation creates a separate AVPlayer for each feed and synchronizes followers by seeking them to the reference player. That makes it useful for UX, entitlement lifecycle, cancellation, and sync edge cases, but **not evidence of a single-decoder or single-compositor engine**.
- The repository is GPL-3.0. Treat it as a behavioral/architectural reference; do not copy its code into our app unless we explicitly decide to adopt GPL-compatible distribution obligations.

### C. Race Control — GPL-3.0, F1 playback and sync reference
Repository: https://github.com/robvdpol/RaceControl

Useful references:
- F1 feed/catalog handling, user-controlled layouts, player controls, and its experimental multi-stream synchronization.
- It supports multiple external/internal player implementations, so it is not a drop-in Android engine. Its GPL-3.0 license also means we should study behavior rather than transplant code unless we intentionally accept the license obligations.

### D. Carbon for F1TV — browser sync reference
Repository: https://github.com/Carbon-for-F1TV/Carbon-for-F1TV

Useful reference:
- Synchronization behavior and live-stream edge cases across simultaneous F1 TV streams. It is a browser extension/userscript, not a native decoder/compositor, so use it to inform synchronization tests rather than media architecture.

### E. NexPlayer MultiView public documentation — constraints only, no SDK dependency
Documentation: https://github.com/NexPlayer/NexPlayer_Multiview

We will not use its proprietary SDK. Its public stream requirements are nevertheless useful general engineering guidance: aligned timestamps/period starts for DASH, EXT-X-PROGRAM-DATE-TIME for HLS, adequate live presentation delay, and lower-resolution renditions for smaller views. These are input/stream properties to verify, not guarantees that our F1 feeds satisfy them.

### F. ChromeOS VideoDecodeEncodeDemo — open-source proof of concept

Repository: https://github.com/chromeos/video-decode-encode-demo

Useful reference:
- Multiple video decode pipelines feeding SurfaceTexture/OpenGL.
- Frame timestamp bookkeeping and coordinated rendering.
- Shared audio timeline/mixing concepts.
- The README reports four 1080p streams in its tested environment, but explicitly labels the project a proof of concept, not production-ready. This is a starting reference, not a performance guarantee for our Samsung phone or Android TV.

### C. Android MediaCodec / SurfaceTexture / OpenGL samples
Reference: https://github.com/PhilLab/Android-MediaCodec-Examples

Useful for understanding the native decode-to-texture-to-compositor path. We must test actual decoder capacity and thermal behavior on target hardware.

### D. GPAC — already used in this branch
Repository: https://github.com/gpac/gpac

Relevant documentation:
- HEVC tile merger: https://github.com/gpac/gpac/wiki/hevcmerge
- Compositor, including `mosaic://` multi-source input: https://github.com/gpac/gpac/wiki/compositor
- DASH/HLS input filter: https://github.com/gpac/gpac/wiki/dashin

GPAC gives us two distinct candidates: `hevcmerge` for genuinely compatible spatial HEVC tiles, and a single GPAC filter session using multiple media inputs plus the compositor for independent camera feeds. The latter is promising but is **not enabled in our current Android build**; we must build it and prove its Android output-surface, timing, and performance behavior before relying on it.

### Research conclusion

We are not looking for a drop-in equivalent to Tiledmedia or attempting to clone its internals. We are building the app's required multiview capabilities from public Android media APIs, open standards, and reusable open-source components. The engine must be independently specified and tested.

## 2A. Implementation progress (2026-10-09)

The first provider-neutral foundation has been committed to the active branch:
- `app/src/main/java/app/f1multiview/media/OwnMultiviewEngineContract.kt` defines feed descriptors, normalized viewports/layouts, explicit engine phases, per-feed status, and a shared session timeline without TME types.
- `app/src/main/java/app/f1multiview/media/SessionTimelineSynchronizer.kt` defines a deterministic master/follower correction policy: hold followers when the master buffers, resume engine-paused followers, use rate correction for small drift, and seek for large drift with separate VOD/live thresholds.
- `app/src/test/java/app/f1multiview/media/SessionTimelineSynchronizerTest.kt` contains eight JVM unit tests for these decisions.

These files are an initial contract/policy layer, **not yet a functioning video compositor**. They are not wired into the existing playback path; no build or test run has been triggered. The next implementation milestone is a clear synthetic two-feed MediaCodec-to-SurfaceTexture/OpenGL prototype with measurable first-frame, drift, and frame-drop diagnostics. Only after that proof should the new engine replace the TME-dependent gate.

## 2B. Broader web research (Firecrawl + public technical documentation)

This research extends beyond GitHub repository discovery. Findings below are based on the linked public documentation and project READMEs, not on any proprietary multiview SDK.

### Native Android media/rendering references

**Android MediaCodec API**
- Official reference: https://developer.android.com/reference/android/media/MediaCodec
- Android documents MediaCodec as an asynchronous codec pipeline that consumes and produces buffers/surfaces. It gives us the low-level primitives, but does not promise that a given handset can decode any arbitrary number of concurrent high-resolution streams.
- Android's codec performance-point documentation explicitly cautions that performance points assume a single active codec; concurrent codecs need a more conservative aggregate pixel-rate assessment. Query the device's advertised codec capabilities and validate by measurement on target hardware; do not hard-code a decoder count.
- Source: https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities.PerformancePoint

**SurfaceTexture and protected rendering**
- Official architecture guide: https://source.android.com/docs/core/graphics/arch-st
- SurfaceTexture connects a producer Surface to a GLES external texture and exposes frame-available notifications and timestamp/transform information. This is the main public Android primitive for a custom decoded-frame compositor.
- Protected playback has specific protected EGL/Surface constraints; our protected path must be independently validated and must never copy protected pixels into CPU-readable memory.

**Google Grafika — Apache-2.0, archived sample**
- Repository: https://github.com/google/grafika
- Its documented examples include two-video side-by-side playback, SurfaceTexture, MediaCodec, EGL, and presentation timing. It is archived and explicitly says it is an experimental collection rather than a stable, production-ready library. Use its relevant examples as learning/reference material; don't import it as a finished engine.

**GPAC compositor**
- Current docs: https://wiki.gpac.io/Filters/compositor/
- The compositor can consume multiple URL types and has a `mosaic://` multi-view input syntax. It can produce frames in filter mode and can use OpenGL for 3D/display-driver paths.
- This makes GPAC a real prototype candidate for independent feeds, separate from `hevcmerge`. It still needs a proof of Android integration: DASH/HLS input support, decoder path, EGL/output-surface handoff, timestamps/live buffering, and protected output compatibility.
- Our existing GPAC Android build disables compositor/vout/aout, so this is a new build configuration and integration effort, not something already available in the current native library.

**GStreamer glvideomixer**
- Documentation: https://gstreamer.freedesktop.org/documentation/opengl/glvideomixer.html
- GStreamer provides an OpenGL-backed multi-input compositor and an Android deployment guide: https://gstreamer.freedesktop.org/documentation/installing/for-android-development.html
- It is a credible third-party open-source prototype candidate, but requires a significant Android native packaging/dependency effort. We must verify that the selected decode elements really use device hardware decoders, that frames stay GPU-resident through composition, and that the chosen plugins/licenses suit distribution. A compositor existing in a framework does not itself guarantee low-copy or hardware-efficient composition.

### Real-world multiview product and synchronization research

**MultiViewer for F1 (closed source; behavior/documentation only)**
- Sync guide: https://multiviewer.app/docs/usage/syncing-streams
- Public documentation describes per-stream target live latencies, periodic drift measurement, playback-rate corrections, and seeking when drift becomes very large. It also notes that live streams may need tens of seconds of target latency for buffer resilience, and onboard streams may be offset from the world feed.
- This is useful as observable product behavior and as a source of test scenarios; it is closed source and not a code dependency or design blueprint to copy. We must calibrate our own policy against actual authorized feed timestamps and runtime evidence.

**Open Android TV IPTV multiview apps**
- StreamVault: https://github.com/Davidona/StreamVault-IPTV
- AerioTV for Android: https://github.com/jonzey231/AerioTV-Android
- OwnTV: https://github.com/ahXN00/OwnTV
- These demonstrate production-shaped TV UI patterns for multi-tile selection, one audio focus, per-tile menus, D-pad navigation, diagnostics, thermal/resource watchdogs, and graceful handling of many feeds. They are not F1-specific and their multiview implementation should be inspected before borrowing any code; feature descriptions alone do not prove a single physical playback pipeline. Respect each repository's license before any code reuse.

**Apple's multiview engineering session (conceptual reference only)**
- https://developer.apple.com/videos/play/wwdc2025/302/
- Covers coordinating playback between multiple streams and optimizing stream quality for smaller views. The APIs are Apple-specific, but the product/coordination concerns generalize: choose quality by viewport size, define a common playback target, recover after stalls, and manage audio focus. This does not prove that a multiple-player approach will meet our Android performance target.

### Updated candidate comparison

| Candidate | What it can prove | Main risk / unknown | Decision |
| --- | --- | --- | --- |
| Custom MediaCodec + SurfaceTexture + OpenGL ES | Full control of decode scheduling, frame timestamps, one app-owned compositor, diagnostics and viewport resizing | Highest implementation burden; codec limits, lifecycle, live demux and secure surfaces are difficult | Required reference implementation / fallback if frameworks fail |
| GPAC multi-input graph + compositor | One media graph/session with standard media inputs and a compositor | Android build/output surface, hardware decode and protected playback are not proven | Prototype first alongside the custom path |
| GStreamer + glvideomixer | Mature multi-input media graph and GL compositor | Native package size/dependencies, device-specific decoder elements, low-copy behavior and DRM surface integration | Third candidate if GPAC integration fails or GStreamer offers a measurable advantage |
| HEVC compressed-domain merge | Potentially one encoded output and one decode for compatible spatial tiles | Independent onboard cameras are not spatial tiles of one picture; source compatibility not established | Use only for inputs that objectively satisfy the HEVC tiled-stream contract |
| N separate ExoPlayers/MediaPlayers | Fastest way to show separate sources, useful for a baseline comparison only | Decoder contention, synchronization and jank; violates the intended architecture if used as the production fallback | Not an acceptable production workaround |

### Engineering conclusions from broader research
1. Do not assume a single logical session means one physical decoder. Keep session ownership, decoding, and composition as separately measured concepts.
2. Build a benchmark that records per-codec dimensions/rate, number of active hardware decoders, first-frame time, compositor frame time, dropped frames, queue depth, drift, memory, CPU/GPU and thermal behavior.
3. Include a viewport-aware quality policy: a small tile should not automatically request the same rendition as the main feed if the authorized stream catalog offers lower-resolution variants. Do not assume a rendition exists; inspect the actual manifest.
4. Live sync needs a target-latency model in addition to PTS drift correction. Streams can have different live edges or encoder offsets even when each player's local position looks valid. Derive alignment from trustworthy timestamps or calibrated content events, not wall-clock guessing.
5. Use a controlled bake-off with two known-clear synthetic feeds before F1 integration: (a) GPAC compositor graph, (b) custom MediaCodec/SurfaceTexture/OpenGL, and optionally (c) GStreamer. Choose on evidence. A successful library build alone is not sufficient.

## 3. Target architecture

The app exposes **one logical multiview player/session**. It must not create independent ExoPlayer instances as a workaround. Internally, the engine may need multiple decoder instances when the input consists of independent camera feeds; a single coordinator does not magically turn separate encoded streams into one decoder input.

Proposed components:

1. **FeedCatalog / FeedSourceResolver** — resolve each selected feed through the existing authorized F1 flow; produce a typed feed descriptor. Do not require TME metadata to represent a feed.
2. **FeedPipeline** — own manifest/demux/sample acquisition and bounded queues for one input. Keep this behind an interface so the implementation can be tested with clear synthetic samples before protected playback is integrated.
3. **TimelineClock** — use the main/world feed as the master clock; map each feed's presentation timestamps to the same session timeline.
4. **FrameSynchronizer** — continuously measure drift; apply small rate corrections where supported and seek/re-align on larger drift; re-align followers after stalls or seeks.
5. **DecoderCoordinator** — allocate/release decoder resources as a unit, inspect codec support, and fail with a visible, diagnosable reason if the device cannot sustain the selected layout. No fake-success guards.
6. **MultiviewCompositor** — render decoded feed textures into one OpenGL ES output surface, with per-feed crop, aspect ratio, z-order, and resizeable viewports.
7. **AudioRouter** — select one audio source by default and prevent accidental simultaneous audio from every feed.
8. **AdaptiveLoadController** — observe decoder load, dropped frames, buffer depth, and thermal/performance signals; reduce per-feed resolution/frame rate where supported rather than letting every feed stutter.
9. **Diagnostics** — log per-feed codec, dimensions, input queue depth, first-frame latency, decoder errors, PTS drift, dropped frames, and compositor frame time. Never log tokens or secrets.

### Three implementation paths to evaluate

**Path 1 — compressed-domain tile merge:** use GPAC `hevcmerge` only if the actual inputs are spatial HEVC tiles with matching access-unit timestamps, decoder configuration, and valid tile geometry. It produces one merged HEVC bitstream but is not a merger for independent camera angles.

**Path 2 — one GPAC media graph with a compositor (first prototype candidate):** enable and build GPAC's `dashin`/media input and `compositor` filters, feed the independent camera streams into one GPAC session, and render a mosaic to an Android-owned output surface. This keeps one graph/session owner and reuses an established open-source media framework. It is not yet proven in our Android build; compositor/vout are currently disabled, so we must validate native dependencies, surface ownership, live timing, and hardware decoding before adopting it.

**Path 3 — custom coordinated decode/composite:** use one app-owned engine to coordinate per-feed demux/decode pipelines, SurfaceTexture outputs, a shared timeline, and one OpenGL ES compositor. This can emulate the multiview behavior and layout, but may use multiple hardware decoder contexts and has device-dependent limits. It is not the same compression/decoder optimization as a server-prepared tiled stream.

An important engineering constraint remains independent of any vendor: arbitrary independent camera feeds cannot be assumed to combine into one encoded stream or one decoder input. We must establish the source formats and then choose a technically valid open implementation. Run a format/capability probe and a two-feed prototype before choosing the primary path. Do not silently fall back from one path to another.

## 4. Phased execution plan

### Phase 0 — freeze the correct direction
- Remove TME discovery and all Tiledmedia/ClearVR inspection from the acceptance criteria and implementation plan.
- Keep current work on `feature/tiledmedia-multiview-rearchitecture`.
- Preserve resizing, TV remote controls, logging, and existing UI.
- Do not trigger a build without explicit approval.

### Phase 1 — audit the current implementation (static code audit baseline complete; source-format probe remains)
- Trace the complete path from selected F1 feed -> playback response -> feed descriptor -> aligned sample queue -> native merger -> decoder/output surface.
- Identify every TME-specific type, gate, and assumption; classify each as reusable generic behavior, GPAC-specific behavior, or proprietary/TME-only plumbing.
- Establish the exact input codec/container and whether real feeds are compatible with the current HEVC merger. Do not infer this from HTTP 200 or a manifest URL alone.
- Add focused tests for missing TME metadata, incompatible codecs, timestamp mismatch, merger output, and visible failure states.

#### Confirmed code-audit findings
1. `UnifiedMultiviewEngine.updateViewport()` sets `media3MultiviewBlocked` when more than one visible video exists and no parsed/typed TME session is available; it releases the decoder manager and returns an empty set. This is the immediate reason ordinary independent feeds cannot enter a multiview playback path when F1 responses have no TME metadata.
2. `OpenTiledMultiviewEngine` is already vendor-independent in its implementation, but its entry contract still requires a `TmePlayback` whose topology is `SINGLE_MOSAIC_SOURCE`, a single source URL, and tile geometry. It handles a pre-packaged mosaic; it does not combine independent onboard URLs.
3. The current JNI GPAC graph explicitly loads `hevcmerge:mrows=true:strict=true`, declares each input as HEVC, and collects compressed output samples. It does not decode independent camera pictures or render a general mosaic.
4. `scripts/build-gpac-android.sh` explicitly disables GPAC `compositor`, `vout`, and `aout`; therefore the currently built GPAC configuration cannot provide the proposed independent-stream compositor without a separate build/configuration and Android output-surface proof.
5. The current default backend is `Media3MultiPlayerFallbackBackend`, while the code intentionally blocks that backend for more than one visible video. Simply removing the gate would therefore violate the one-logical-engine requirement and risk returning to the multi-ExoPlayer behavior the project is meant to replace.
6. Research of F1AppleTV and Race Control confirms that existing F1 clients provide valuable layout, feed lifecycle, and synchronization patterns, but both use separate per-feed players in their current implementations. They are references for product behavior and test cases, not evidence of single-decoder composition.
7. The first implementation milestone must be a vendor-neutral engine contract and clear synthetic two-feed rendering proof. Only after that should the F1 feed resolver be wired into it; the actual F1 codec/container/timestamp characteristics still need to be measured rather than assumed.


- Trace the complete path from selected F1 feed -> playback response -> feed descriptor -> aligned sample queue -> native merger -> decoder/output surface.
- Identify every TME-specific type, gate, and assumption; classify each as reusable generic behavior, GPAC-specific behavior, or proprietary/TME-only plumbing.
- Establish the exact input codec/container and whether real feeds are compatible with the current HEVC merger. Do not infer this from HTTP 200 or a manifest URL alone.
- Add focused tests for missing TME metadata, incompatible codecs, timestamp mismatch, merger output, and visible failure states.

### Phase 2 — build an engine contract and synthetic test harness
- Define a TME-independent `FeedDescriptor`, `FeedSample`, `SessionTimeline`, `ViewportLayout`, and `EngineState`.
- Use synthetic, clear, known-good media samples to test engine behavior independently of F1 authentication and DRM.
- Prove timestamp alignment, feed add/remove, seek, buffering, surface recreation, resize, and release/restart lifecycle.
- Keep the test path separate from real F1 entitlement and protected playback.

### Phase 3 — prove the native media path
- Prototype GPAC HEVC merging with compatible spatial-tile test bitstreams and verify the merged output using a decoder, not just a successful native return.
- Build a separate GPAC configuration with `dashin` and `compositor` enabled; prove that two local clear test streams render into one Android surface in one graph, then add a third stream.
- If GPAC's Android compositor/output integration is unsuitable, prototype the custom MediaCodec -> SurfaceTexture -> OpenGL path with two then three synthetic feeds.
- Measure CPU/GPU, memory, decoder allocation, frame drops, and thermal behavior on the target phone/TV. Choose based on evidence, not naming or architecture diagrams.

### Phase 4 — integrate the chosen engine with F1 feeds
- Connect the engine to the existing authorized feed resolver without requiring TME metadata.
- Preserve a single engine/session owner; no independent ExoPlayer fallback.
- Keep protected playback and DRM integration as a later milestone, but do not bypass or weaken DRM when that milestone is implemented.
- Validate first with two feeds, then three/four, including repeated layout changes and surface recreation.

### Phase 5 — release gate
Do not claim success until the correct branch builds, unit tests pass, the signed APK is verified and uploaded, and actual device evidence confirms two-/three-feed playback is smooth and synchronized. Build status alone is not runtime proof.

## 5. Acceptance criteria

- The engine can start without any TME metadata.
- Two and three feed inputs can be selected and reach explicit, diagnosable states.
- All active feeds share one session timeline and continuously re-synchronise after stalls.
- One logical player/session controls all feeds; no independent ExoPlayer fallback.
- Resize, layouts, audio selection, and TV remote navigation continue to work.
- Unsupported codec combinations or insufficient decoder capacity produce clear diagnostics rather than endless loading.
- No DRM bypass, invented F1 endpoint, or proprietary Tiledmedia runtime dependency.
