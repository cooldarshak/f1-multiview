# F1 MultiView: Own Multiview Engine Plan

**Target branch:** `feature/tiledmedia-multiview-rearchitecture`  
**Direction:** Build an original multiview engine from open standards and open-source components. Tiledmedia/ClearVR is explicitly out of scope: no SDK, no proprietary metadata dependency, no proprietary implementation study, and no Tiledmedia-derived design blueprint.

## 1. Findings that change the plan

### Explicit exclusion: Tiledmedia/ClearVR

Do not inspect, reuse, reverse-engineer, or design around the Tiledmedia/ClearVR assets in the official APK. Do not search for its private metadata, attempt to reproduce its internal SDK contracts, or make TME discovery a project milestone. The official APK is not an input to our architecture work. Use public Android APIs, open standards, and independently maintained open-source projects instead.

### Current source state

The previous metadata-dependent session/parser path, tiled backend, native compressed-domain merger, and GPAC/CMake packaging have been removed from the active app source and build workflows. The app no longer selects production multiview topology from private provider metadata.

The production coordinator currently supports ordinary authorized single-feed playback. When multiple video feeds are selected, it shows a clear “not yet enabled” state and refuses independent-player fallback. This is an intentional interim gate: the custom renderer must first pass the three-feed synthetic runtime proof on the target phone and Android TV. It is not a claim that production multiview is already working.

The synthetic proof uses public Android APIs only: local clear H.264 fixtures, one MediaCodec decoder per feed, SurfaceTexture inputs, and one EGL/OpenGL ES compositor. No F1 endpoint, account credential, or protected stream is used by that test harness.

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

### D. Optional open-source media frameworks

GPAC and GStreamer remain public open-source projects that could be evaluated later if there is a concrete, independently validated benefit. Neither is part of the current app build, and neither should be assumed to provide a working Android output-surface, hardware-decoder, timing, or protected-playback path merely because it builds on Linux.

The immediate baseline is the app-owned Android MediaCodec → SurfaceTexture → EGL/OpenGL ES prototype. Any future framework comparison must use clear synthetic inputs first, meet the repository's licensing requirements, and be compared using measured first-frame latency, drift, dropped-frame evidence, CPU/GPU/memory use, thermal behavior, lifecycle stability, and build size.

### Research conclusion

We are not looking for a drop-in equivalent to Tiledmedia or attempting to clone its internals. We are building the app's required multiview capabilities from public Android media APIs, open standards, and reusable open-source components. The engine must be independently specified and tested.

## 2A. Implementation progress (2026-10-09)

The provider-neutral contract in `OwnMultiviewEngineContract.kt` defines feed descriptors, normalized viewports/layouts, engine phases, feed status, and a shared session timeline. `SessionTimelineSynchronizer.kt` contains a deterministic master/follower correction policy and JVM tests.

The debug-only synthetic harness now generates **three distinct clear H.264 clips**, describes them with `FeedDescriptor`, decodes them with three Android `MediaCodec` instances against one shared playback anchor, and draws their `SurfaceTexture` inputs through one GLES compositor using normalized `FeedViewport`/`ViewportLayout` values.

The diagnostics report per-feed first-texture-frame latency, texture update rate and gaps, surface timestamp skew, coalesced frame notifications, codec-reported dropped-frame metrics when exposed, late presentation deadlines, GLES draw cadence, process PSS/heap/CPU, and decoder-thread count. Coalesced callbacks and late presentation deadlines are explicitly not treated as authoritative decoder-drop counts.

The source migration and validation status is tracked in [OWN_ENGINE_SOURCE_MIGRATION_AUDIT.md](OWN_ENGINE_SOURCE_MIGRATION_AUDIT.md). Cloud build/test validation is in progress; target-device runtime measurements are still required.

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

Build an app-owned, provider-neutral engine with these responsibilities:

