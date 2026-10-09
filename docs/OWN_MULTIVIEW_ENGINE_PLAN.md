# F1 MultiView: Own Multiview Engine Plan

**Target branch:** `feature/tiledmedia-multiview-rearchitecture`  
**Direction:** Build an original multiview engine. Do not make Tiledmedia/TME SDK, metadata, or proprietary service a runtime dependency.

## 1. Findings that change the plan

### Official F1 TV APK (static inspection of the user-provided APK)

The APK contains Tiledmedia/ClearVR components, including `assets/tm-main-release.js`, `assets/tiledmedia.css`, `assets/tmplayer-ui.html`, and native ClearVR renderer libraries. The bundled SDK exposes concepts for content items, feeds, tracks, tiles, rendering state, and hardware decoder limitations. This is evidence that the official app integrates a specialised Tiledmedia stack; it is not evidence that its proprietary implementation can be reproduced merely by looking for a field in `CONTENT/PLAY`.

Use this inspection only to infer externally observable behavior and architectural responsibilities. Do not copy proprietary implementation code or depend on private SDK symbols.

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

### B. ChromeOS VideoDecodeEncodeDemo — open-source proof of concept
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

I did not find a ready-made, production-quality, open-source Android library that is a drop-in equivalent to Tiledmedia's complete multiview stack. There are reusable building blocks for sync, media demux/decode, and composition. We should build our own engine around those blocks rather than integrate TME.

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

Public Tiledmedia documentation describes multiview transcoding/packaging in its cloud or a customer's transcoding pipeline. Consequently, we can reproduce the user-visible multiview behavior on-device, but we cannot promise the same bandwidth and single-decoder efficiency from arbitrary independent feeds without a compatible pre-tiled source or an equivalent upstream packaging stage.

Run a format/capability probe and a two-feed prototype before choosing the primary path. Do not silently fall back from one path to another.

## 4. Phased execution plan

### Phase 0 — freeze the correct direction
- Stop treating TME discovery as the acceptance criterion.
- Keep current work on `feature/tiledmedia-multiview-rearchitecture`.
- Preserve resizing, TV remote controls, logging, and existing UI.
- Do not trigger a build without explicit approval.

### Phase 1 — audit the current implementation (next)
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
