# StarCam

A OnePlus-15-specific astrophotography camera app. One button — **ASTRO** — does the whole job:
waits for tripod stillness → probes the sky and picks exposure/ISO → autofocuses **on the stars
themselves** (FWHM sweep) → shoots a burst of manual RAW frames → star-aligns and sigma-clip-stacks
them **on the phone** in native C++ → astro post-processing that finds the landscape and treats
it apart from the sky (light-pollution removal on the sky only, a dark sky, the ground's own
detail, star colour, wavelet noise reduction) → finished JPG in the gallery, upright the way the
phone was held + 16-bit linear TIFF master + optional DNGs of every frame for PC stacking
(Siril/PixInsight/Sequator).

**What's in this photo** names everything in a finished photo from its own stars - the
constellations drawn in, the stars and how far away they are, the planets, the nebulae and
galaxies, and how long their light was on its way - with a labelled copy to save and share.

A **sky calendar** lays out the next twelve months where you are - meteor showers rated for the
Moon and your own sky, the Milky Way's nights and season, eclipses as seen from your place,
planets at their best - each one a tap from the sky view at that moment, with an afternoon alert
on nights worth going out for.

A **dark-sky map** shows the light pollution around you (built into the app from 2025 satellite
night lights and a published sky-glow model, so it works offline), how dark your own sky is, and
the darker places within reach - with the way there in your maps app and the night planned there.

Around the shot: a **sky view** that draws the Milky Way and its core, the constellations, the
planets and the Moon over the viewfinder where they really are — now, or slid ahead to plan a
frame by daylight — and a **Tonight planner** (whether tonight is a Milky Way night, the best
window, when it gets dark, the Moon, how high the core climbs), all computed on the phone; an
**editing studio** that develops a session's master again with your own sky/landscape
brightness, contrast, colour and noise settings (saved as a new photo), **1 frame vs
stacked**, a drag-to-compare view of what the stacking did, and **watch it build up**, a short
video of the live stack growing from the first noisy frame into the finished photo (made on the
phone, saved to the gallery).

While ASTRO shoots, an **autopilot** watches every frame: frames spoiled by cloud or soft stars are
left out and replaced, passing cloud is waited out, a drifting focus is refocused on the stars,
dawn or moonrise ends the session with the good frames, and "shoot for 30 minutes" replaces the
frame count when time is what you have.

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
`stack_report.json`), `meta/session.json` (every frame's stats & rejection reasons), `replay/`
(the live stack after each frame, for the replay video), `cache/` (engine working files, kept
only with "Keep raw cache" for in-app re-stacking). A `.nomedia` marker keeps the StarCam folder
out of gallery apps; the finished photo goes to the gallery under `Pictures/StarCam`, a replay
video under `Movies/StarCam`.

Requires: a OnePlus 15 (any RAW-capable Android 12+ device works, adaptively). Builds from the
command line (`tools\setup-toolchain.ps1`, then `tools\build.ps1`, see `DEBUGGING.md`) or Android
Studio — Gradle 8.13 / AGP 8.13.2 / NDK 27 / compile and target SDK 36 pinned in the project.
Release builds (`gradlew bundleRelease`) are minified and signed from `keystore.properties`
(see `keystore.properties.example`).
