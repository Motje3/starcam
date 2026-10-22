# StarCam

A OnePlus-15-specific astrophotography camera app. One button — **ASTRO** — does the whole job:
waits for tripod stillness → probes the sky and picks exposure/ISO → autofocuses **on the stars
themselves** (FWHM sweep) → shoots a burst of manual RAW frames → star-aligns and sigma-clip-stacks
them **on the phone** in native C++ → astro post-processing that finds the landscape and treats
it apart from the sky (light-pollution removal on the sky only, a dark sky, the ground's own
detail, star colour, wavelet noise reduction) → finished JPG in the gallery, upright the way the
phone was held + 16-bit linear TIFF master + optional DNGs of every frame for PC stacking
(Siril/PixInsight/Sequator).

Around the shot: a **Tonight planner** (when it gets dark, the Moon, when and where the Milky
Way's core stands, a sight that brings the phone onto it — all computed on the phone), an
**editing studio** that develops a session's master again with your own sky/landscape
brightness, contrast, colour and noise settings (saved as a new photo), and **1 frame vs
stacked**, a drag-to-compare view of what the stacking did.

## Modes
- **ASTRO** — Fast (8 frames) / Best (20 + darks) / Insane (40 + darks + foreground composite)
- **TRAILS** — star-trail lighten compositing over 5–60 minutes
- **METEOR** — continuous capture + streak detection; planes/satellites classified out (beta)
- **PRO** — full manual everything

The capture screen keeps to the essentials (viewfinder, mode, preset, shutter); the **Pro sheet**
holds the quick manual overrides and **Settings** every automatic decision (40+ settings in eight
tabs, searchable, each with a plain-language explanation), including "stack on device" off = pure
RAW intervalometer. Each session step has its own full screen with live clocks, the live stack and
a frame tape. Red night-vision UI with screen dim. Gyro stillness gating + per-frame bump
rejection. Thermal-aware frame pacing. Built-in **engine self-test** (synthetic starfield through
the real pipeline — verify the math in daylight) and a **capability probe** that dumps everything
the OnePlus 15 exposes through Camera2. UI design: `design/handoff/` (tokens, screens, components).

## Stack
Kotlin + Jetpack Compose (Material3, dark-only) · Camera2 directly (no CameraX) · C++17 native
engine via NDK/CMake (star detection, triangle/RANSAC alignment, tile-based sigma-clip stacking,
Malvar demosaic, post pipeline — no OpenCV, all ours) · zero third-party runtime dependencies.

- `PLAN.md` — the authoritative architecture/algorithms spec
- `DEBUGGING.md` — logs on the phone, sharing them from the field, pulling them over USB, the
  camera test, building from the command line (start here!)
- `MORNING.md` — the original setup notes and first-night guide
- `app/src/main/cpp/` — native engine
- `app/src/main/java/com/wm/starcam/` — app

Sessions land in `Android/media/com.wm.starcam/StarCam/sessions/<timestamp>_<mode>/` — visible
over USB: `lights/*.dng`, `darks/*.dng`, `out/` (final JPG + `master_linear16.tiff` +
`stack_report.json`), `meta/session.json` (every frame's stats & rejection reasons), `cache/`
(engine working files, kept only with "Keep raw cache" for in-app re-stacking). A `.nomedia`
marker keeps the StarCam folder out of gallery apps; the finished photo goes to the gallery
under `Pictures/StarCam`.

Requires: a OnePlus 15 (any RAW-capable Android 12+ device works, adaptively). Builds from the
command line (`tools\setup-toolchain.ps1`, then `tools\build.ps1`, see `DEBUGGING.md`) or Android
Studio — Gradle 8.13 / AGP 8.13.2 / NDK 27 / compile and target SDK 36 pinned in the project.
Release builds (`gradlew bundleRelease`) are minified and signed from `keystore.properties`
(see `keystore.properties.example`).
