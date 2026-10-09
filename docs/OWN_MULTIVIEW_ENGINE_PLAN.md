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

These files are an initial contract/policy layer, **not yet the production playback path**. An isolated debug-only synthetic prototype now generates two distinct clear H.264 fixtures, decodes them with two Android MediaCodec instances, and composites their SurfaceTextures through one GLES output view. Its source now displays/logs first-texture-frame latency, texture update rate/gaps, surface PTS skew, coalesced frame notifications, codec-reported dropped-frame metrics when available, late presentation deadlines, GLES draw cadence, process PSS/heap/CPU, and decoder thread count. Coalesced callbacks and late deadlines are explicitly not treated as authoritative decoder-drop counts.

The source-level dependency map and staged removal plan are recorded in [OWN_ENGINE_SOURCE_MIGRATION_AUDIT.md](OWN_ENGINE_SOURCE_MIGRATION_AUDIT.md). No build or test run has been triggered. **The production path still contains TME-dependent control flow and has not yet been migrated**; do not claim the architecture is corrected. Next, validate the two-feed prototype with an approved build and runtime measurements, then replace the production TME discovery/session path with the provider-neutral own-engine contract, preserving the existing UI controls/layouts/resizing/TV remote behavior and never silently falling back to one ExoPlayer per feed.

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


## 2C. Cross-industry multiview research (Firecrawl + public engineering sources, 2026-10-09)

This section intentionally broadens beyond F1 projects. It distinguishes publicly documented product behavior from implementation evidence. It does not treat marketing claims or product features as proof of a compatible Android implementation.

### Strongest new implementation references

**Qualcomm GStreamer video-wall sample — practical multi-decode/composition pipeline**
- Technical guide: https://docs.qualcomm.com/doc/80-80022-55/topic/gst-concurrent-videoplay-composition.html
- Engineering overview: https://www.qualcomm.com/developer/blog/2024/07/qualcomm-linux-sample-apps-building-blocks-ai-inference
- Qualcomm documents a runnable command-line sample that reads multiple H.264 MP4 files (or network sources), demuxes/parses them, decodes each channel with a hardware decoder element, composes decoded frames using `qtivcomposer`, and displays the result. The overview says the sample can compose four or eight channels.
- Why it matters: it is a concrete, non-F1 example of the same decode-many -> compose-one-screen problem, including the use of platform-specific hardware decode and composition blocks.
- Limitation: the described sample targets Qualcomm's Linux multimedia stack (V4L2/Wayland/Qualcomm plugins), not Android's public MediaCodec/EGL APIs. `qtivcomposer` is not a portable Android component. Reuse the pipeline shape and benchmarking method, not assumptions that its binaries will run in our app.
- Prototype lesson: each feed needs bounded buffering and independent decode status; composition must be treated as a first-class stage, and decoder capacity must be measured on the actual target SoC.

**Google Grafika — two simultaneous Android video surfaces**
- https://github.com/google/grafika (Apache-2.0; archived/experimental)
- Explicitly demonstrates decoding two video streams side by side to TextureViews and contains MediaCodec, SurfaceTexture, EGL and presentation-timing examples.
- Why it matters: closest small public Android-native reference for the low-level primitives required by a custom proof of concept.
- Limitation: it is a sample collection, not a production engine; it does not establish stable live HLS/DASH ingest, multi-feed synchronization, Widevine protected composition, or performance on current target devices.

**media-kit / libmpv Android rendering path**
- https://github.com/media-kit/media-kit (MIT)
- Its documented Android implementation renders libmpv to a Surface backed by an Android SurfaceTexture, with MediaCodec hardware decoding configured through mpv.
- Why it matters: a viable research avenue for an established playback core that can render into a texture/surface owned by a host app; potentially useful for an isolated performance comparison.
- Limitation: it does not by itself prove multi-feed single-session composition, synchronization, or protected DRM integration. libmpv is a playback engine, not a magic one-decoder merger. Check transitive dependencies, build burden, and license notices before adoption.

**GStreamer OpenGL compositor**
- Element docs: https://gstreamer.freedesktop.org/documentation/opengl/glvideomixer.html
- Android build/deployment guide: https://gstreamer.freedesktop.org/documentation/installing/for-android-development.html
- The GL mixer composites multiple input streams into one output scene. Qualcomm's sample confirms a similar approach with vendor-specific hardware decoder/compositor plugins on its platform.
- Why it matters: GStreamer has an actual multi-input graph model and multiple possible decode/composition elements, rather than requiring us to invent all media plumbing.
- Limitation: an Android package must include compatible plugins and codec elements; whether decoded frames remain GPU-resident, hardware decode is selected, and secure surfaces work must be demonstrated on-device. Plugin names and hardware acceleration differ by SoC.

**Android Media3 SurfaceControl demo and MediaCodec/SurfaceTexture references**
- Media3 SurfaceControl demo: https://github.com/androidx/media/tree/release/demos/surface
- Android MediaCodec: https://developer.android.com/reference/android/media/MediaCodec
- SurfaceTexture architecture: https://source.android.com/docs/core/graphics/arch-st
- These provide public Android primitives and samples for surface ownership and rendering, but not a ready-made independent-feed multiview engine. A single SurfaceControl surface is not equivalent to combining arbitrary independently encoded streams into one decoder.

### Commercial and broadcast implementations: useful constraints, not code to copy

