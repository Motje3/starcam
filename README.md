# StarCam

A OnePlus-15-specific astrophotography camera app. One button — **ASTRO** — does the whole job:
waits for tripod stillness → probes the sky and picks exposure/ISO → autofocuses **on the stars
themselves** (FWHM sweep) → shoots a burst of manual RAW frames → star-aligns and sigma-clip-stacks
them **on the phone** in native C++ → astro post-processing (gradient removal, asinh stretch,
chroma denoise) → finished JPG in the gallery + 16-bit linear TIFF master + optional DNGs of every
frame for PC stacking (Siril/PixInsight/Sequator).

## Modes
- **ASTRO** — Fast (8 frames) / Best (20 + darks) / Insane (40 + darks + foreground composite)
- **TRAILS** — star-trail lighten compositing over 5–60 minutes
- **METEOR** — continuous capture + streak detection; planes/satellites classified out (beta)
- **PRO** — full manual everything

Every automatic decision is overridable in the Pro panel (30+ toggles/sliders), including
"stack on device" off = pure RAW intervalometer. Red night-vision UI with screen dim. Gyro
stillness gating + per-frame bump rejection. Thermal-aware frame pacing. Live stack preview
while capturing. Built-in **engine self-test** (synthetic starfield through the real pipeline —
verify the math in daylight) and a **capability probe** that dumps everything the OnePlus 15
exposes through Camera2.

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
(engine working files, kept only with "Keep raw cache" for in-app re-stacking).

Requires: Android Studio (Ladybug or newer), a OnePlus 15 (any RAW-capable Android 12+ device
works, adaptively), no other setup — Gradle 8.9 / AGP 8.7.3 / NDK 27 pinned in the project.
