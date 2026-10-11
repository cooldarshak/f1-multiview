# Live Timing, Driver Tracker, and Track Map Implementation Plan

**Project:** F1 MultiView for Android / Android TV  
**Target branch:** `feature/tiledmedia-multiview-rearchitecture`  
**Priority:** Reproduce the supplied Live Timings and Driver Tracker / Track Map references faithfully, using real session data.  
**Principle:** Fix existing behavior without deleting working functionality. No shortcuts and no fabricated data.

## 1. Scope and non-negotiable constraints

- The immediate milestone is a working, integrated Live Timing tower and native Driver Tracker / Track Map, including replay/VOD behavior and live-session data flow.
- Single-feed playback is already working in the known-good path. Do not misclassify the timing/map work as a playback repair or regress single-feed playback.
- Keep official F1 TV feed types selectable and playable. Native timing and map panels are additive, not replacements for official feeds.
- Preserve Widevine/DRM behavior, the Media3/ExoPlayer single-feed path, and existing multiple-feed safety guards.
- Do not use OpenF1 for live timing or position data. Do not use its endpoints, APIs, SDK, or code.
- Do not use Tiledmedia, TME, ClearVR, or any vendor-owned or vendor-created implementation or dependency.
- Use genuine feed, replay, timing, position, and geometry data. Never invent timing values, GPS coordinates, driver positions, track geometry, or session metadata.
- Keep the implementation native to Android/Compose where practical. Do not introduce a mandatory Python/Pandas backend or service.
- Preserve required upstream licence and attribution notices. Review licences before copying code; prefer porting behavior and architecture rather than copying incompatible implementations. Do not copy AGPL-licensed f1-dash code into this app unless licensing is explicitly resolved.
- Do not claim the work is complete based only on compilation or CI. Separate build/test evidence from runtime/device evidence.

## 2. Reference projects and intended use

1. **F1OpenViewer** — https://github.com/npanu420/F1OpenViewer  
   Primary behavioral reference for the live timing tower and F1 timing feed transport. Inspect `electron/livetiming.js`, `src/domain/timing.ts`, `src/ui/components/LiveTimingBody.tsx`, and `src/ui/hooks/useLiveTiming.ts`. Study SignalR negotiation, WebSocket framing, subscriptions, reconnect behavior, compressed message decoding, sparse-delta merging, driver rows, sectors, tyres, weather, and race-control data. Port the required behavior to Kotlin/Compose, respecting licence terms.

2. **F1ReplayTiming** — https://github.com/adn8naiagent/F1ReplayTiming  
   Reference for replay/archive session processing, timestamped snapshots, track rendering, position mapping, smooth marker interpolation, and seek handling. Inspect `backend/services/live_signalr.py`, `backend/routers/track.py`, `backend/routers/replay_data.py`, `frontend/src/components/TrackCanvas.tsx`, and `frontend/src/lib/trackRenderer.ts`. Port the relevant client-side behavior without making its Python backend mandatory.

3. **Fast-F1** — https://github.com/theOehrly/Fast-F1  
   Reference for timing archive semantics and telemetry interpretation, not an Android runtime dependency.

4. **f1-circuits** — https://github.com/bacinger/f1-circuits  
   Reference for circuit geometry. Verify coordinate systems, circuit identity, and alignment before rendering.

5. **Jolpica-F1** — https://github.com/jolpica/jolpica-f1  
   Historical schedule, results, and standings only. Do not treat it as the primary live position source.

6. **f1-dash** — https://github.com/slowlydev/f1-dash  
   Supplementary UI/state reference only. Distinguish estimated mini-sector-derived positions from actual telemetry; respect its licence and do not copy AGPL code into the app without resolving compatibility.

**Explicit exclusion:** https://github.com/br-g/openf1. Do not use OpenF1 for any live timing or driver-position implementation.

## 3. Existing implementation to inspect first

Re-fetch the latest branch head before changing anything. Inspect these files and their call sites before editing:

- `app/src/main/java/app/f1multiview/data/timing/LiveTimingClient.kt`
- `app/src/main/java/app/f1multiview/data/timing/ReplayTimingClient.kt`
- `app/src/main/java/app/f1multiview/data/timing/TrackMapClient.kt`
- `app/src/main/assets/circuit_geometry_2026.json`
- `app/src/main/java/app/f1multiview/ui/TrackMapPanel.kt`
- `app/src/main/java/app/f1multiview/ui/UgisInfoPanel.kt`
- `app/src/main/java/app/f1multiview/ui/App.kt`
- `app/src/main/java/app/f1multiview/viewmodel/MultiViewViewModel.kt`
- `app/src/main/java/app/f1multiview/model/Models.kt`
- `app/src/test/java/app/f1multiview/data/timing/ReplayTimingClientTest.kt`
- `app/src/test/java/app/f1multiview/data/timing/TrackMapClientTest.kt`

Do not assume earlier observations still describe the current branch. Verify each finding against the latest source and installed APK/build commit.

## 4. Implementation sequence

### Phase 1 — Replay timing load and deterministic session resolution

