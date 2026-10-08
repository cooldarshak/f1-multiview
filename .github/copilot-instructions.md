# F1 MultiView Copilot instructions

## Project
This repository is a native Android + Android TV F1 MultiView application. The target playback architecture is compressed-domain multiview merging of F1 CMAF/HEVC tile feeds into ONE HEVC elementary stream, followed by ONE MediaCodec decoder and GPU compositor/layout.

## Authoritative development branch
The active implementation branch is `feature/tiledmedia-multiview-rearchitecture`.
The current isolated native-TME work branch is `work/tme-native-complete`.

For the current TME completion work:
- Work only on `work/tme-native-complete` until the owner explicitly approves integration.
- Never checkout, modify, merge into, rebase onto, or push to `main`.
- Never modify `feature/tiledmedia-multiview-rearchitecture` unless explicitly instructed to integrate.
- Do not create or trigger GitHub Actions workflows.
- Do not use `gh workflow run`, workflow dispatch, or any equivalent CI trigger.
- Do not push any branch automatically. Local validation must finish without publishing changes.

## Engineering rules
1. FIX, DON'T DELETE.
2. NO SHORTCUTS.
3. NO BYPASS.
4. Do not add guards that hide a real defect.
5. Do not replace native TME with multiple ExoPlayers.
6. Preserve the ONE merged HEVC stream -> ONE MediaCodec -> ONE decoder architecture.
7. Preserve DRM/Widevine boundaries. Never bypass DRM.
8. Do not claim a fix without evidence.
9. GitHub Actions is the final clean-build gate and must only be run after explicit owner approval.
10. Batch meaningful changes so one final integration requires one CI build.

## Local validation loop
Use:
`./scripts/local-tme-validate.sh`

The script is intentionally branch-locked and must refuse to run on `main` or `feature/tiledmedia-multiview-rearchitecture`. It must not invoke GitHub Actions, push, create PRs, or use `gh`.

Local validation should cover:
- native TME/GPAC prerequisites
- GPAC compressed-domain smoke test when host prerequisites exist
- Gradle unit tests
- debug APK assembly
- APK/native-library packaging checks where available

If a prerequisite is unavailable, report it explicitly. Do not silently substitute a fake merger or multiple-player implementation.

## Current native TME direction
The native boundary uses GPAC `hevcmerge` and `hevcsplit`. The merger must remain a real compressed-domain operation. The Android native library must fail clearly when the real GPAC dependency is unavailable rather than silently building a fake implementation.

## Evidence standard
A local build is not equivalent to a final CI success. A final build is only considered successful when workflow success, `testDebugUnitTest`, `assembleDebug`, APK artifact existence, native library packaging, and applicable runtime evidence are verified.