- **FeedCatalog / FeedSourceResolver:** resolve selected feed IDs into typed sources. Provider metadata may enrich a feed but is not a prerequisite for multiview.
- **FeedPipeline:** demux, buffer, decode, and expose frames through Android public media APIs. Preserve the authorized manifest, entitlement, authentication headers, and Widevine path when protected inputs are later integrated.
- **TimelineClock:** maintain one logical session timebase and per-feed media timestamps.
- **FrameSynchronizer:** measure master/follower PTS drift, correct small drift with bounded playback-rate changes, and seek only when the drift policy requires it.
- **DecoderCoordinator:** allocate/release physical decoders based on measured device capability, codec profile, secure-decoder constraints, resolution, and thermal/resource behavior. A logical session does not imply a single decoder.
- **MultiviewCompositor:** consume independent decoded surfaces/textures and draw the requested normalized viewports into one app-owned output surface.
- **AudioRouter:** maintain one selected audio source and handle mute/language selection where the source supports it.
- **AdaptiveLoadController:** adjust selected feed quality/resolution and avoid resource overcommit without silently dropping feeds or hiding failures.
- **Diagnostics:** expose first-frame latency, per-feed PTS drift, frame-drop evidence, buffering, decoder identity/capacity, CPU/GPU/memory, and thermal state where available.

### Three implementation principles

1. **Prove the compositor on synthetic clear media first.** The current harness uses three independent 320×180 H.264 feeds at 15 fps. That is a starting point, not proof of the resolution/frame rate F1 will require.
2. **Measure physical decoder limits.** Each independent encoded camera feed may need its own decoder. Record the actual codec selected, frame cadence, failures, resource use, and sustainable feed count on each target device.
3. **Keep production UI independent of backend selection.** Preserve existing feed selection, main-feed selection, layouts, resize handles, playback controls, audio/subtitle/quality menus where supported, logging/screenshot settings, and TV D-pad/focus behavior. Unsupported multi-feed playback must be visible; it must not silently create one ExoPlayer per feed.

## 4. Phased execution plan

### Phase 0 — enforce the boundary

- Keep work on `feature/tiledmedia-multiview-rearchitecture`; do not use `main` for active development.
- Keep the app free of proprietary multiview metadata parsers, vendor-specific session contracts, and native merger code derived from that path.
- Preserve authentication, entitlement, and Widevine restrictions. No DRM bypass.
- Do not trigger builds unless the user has explicitly requested validation.
- Keep resizing and existing UI/control behavior intact.

### Phase 1 — source migration audit

- Verify that app source, Gradle, native packaging, scripts, and CI no longer depend on the removed metadata-driven path.
- Keep provider-neutral feed/timeline/viewport contracts and focused tests.
- Document any remaining unsupported production behavior instead of disguising it with fallback routing.

### Phase 2 — three-feed synthetic proof

- Generate three visibly distinct clear H.264 fixtures locally.
- Decode three feeds through separate MediaCodec instances and present them through three SurfaceTexture inputs to one GLES compositor.
- Measure first-frame latency, PTS drift, frame-drop evidence, decoder identities, CPU/GPU/memory, and lifecycle behavior.
- Repeat layout changes, pause/resume, surface recreation, and teardown to find races and resource leaks.
- Validate on the target Android phone and Android TV; an emulator or cloud build is not a substitute for device decoder-capacity evidence.

### Phase 3 — fix and establish the baseline

- Fix rendering, pacing, timestamp, resource, or lifecycle defects before protected media is introduced.
- Add explicit failure diagnostics when a codec or device cannot sustain three feeds.
- Compare one, two, and three feeds to establish where capacity degrades; do not claim four-feed support without evidence.

### Phase 4 — authorized F1 integration

- Only after Phase 2 passes on both devices, connect the app-owned engine to ordinary authorized F1 feed resolution.
- Preserve login/session refresh, entitlement headers, manifest preparation, license requests, and Widevine secure-output requirements.
- Validate real feed codecs, containers, timestamps, live-edge behavior, audio, subtitles, quality selection, and decoder capacity.
- Do not infer that a clear synthetic path proves protected F1 playback works.

### Phase 5 — release gate

- `testDebugUnitTest` passes.
- `assembleDebug` succeeds.
- APK signature, identity, integrity, and artifact existence are verified.
- Runtime evidence from the target phone and Android TV confirms smooth three-feed playback and records the requested metrics.
- No success claim is made based on source changes or a cloud build alone.