**AWS Elemental MediaPackage Dynamic Multiview / JioHotstar**
- AWS announcement (September 2026): https://aws.amazon.com/about-aws/whats-new/2026/09/aws-elemental-dynamic-multiview-video
- AWS technical overview: https://docs.aws.amazon.com/mediapackage/latest/userguide/dynamic-multiview.html
- How it works: https://docs.aws.amazon.com/medialive/latest/ug/dynamic-multiview-how-it-works.html
- Public documentation describes a server-side system that assembles matching segments from independently encoded sources into a viewer-selected multiview output on demand, delivered as one standard HLS or DASH stream. The described method works in the compressed domain without decoding/re-encoding the layout, so the client sees one video track/decoder and one DRM key. AWS says JioHotstar is using it for quad-view IPL coverage.
- Why it matters: it is real-world evidence that the user experience can be delivered efficiently by changing the streaming packaging/production architecture, not just the client app.
- Critical limitation for our project: this is an AWS server-side product, not an Android library we can embed. Our app does not control F1's origin/packaging pipeline and cannot create this output merely by changing the Android player. It becomes relevant only if the authorized content provider supplies a compatible precomposed stream or we later operate a lawful server-side pipeline for sources we are authorized to process. Do not confuse this architecture with client-side decoding/compositing.

**Sky Sports “Your Multiview”**
- Official product information: https://www.skysports.com/football/news/13442249/your-multiview-on-sky-sports-how-to-watch-up-to-four-live-football-games-or-sports-events-at-same-time
- Official usage video: https://www.skysports.com/more-sports/video/13575471/how-to-watch-your-multiview
- Public materials establish the product behavior: up to four live events, adjustable layout, audio choice and promotion of one event to full screen across Sky's supported TV platforms.
- Why it matters: validates requirements for tile assignment, focus switching, audio routing and layouts across mixed sports.
- Limitation: the public product page does not document the internal decoder, compositor, or packaging architecture. We must not guess whether it uses client-side players, a precomposed stream, or a platform-specific pipeline.

**Disney / Netflix / Amazon / Hotstar**
- Searches for public engineering write-ups did not establish a reusable public source tree for the exact on-device problem of synchronizing and composing several independent protected live streams into one Android view. Public Netflix engineering material found in this pass is about large-scale live streaming and service-side delivery, not an Android multiview compositor.
- Keep researching their patents, engineering blogs, conference talks, and public SDKs where relevant, but only promote a claim to “implementation evidence” when the source actually describes the media path. A brand using streaming technology is not evidence that its private app architecture is available to reuse.
- The AWS/JioHotstar case above is currently the strongest public, named example found of a commercial streaming platform deploying dynamic multiview at scale, but it is a server-side packaging design and does not solve our current client-only constraint.

**NexPlayer Multiview public repo — commercial SDK documentation, not reusable engine source**
- https://github.com/NexPlayer/NexPlayer_Multiview
- This is public documentation for a proprietary commercial player SDK rather than an open implementation we can transplant. Use only its public input/timestamp constraints as already noted above; do not add the SDK as a dependency.

### Additional general-purpose video sources

**ChromeOS VideoDecodeEncodeDemo**
- https://github.com/chromeos/video-decode-encode-demo
- A proof-of-concept for multi-stream decode/encode and SurfaceTexture/OpenGL coordination. Its limitations and target environment must be kept explicit.

**Google Grafika**
- https://github.com/google/grafika
- Apache-2.0 reference samples for Android MediaCodec and GLES rendering. Archived; learn from examples, don't depend on it as a maintained framework.

**media-kit**
- https://github.com/media-kit/media-kit
- MIT-licensed playback framework with an Android SurfaceTexture rendering path. Worth a controlled comparison only if it can be hosted per feed without creating the same decoder-contention problem and if a separate compositor can consume its frames safely.

### Updated architectural decision

1. **Prototype first: native Android custom rendering with clear synthetic clips.** Use MediaCodec -> SurfaceTexture -> EGL/OpenGL ES and render two feeds into one app-owned output surface. Grafika is a reference for primitives, not the finished solution. This most directly proves whether our target hardware can sustain the required independent feed count and gives us a baseline without depending on proprietary SDKs.
2. **Prototype in parallel only after the first harness exists: GStreamer/GPAC.** Compare actual first-frame time, dropped frames, CPU/GPU/memory, thermal load, synchronization error, build size, dependency/licensing burden, and lifecycle stability. Qualcomm's sample validates the general graph pattern but not Android portability.
3. **Do not spend the next milestone integrating AWS server-side multiview or NexPlayer.** They are useful architectural evidence, but neither is an embeddable, self-contained open Android engine that we can substitute into the current app.
4. **Keep client engine and source packaging separate.** If the authorized F1 feeds are independent camera streams, the client prototype must decode each feed as required and compose them. A single logical session/compositor does not promise one physical decoder. A one-decoder path is possible only if the inputs are supplied in a format that actually represents a single decodable picture/stream (for example a precomposed stream or a compatible spatially tiled encoding).
5. **Preserve protected playback as a separate acceptance gate.** A successful clear synthetic prototype proves rendering/synchronization only. It does not prove Widevine compatibility; protected surfaces, codec secure-decoder requirements and output constraints must be tested without bypassing DRM.
6. **Do not make any build or claim runtime success without explicit user approval and the agreed evidence checks.**

### Immediate next work
- Inspect the existing Gradle/NDK and native-surface lifecycle on the active branch.
- Add a minimal isolated two-feed synthetic harness without altering existing production UI or removing resize behavior.
- Reuse only license-compatible examples and document any code copied or adapted.
- Measure whether two streams can be decoded and composited smoothly on target hardware before selecting a framework.
- Then inspect the actual authorized F1 feed formats and decide whether the same client pipeline can support them; do not return to TME discovery as a prerequisite.
