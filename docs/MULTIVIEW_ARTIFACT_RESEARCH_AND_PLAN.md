# MultiViewer 2.9.3 and F1 TV 3.0.49.4: static research and Android plan

Date: 2026-10-14
Branch: `codex/single-decoder-multiview`
Base: `feature/tiledmedia-multiview-rearchitecture` at `f7dd4415`

## Scope and evidence

This is static analysis of the two supplied packages. Neither application was run, no account or stream was used, and decoder count was not measured. It describes observable component boundaries, not a copy of vendor algorithms or private protocols.

| Artifact | Identity | Static observations |
| --- | --- | --- |
| `C:\Darshak\private\MultiViewer-win32-x64-2.9.3\resources\app.asar` | MultiViewer `package.json` version 2.9.3; 151,313,342-byte ASAR | Electron/Webpack package. `package.json` lists `bitmovin-player` 8.206.0 and `shaka-player` 5.2.12. `.webpack/renderer/players/index.js` contains distinct Shaka and Bitmovin player controls, per-player `video` access, synchronization, channel panning, and Widevine license error handling. The player UI is packaged separately from F1 catalog/video pages. No TiledMedia reference was found in the inspected player entry bundle. |
| `C:\Darshak\AI\Tools\project\com.formulaone.production_3.0.49.4-SP166.4.1-release-R54.2-mobile-30494002_minAPI29(arm64-v8a,armeabi-v7a,x86,x86_64)(nodpi)_apkmirror.com.apk` | SHA-256 `b5b277ad22a2751b10bfc96aaa961b5023b10bfdc4f86936d309ae80ee89c6dd`; 107,899,904 bytes; 8 DEX files | The APK contains `com/avs/f1/ui/tiledmediaplayer/*` classes for layout, controls, feed metadata, device support, and analytics; many `com/tiledmedia/clearvr*` classes; `libClearVRNativeRendererPlugin.so` and `libClearVRUtils.so` for all four packaged ABIs; Media3 and Bitmovin code also appear. This establishes an app-specific tiled player path and bundled vendor runtime. It does not reveal which path is selected for a particular entitlement/device/session or prove one active decoder. |

REA opened the APK as an archive and confirmed its hash. REA's JADX-backed Android inspection could not run because `REA_JADX_MCP_JAR` is not configured. REA analyzed four selected MultiViewer JavaScript bundles from the extracted ASAR, but its semantic graph was partial and the full-ASAR request timed out. The local scripts and raw matches in the parent project directory provide repeatable package-level checks; extracted ASAR files are inspection material only.

### What the comparison means

MultiViewer's packaged architecture is consistent with separately instantiated browser players that are synchronized at the application layer. Its ability to show multiple feeds is **not evidence of one decoder**. The F1 TV APK has a distinct tiled playback path, but the static package inventory does **not** establish the exact protected bitstream transformation or its source/license contract. Do not transplant ClearVR code, metadata, or protocol into this app.

An [AWS account of F1 TV Premium](https://aws.amazon.com/blogs/media/f1-sets-new-standard-in-sports-streaming-with-f1-tv-premium-using-aws/) describes upstream HEVC tile encoding with aligned tile settings and client bitstream rewriting/merging. That public description explains why a single decoder could consume selected tiles. It does not prove that the authorized URLs currently returned to this project contain compatible tile representations.

## Current branch diagnosis

`AppOwnedTiledStreamDescriptor` validates a proposed authorized tiled stream and `SingleStreamTiledPlaybackPolicy` guards multi-feed playback. `UnifiedMultiviewEngine.updateViewportInternal` currently passes `descriptor = null` for more than one visible video feed and falls back to the main feed. No code connects selected F1 feeds to a validated tiled input or a one-decoder media session. The clear synthetic prototype exercises three decoders plus one compositor, which tests multi-decoder rendering only.

The existing plan records selected authorized feeds as AVC in prior device logs and Widevine session/init-data failures. Those are historical observations in this repository, not reverified in this research. The immediate blocker is the missing authorized, tile-compatible source contract, rather than the layout UI.

## Proposed implementation gates

1. **Establish the input contract.** With the user's authorized F1 account and a permitted test session, record redacted manifest metadata for the exact selected feeds: codec/profile, tile compatibility, segment timing and keyframes, DRM signaling/key IDs, and allowed license/output path. Confirm whether an official combined representation is offered or an independently implementable standards-based compressed-domain packager is permitted. Do not store tokens, keys, licenses, or media payloads in the repository. If the available feeds remain ordinary AVC, one decoder cannot show independent feed choices without upstream transcoding/encoding.
2. **Prove one decoder with clear synthetic HEVC tiles.** Build original test media using a controlled encoder/packager outside the F1 path. Verify a single valid HEVC access-unit stream, selectable 2/3/4-tile layouts, one Android `MediaCodec` decoder, one output surface, correct timestamps, and seek/layout changes. This validates the local renderer and stream assembly independently of entitlement and DRM.
3. **Connect an authorized protected source.** Only after Gate 1 succeeds, create an app-owned resolver returning one coded stream plus explicit Widevine/session metadata. Feed that into one Media3 or secure `MediaCodec` playback session, with secure surface output and no protected-pixel readback. Keep the existing single-feed playback available while the multiview gate is unsatisfied. Android's [Media3 DRM guidance](https://developer.android.com/media/media3/exoplayer/drm) and [secure codec API](https://developer.android.com/reference/android/media/MediaCodec) define the public playback boundary.
4. **Validate on physical phone and Android TV.** Record actual codec instance count/name, protected output status, first frame, dropped frames, PTS drift, seek/layout behavior, rebuffering, memory, battery/thermal load, and 30-minute 2/3/4-feed runs. A JVM test or APK build cannot establish this.

### Architecture decision

The Android device should run **one video decoder** for one coded tiled stream. Encoding happens upstream. If the user means a single *encoder* running on the Android device, that would require decoding the separate inputs first and would not solve the decoder-capacity problem; protected F1 pixels may also be unavailable for re-encoding. If no authorized tile-compatible source or permitted upstream packaging route exists, keep the production multiview gate closed and report that dependency instead of presenting synchronized independent players as a one-decoder solution.

## Next concrete task

Implement a small source-inspection harness in this branch that accepts user-supplied, authorized, redacted manifest samples and emits only codec/timing/DRM-presence metadata. Use its result to decide whether Gate 2 can target the real source contract. This requires an actual authorized sample; the shipped APK alone cannot supply it.
