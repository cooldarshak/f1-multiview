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