## 5. Acceptance criteria

- The app starts and supports a single authorized F1 feed without requiring proprietary multiview metadata.
- Three clear synthetic feeds can be decoded and composited through one app-owned GLES output surface.
- First-frame latency, PTS drift, dropped-frame evidence, decoder usage, CPU/memory, and lifecycle behavior are measurable.
- Device decoder limits are explicit and evidence-based.
- Existing feed selection, layouts, resizing, controls, logging/screenshot settings, and TV navigation remain present.
- Multi-feed production playback never silently falls back to independent ExoPlayers.
- No authentication bypass, DRM bypass, invented F1 endpoint, or proprietary multiview runtime dependency is introduced.

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

1. **Current baseline: three-feed custom Android renderer.** Continue with MediaCodec → SurfaceTexture → EGL/OpenGL ES using clear synthetic clips. This is the first measurable baseline and does not depend on a proprietary SDK.
2. **Optional open-source comparisons only after baseline measurements.** GPAC, GStreamer, or another license-compatible project may be considered only as an independently validated component. No old native merger path is part of the current app.
3. **Measure sustainable capacity, not just player count.** One logical session/compositor may still require one physical decoder per independent encoded camera feed. Prove what each target device can sustain.
4. **Keep protected playback as a separate acceptance gate.** A clear synthetic prototype proves only clear decode/render/synchronization behavior. It does not prove Widevine compatibility or protected-output support.
5. **No build/runtime success claim without evidence.** Cloud tests and signed APK verification establish build health; phone and Android TV runtime measurements establish rendering behavior.

### Immediate next work

- Complete the current cloud validation run: JVM unit tests, signed APK assembly, signature/integrity checks, and artifact upload.
- Install and run the debug-only three-feed prototype on the target phone and Android TV.
- Capture its per-feed first-frame latency, texture update gaps, timestamp skew, codec-reported dropped-frame metrics where exposed, late deadlines, GLES draw cadence, PSS/heap/CPU, and decoder names.
- Fix any defects found and repeat until the synthetic proof passes.
- Only then integrate authorized F1 feeds into the own decoder/compositor path.

### Updated architectural decision

1. **Prototype first: native Android custom rendering with clear synthetic clips.** Use MediaCodec -> SurfaceTexture -> EGL/OpenGL ES and render two feeds into one app-owned output surface. Grafika is a reference for primitives, not the finished solution. This most directly proves whether our target hardware can sustain the required independent feed count and gives us a baseline without depending on proprietary SDKs.
2. **Prototype in parallel only after the first harness exists: GStreamer/GPAC.** Compare actual first-frame time, dropped frames, CPU/GPU/memory, thermal load, synchronization error, build size, dependency/licensing burden, and lifecycle stability. Qualcomm's sample validates the general graph pattern but not Android portability.
3. **Do not spend the next milestone integrating AWS server-side multiview or NexPlayer.** They are useful architectural evidence, but neither is an embeddable, self-contained open Android engine that we can substitute into the current app.
4. **Keep client engine and source packaging separate.** If the authorized F1 feeds are independent camera streams, the client prototype must decode each feed as required and compose them. A single logical session/compositor does not promise one physical decoder. A one-decoder path is possible only if the inputs are supplied in a format that actually represents a single decodable picture/stream (for example a precomposed stream or a compatible spatially tiled encoding).
5. **Preserve protected playback as a separate acceptance gate.** A successful clear synthetic prototype proves rendering/synchronization only. It does not prove Widevine compatibility; protected surfaces, codec secure-decoder requirements and output constraints must be tested without bypassing DRM.
6. **Do not make any build or claim runtime success without explicit user approval and the agreed evidence checks.**

### Immediate next work
- Inspect the existing Gradle/NDK and native-surface lifecycle on the active branch.
- Add a minimal isolated three-feed synthetic harness without altering existing production UI or removing resize behavior.
- Reuse only license-compatible examples and document any code copied or adapted.
- Measure whether two streams can be decoded and composited smoothly on target hardware before selecting a framework.
- Then inspect the actual authorized F1 feed formats and decide whether the same client pipeline can support them; do not return to TME discovery as a prerequisite.