**Problem to verify:** `MultiViewViewModel.setSession()` resets state and calls `loadStreams(session)`, but may not call `loadReplayTiming(session)`. Some VOD/resume paths may load replay timing separately. `loadReplayTiming()` may silently return when season year or meeting number is missing. The UI playhead updater cannot update timing if the replay client was never loaded.

Tasks:
- Trace session selection, playback start, pause/resume, seek, and replay playhead updates end-to-end.
- Ensure replay timing is loaded for the selected replay session at the correct lifecycle point and is reset/reloaded when the session changes.
- Report missing year, meeting, session identity, archive, or timing streams as explicit diagnosable states rather than silent empty panels.
- Replace permissive first-match session selection with deterministic scoring. Prefer exact archive/session identifiers and exact names; use season, meeting, and session type as supporting evidence.
- Reject unresolved ambiguous matches rather than accidentally selecting Practice, Qualifying, Sprint, or Race from the wrong event.
- Log the chosen archive/session path and resolution reason without logging credentials, cookies, or secrets.
- Verify that playhead time maps to archive timestamps correctly and that forward/backward seeking returns the appropriate snapshot.
- Keep replay timing isolated from live timing state.

Tests:
- Exact match beats partial name/type match.
- Similar session names/types across a meeting do not select the wrong session.
- Missing identifiers and ambiguous candidates fail clearly.
- Replay load occurs on session selection and reloads correctly after session changes.
- Playhead lookup, forward seek, backward seek, and reset behavior.
- Sparse timing deltas merge into the expected full snapshot.

### Phase 2 — Circuit geometry selection and local-first fallback

**Problem to verify:** the bundled `circuit_geometry_2026.json` contains 23 circuits, including Bahrain/Sakhir key `63` (730 points) and Singapore key `61` (544 points). The current client may try remote geometry and older-year fallbacks before bundled geometry, allowing HTTP 401 or network delay to prevent immediate rendering.

Tasks:
- Validate the bundled asset's schema, circuit identifiers, point counts, coordinate ranges, and coordinate-system compatibility.
- For a known, validated circuit, render bundled geometry immediately. Do not make a remote request a prerequisite for a usable local map.
- Use remote geometry only when local geometry is missing, unsupported, or a newer layout is genuinely required.
- Validate circuit identity, not merely a minimum centreline point count. Never draw a different circuit as a fallback.
- Make geometry fallback behavior deterministic and fail closed when the circuit cannot be identified confidently.
- Confirm the geometry and telemetry coordinate transforms align. Avoid guessed transforms or silently plausible but incorrect tracks.
- Surface a clear map-unavailable state if no validated geometry matches.

Tests:
- Known circuit resolves locally without a network request.
- Remote HTTP 401/timeout does not block a valid local circuit.
- Unknown circuit does not render another circuit's geometry.
- Invalid/empty/out-of-range geometry is rejected.
- Coordinate transform and track-bound calculations are stable.

### Phase 3 — Live timing transport, decoding, and reducer

**Problem to verify:** `LiveTimingClient.kt` contains SignalR/WebSocket-related parsing and handlers for position, driver list, and session information. End-to-end feed receipt, subscription, decoding, reducer updates, ViewModel state, and UI rendering must be proven rather than assumed.

Tasks:
- Trace the authorised live timing connection lifecycle: negotiation, WebSocket connection, protocol framing, subscription messages, keepalive, reconnect/backoff, cancellation, and session changes.
- Verify each required subscription is sent and acknowledged where the protocol supports acknowledgement.
- Instrument safe counters/state transitions at each stage: connected, subscribed, raw messages received, decoded messages, decompression successes/failures, message types, reducer updates, and UI publication.
- Do not log authentication tokens, cookies, signed URLs, or other secrets.
- Test supported JSON/base64/raw-deflate decoding against captured, non-secret fixtures. Handle malformed payloads without crashing the session.
- Implement robust sparse-delta merging so omitted fields preserve their last valid values, while explicit nulls/removals follow the source protocol's semantics.
- Keep driver, timing, weather, track status, lap count, and position state coherent for the selected session.
- Make stale/disconnected/no-data states explicit. Do not fill missing values with fabricated values.
- Keep reconnects idempotent and avoid duplicate collectors/subscriptions.

Tests:
- Protocol framing and payload decoding.
- Compressed and uncompressed fixture decoding.
- Sparse nested delta merge, null/removal semantics, malformed payload handling.
- Reconnect, cancellation, duplicate subscription prevention, and stale-state behavior.
- Reducer output from representative feed fixtures.

### Phase 4 — Driver Tracker positions and time-based interpolation

Tasks:
- Identify the authoritative position feed and its actual coordinate semantics. Do not assume X/Y/Z means latitude/longitude or that units are interchangeable.
- Decode real position updates and associate entries with the correct driver identifiers using the session's driver list.
- Preserve timestamped position samples and bounded recent trails for each driver.
- Align samples to the replay video playhead in replay mode; use current feed timestamps in live mode.
- Interpolate only between genuine known samples. Use bounded extrapolation only if justified by the source and clearly constrain it.
- Detect stale/missing positions and avoid presenting old markers as current.
- Reset or rebuild trails correctly on backward seek, session change, and replay discontinuity.
- Align track coordinates to the selected validated circuit geometry with a documented transform. Do not invent GPS or map positions.
- Ensure marker motion is smooth but does not drift, teleport through unrelated track sections, or jump across the start/finish seam incorrectly.
- Keep live and replay pipelines separate where their time semantics differ.

Tests:
- Driver ID association.
- Out-of-order, duplicate, stale, and missing samples.
- Interpolation at intermediate timestamps and bounds on extrapolation.
- Backward seek and session-switch trail reset.
- Track coordinate alignment, start/finish seam behavior, and missing geometry handling.

### Phase 5 — Reference-quality native timing tower

Build or complete a dedicated native Compose timing tower, based on actual available feed fields. It should support:

- Position and driver abbreviation.
- Team identity/colour when provided by trusted session metadata.
- Gap to leader and interval to the car ahead.
- Last lap and best lap.
- S1/S2/S3 values and sector status/segments when present.
- Tyre compound, stint/age, and pit state/stops when available.
- Fastest-lap indicator only when supplied by valid session data.
- Weather, track status, session clock/lap count, and race-control messages when available.
- Compact and detailed layouts suitable for phone and Android TV.
- Clear loading, unavailable, stale, reconnecting, and error states.

Do not invent sector bars, timing values, tyre compounds, team colours, fastest-lap status, or race-control messages. Missing fields should be displayed as unavailable or omitted in a consistent way.

### Phase 6 — Integrated layout and controls

- Integrate the native timing tower and Driver Tracker/Track Map as grid panels, not popups.
- Preserve the established layout preference: main video on the left, timing/telemetry in the upper-right area (roughly two-thirds), and Driver Tracker in the lower-right area (roughly one-third), while respecting the existing layout picker and resizing behavior.
- Keep official F1 TV feeds independently selectable and playable.
- Support Android TV D-pad navigation and sensible focus behavior; keep the layout responsive on mobile.
- Avoid blocking video playback while timing or map data loads, reconnects, or fails.
- Keep the timing tower and map useful independently, and ensure both can be shown together in the same multiview layout.

### Phase 7 — End-to-end validation and release evidence

Validate separately for replay/VOD and live sessions:

1. Select the intended session and confirm deterministic session/archive resolution.
2. Confirm timing archive/feed is actually loaded and report useful counters/state.
3. Confirm tower rows are populated from real timing data.
4. Confirm driver positions correspond to real samples and the correct driver identities.
5. Confirm the track map uses the correct validated circuit geometry.
6. Confirm markers move with time, remain aligned, and respond correctly to pause, seek, and backward seek.
7. Confirm session switching clears stale data and loads the new session.
8. Confirm no OpenF1 endpoint or dependency is used.
9. Confirm official feed selection and existing single-feed playback still work.
10. Confirm the combined timing + map + video layout is usable with Android TV D-pad and on mobile.
11. Run relevant unit tests, `testDebugUnitTest`, and `assembleDebug`.
12. Verify the CI run is for the exact intended branch and commit, and verify the APK artifact exists and matches that commit.
13. Record actual device/runtime evidence separately. A green build alone is not proof of working live data, smooth tracking, or correct rendering.

## 5. Current known risks to revisit

- Replay timing may not be loaded on every session-selection path.
- Replay archive matching may be too permissive and can choose the wrong session.
- Remote geometry requests may delay or block known local geometry after HTTP 401.
- Live transport may connect without producing successfully decoded/subscribed timing state; stage-by-stage instrumentation is required.
- Position parsing may produce samples without proving timestamp alignment, driver association, or coordinate compatibility.
- The existing map renderer may not provide continuous playhead-based interpolation or correct trail reset on backward seek.
- The existing timing panel may not yet match the reference's row density, sector details, tyre/stint presentation, weather, and race-control information.
- An earlier screenshot showed a circuit-geometry HTTP 401. Confirm the exact APK commit and failing code path before attributing the issue to the current branch.
- Keep the separate protected-video black-picture/audio symptom isolated from this timing/map milestone unless specifically requested.

## 6. Work order and completion criteria

Implement in this order:

1. Replay timing load and deterministic session resolution.
2. Local-first circuit geometry fallback and identity validation.
3. Live timing transport/decoding instrumentation and reducer correctness.
4. Driver tracker position mapping and interpolation.
5. Native reference-quality timing tower.
6. Integrated timing + map + video layout and interaction polish.
7. Replay/live end-to-end validation, tests, CI/APK verification, and device evidence.

A phase is complete only when its relevant tests pass and its behavior is verified with real or representative fixtures. The overall milestone is complete only when the timing tower and Driver Tracker/Track Map work together with video in replay and live modes, use real data, handle seek/session changes correctly, and preserve official feed selection and single-feed playback. Clearly document any behavior that remains unverified on a physical device.
