# StarCam — Master Build Plan (authoritative spec)

> Written by Fable at full planning depth on 2026-08-17 night. If a different model (Opus) continues this
> build: **follow this document exactly.** It defines architecture, file layout, algorithms with parameters,
> JNI contracts, and the implementation order. Check off progress in `PROGRESS.md` (same folder) after every
> file so a takeover can resume mid-stream. Do not redesign; implement.

## 0. Mission

A OnePlus-15-specific astrophotography Android app. One giant **ASTRO** button that: waits for tripod
stillness → probes exposure → autofocuses on the stars themselves → shoots N RAW frames with full manual
sensor control → star-aligns and sigma-clip-stacks them **on the phone** in C++ → applies an astro
processing pipeline → saves finished JPG + 16-bit linear TIFF master + (optional) all sub-frame DNGs for PC
post-processing. Plus Star Trails mode, Meteor mode, a Pro panel where every automatic decision is
overridable, a Camera2 capability probe screen, and an engine self-test that validates the whole stacking
pipeline with synthetic starfields (testable in daylight).

User constraints:
- Everything toggleable — Auto works with one button, Pro exposes every knob.
- Nice dark UI/UX + red night-vision theme.
- Push the phone to its limit; PC (Siril etc.) remains an optional last mile, never a requirement — so keep DNGs.
- User tests manually in the morning; no compiler on this PC tonight, so code must be written conservatively
  against **known-good, pinned versions** (below) and reviewed hard.

## 1. Toolchain & versions (pinned, known-good — do not "upgrade")

- Gradle **8.9** (wrapper), Android Gradle Plugin **8.7.3**, Kotlin **2.0.21**,
  Compose via `org.jetbrains.kotlin.plugin.compose` **2.0.21** + `androidx.compose:compose-bom:2024.12.01`.
- `compileSdk 35`, `targetSdk 35`, `minSdk 31` (OnePlus 15 ships Android 15/API 35; 31 keeps flexibility and
  gives us `CameraExtensionCharacteristics`).
- NDK **27.0.12077973**, CMake **3.22.1** (both auto-installed by Android Studio on first sync), C++17,
  abiFilters: `arm64-v8a` only (OnePlus 15 is arm64; halves build time and APK size).
- Dependencies (deliberately minimal — fewer version risks):
  `androidx.core:core-ktx:1.15.0`, `androidx.activity:activity-compose:1.9.3`,
  `androidx.lifecycle:lifecycle-runtime-ktx:2.8.7`, `androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7`,
  compose-bom + `ui`, `ui-graphics`, `ui-tooling-preview`, `material3`, `material-icons-extended`,
  `androidx.exifinterface:exifinterface:1.3.7`.
  **No** navigation lib (own sealed-class router), **no** kotlinx-serialization (use `org.json` from the
  platform), **no** CameraX, **no** OpenCV (all native code is ours).
- `applicationId` / namespace: `com.wm.starcam`.
- Camera2 only (`android.hardware.camera2`). JNI via CMake `externalNativeBuild`.

## 2. Project layout

```
StarCam/
  PLAN.md  PROGRESS.md  README.md  MORNING.md            ← docs
  settings.gradle.kts  build.gradle.kts  gradle.properties  gradlew  gradlew.bat
  gradle/wrapper/gradle-wrapper.properties (+ .jar if downloadable tonight)
  app/
    build.gradle.kts  proguard-rules.pro
    src/main/
      AndroidManifest.xml
      java/com/wm/starcam/
        MainActivity.kt  Router.kt
        ui/theme/{Color.kt, Theme.kt, Type.kt}
        ui/{CaptureScreen.kt, ProPanelSheet.kt, SessionsScreen.kt, SessionDetailScreen.kt,
            ProbeScreen.kt, SelfTestScreen.kt, components/{Hud.kt, Histogram.kt, BigButton.kt,
            LiveStackView.kt, NightOverlay.kt, SettingRows.kt}}
        camera/{CameraController.kt, CaptureEngine.kt, CapabilityProbe.kt, AutoExposure.kt,
                FocusSweep.kt, DngWriter.kt, CamMeta.kt}
        sensors/{MotionMonitor.kt, ThermalMonitor.kt}
        session/{SessionRepository.kt, SessionMeta.kt, SettingsStore.kt}
        engine/{NativeEngine.kt, StackConfig.kt, EngineResult.kt}
        util/{MediaSaver.kt, Fmt.kt, AppLog.kt}
      cpp/
        CMakeLists.txt
        jni_bridge.cpp            ← all JNI entry points; keep signatures EXACTLY in sync w/ NativeEngine.kt
        engine.h/.cpp             ← EngineSession orchestrator (state, frame ingest, finalize)
        sraw.h/.cpp               ← StarCam raw cache format (header + uint16 Bayer planes) read/write
        bayer.h/.cpp              ← black level, WB gains, superpixel luma extraction, Malvar demosaic
        stars.h/.cpp              ← background grid, star detection, centroids, FWHM
        align.h/.cpp              ← star matching (predicted-drift NN + RANSAC similarity fit)
        stack.h/.cpp              ← tile-based warp-on-the-fly sigma-clip stacking
        post.h/.cpp               ← gradient removal, asinh stretch, saturation, chroma denoise, sharpen
        mask.h/.cpp               ← sky/ground mask + feathered composite (beta)
        trails.h/.cpp             ← max-value (lighten) star-trail compositing
        meteor.h/.cpp             ← frame differencing + streak classification (beta)
        tiffout.h/.cpp            ← minimal uncompressed 16-bit TIFF writer (no deps)
        selftest.h/.cpp           ← synthetic starfield generator + end-to-end pipeline validation
        common.h                  ← types (Image16, ImageF, Transform), threadpool, log macros
      res/
        values/{strings.xml, themes.xml}
        drawable/ic_star_vector.xml, mipmap-anydpi-v26 adaptive icon xml, values colors for icon
```

## 3. Camera capture design

### 3.1 Capability probe (ProbeScreen)
On open, enumerate all camera ids; for each dump: hardware level, `REQUEST_AVAILABLE_CAPABILITIES`
(check MANUAL_SENSOR, RAW), `SENSOR_INFO_EXPOSURE_TIME_RANGE`, `SENSOR_INFO_SENSITIVITY_RANGE`,
`SENSOR_MAX_ANALOG_SENSITIVITY`, RAW sizes + `SENSOR_INFO_PIXEL_ARRAY_SIZE` / active array,
`LENS_INFO_MINIMUM_FOCUS_DISTANCE` + focus calibration, `LENS_INFO_AVAILABLE_APERTURES` / focal lengths,
CFA pattern, black/white level, `HOT_PIXEL` modes, `TONEMAP` modes, `CameraExtensionCharacteristics`
NIGHT availability. Render as monospace text + "Copy to clipboard" + "Save report to session folder"
buttons. **The whole app adapts at runtime** to these values (exposure clamps, RAW size choice), so it
works whatever OnePlus exposes; the probe exists for the human (and for tuning defaults later).

### 3.2 Camera selection & formats
- Back camera with RAW + MANUAL_SENSOR and the largest pixel array = main sensor; prefer the **smallest
  RAW_SENSOR size ≥ 9 MP** if several are offered (binned RAW = better SNR, 4× less data); Pro toggle
  "Full-res RAW" switches to max size.
- Surfaces: preview `SurfaceView` (via `AndroidView`) + `ImageReader(RAW_SENSOR, maxImages = 3)`.
  JPEG surface not needed (final JPEGs come from the engine).
- Preview while framing: repeating request, AE on with long-ish exposure boost (`CONTROL_AE_MODE_ON`,
  compensation +2) so the user can frame in darkness; "framing gain" slider in HUD sets
  manual preview exposure (e.g. 1/2 s, ISO 12800) when AE gives up — implemented as manual repeating
  request when toggled.

### 3.3 Burst capture (the core loop)
State machine in `CaptureEngine` (coroutines + `StateFlow<CaptureState>`):
`Idle → WaitingStill → Probing → Focusing → CapturingLights(i/N) → CapturingDarks(i/M) → Stacking(pct) → Done(result) | Error(msg)`
- **WaitingStill**: MotionMonitor gyro RMS < 0.006 rad/s continuously for 1.5 s (both thresholds in
  Pro panel). Also honors "start delay" setting (0/3/10 s).
- **Probing**: single manual frame, `min(1s, maxExposure)`, ISO 3200 → native `probeExposure` returns
  background median (fraction of white level), star count, FWHM → `AutoExposure.decide()` (§5.1).
- **Focusing** (if focus mode = AutoStar): FocusSweep (§5.2). Else set `LENS_FOCUS_DISTANCE` = 0.0
  (Infinity) or manual slider value (diopters).
- **CapturingLights**: sequential `capture()` calls (never repeating bursts — thermal + gyro windows
  between frames). Each request: `CONTROL_MODE_OFF`, `SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY`,
  `LENS_FOCUS_DISTANCE`, `NOISE_REDUCTION_MODE_OFF`, `HOT_PIXEL_MODE_OFF` (if supported),
  `SHADING_MODE_FAST`, `CONTROL_AWB_MODE_OFF` + fixed `COLOR_CORRECTION_GAINS`/transform captured once
  from the probe frame's AWB result (locked WB across the session).
  On image + result: (a) hand Bayer buffer to native `addFrame` (writes .sraw cache, updates live stack,
  returns per-frame stats), (b) if "Save DNGs" on, write DNG via `DngCreator` on an IO dispatcher,
  (c) MotionMonitor verdict for the exposure window → if bumped, mark rejected; if "auto-reshoot" on,
  N target grows by 1 (cap +25%).
- **CapturingDarks** (toggle, default ON for Best/Insane): prompt overlay "Cover the lens", then M=4
  frames at identical exposure/ISO. Native accumulates a master dark (mean) + hot-pixel map (dark
  pixels > median + 6·MAD flagged).
- **Stacking**: `finalizeStack` on Default dispatcher with JNI progress callback → UI percent.
  Cancel button → `abort()` → engine stacks what it has (if ≥ 4 frames) or discards.
- Screen: `FLAG_KEEP_SCREEN_ON` during session; NightOverlay dims UI; thermal status surfaced in HUD;
  if `PowerManager.getCurrentThermalStatus() ≥ THERMAL_STATUS_SEVERE`, insert 2 s inter-frame cooldown
  and show a flame icon.

### 3.4 Modes (all just configs over the same engine)
- **ASTRO** — Fast = 8 lights / darks off; Best = 20 lights / 4 darks; Insane = 40 lights / 4 darks +
  foreground composite enabled. All numbers editable in Pro.
- **TRAILS** — continuous frames (default 30 s total-session slider 10–60 min, per-frame exposure from
  auto), native `trails` lighten-composite, no alignment.
- **METEOR** — continuous like trails; per frame native runs streak detection (§6.6); session end:
  sigma-stack base + lighten-blend meteor frames; also saves each detected-candidate DNG regardless of
  "Save DNGs" toggle. Marked *beta* in UI.
- **PRO** — plain manual: the current Pro-panel settings, shoot N frames, stack or not per toggle.

## 4. Native engine — data flow

Working format: **.sraw** cache, one file per light frame, written immediately in `addFrame`
(crash-safe: a power loss loses nothing already shot). Header (little-endian):
`magic 'SRAW' u32 | version u32 | width u32 | height u32 | cfa u32 (0=RGGB,1=GRBG,2=GBRG,3=BGGR) |
whiteLevel u32 | blackLevel float[4] | wbGain float[4] | ccm float[9] | exposureNs u64 | iso u32 |
timestampNs u64 | reserved 64B` then `uint16[width*height]` row-major Bayer.
DNGs are archival only; the engine never parses DNG. sraw cache is deleted after a successful stack
unless "Keep raw cache" toggle is on.

Pipeline at `finalizeStack`:
1. Reference selection: frame with max(starCount · exp(−FWHM)) among unrejected frames.
2. Per-frame transforms already estimated incrementally during capture (§6.3); refine pass re-fits
   every frame against the reference star list.
3. Frame rejection: gyro-bumped, starCount < 25 % of reference, FWHM > 1.8× reference, alignment
   residual > 2 px, or matched-star inliers < 8. Rejections logged with reasons into session.json.
4. Tile stacking (§6.4) → linear RGB float stack.
5. Optional foreground pass (§6.5) + composite.
6. Post pipeline (§6.7) → final 8-bit sRGB + 16-bit linear TIFF.
7. Outputs: `out/starcam_YYYYMMDD_HHMM.jpg` (via Kotlin `MediaSaver` into MediaStore
   `Pictures/StarCam/` + copy in session), `out/master_linear16.tiff`, `out/stack_report.json`.

## 5. Auto decisions (Kotlin side)

### 5.1 AutoExposure.decide(probeStats, caps, mode)
- Trailing cap: `maxTrailExp = 8 s` for the main lens (~24 mm equiv; NPF-informed default, Pro-editable).
- `exp = min(deviceMaxExposure, maxTrailExp)`; if deviceMax < 2 s, multiply frame count by `8s/deviceMax`
  (capped ×6) — the "OEM capped exposure" survival path.
- ISO: scale from probe so sky background lands at **6 % of white level** (linear, post-black-sub):
  `iso = clamp(3200 · (0.06/bgFrac) · (probeExp/exp), isoRange)` snapped to {200,400,800,1600,3200,6400};
  prefer ≤ 1600 when reachable (highlight headroom for stars).
- Returns (exp, iso, frames, reasoning-string) — reasoning shown in HUD ("8 s · ISO 800 · 20×,
  sky 6 %, trailing-limited"), every value overridable.

### 5.2 FocusSweep (star autofocus)
Coarse: 7 focus positions over diopters `[0, 0.30]` (`d = 0.05·i`), each: 2 s ISO 6400 frame →
native `analyzeFocus` → median FWHM of top-30 stars (needs ≥ 8 stars else fall back to Infinity and
report why). Fine: parabola through best 3 points → 3 more shots ±0.02 around vertex → set minimum.
Duration ≈ 40 s. Lock `LENS_FOCUS_DISTANCE` for the session; show achieved FWHM in HUD.

## 6. Native algorithms (parameters chosen — implement as written)

### 6.1 Luma + background + star detection (`stars.cpp`)
- Luma: 2×2 Bayer superpixel → `(R + 2G + B)/4` at half resolution, float, black-subtracted, WB-applied.
- Background: 48×48-cell grid → per-cell median; noise σ per cell via MAD·1.4826; bilinear-interpolated
  background/noise surfaces.
- Detection: threshold `bg + 4.5σ`; 8-connected components; area 2–120 px; reject aspect ratio > 2.5
  (satellite bits); flux-weighted centroid; FWHM from second moments (`2.355·sqrt((mxx+myy)/2)`);
  keep top 300 by flux. Sub-pixel accuracy ~0.1 px is enough (we fit transforms over dozens of stars).

### 6.2 (reserved)

### 6.3 Alignment (`align.cpp`)
Incremental: frame k's initial guess = frame k−1's transform. Predict each reference star's position,
NN-match within 12 px (luma scale), require flux ratio in [0.4, 2.5]. Fit **similarity transform**
(a,b,tx,ty: x' = a·x − b·y + tx, y' = b·x + a·y + ty) by least squares inside RANSAC
(400 iters, 2-point minimal sample, inlier < 1.5 px, refit on inliers). Store transform at **full raw
resolution** (×2 from luma coords). If inliers < 8 → bootstrap fallback: triangle-similarity vote using
brightest 25 stars (all triangles, invariant = sorted side ratios, hash-match tolerance 1 %), then RANSAC
as above; if still failing, mark frame unaligned/rejected. First frame = identity.

### 6.4 Stacking (`stack.cpp`)
Output = reference frame's geometry, linear RGB float32 (memory: W·H·3·4 B; 12.5 MP → 150 MB — OK.
If full-res 50 MP requested: process output in two horizontal halves sequentially → 300 MB peak).
Tiles 512×512 output px. Per tile, per frame: inverse-transform tile rect → source rect (+8 px pad);
Malvar-demosaic that source region from the mmap'd sraw (black-sub, WB gains, white-norm to [0,1]);
bilinear-sample warped pixels into `tileStack[frame][512·512·3]` (float16 storage acceptable? **no —
use float32**, memory 512·512·3·4·40 ≈ 126 MB transient, fine). Master-dark subtract + hot-pixel map
substitution (bilinear of neighbors) happen on the Bayer tile before demosaic.
Combine per pixel-channel across frames: 2-iteration sigma clip — mean/σ, reject > 2.8σ (and > 3.5σ on
first iter), final mean of survivors; if < 3 survive, use median. Track per-tile rejection % for the
report. Multithread tiles across `std::thread` pool (hw_concurrency − 2).

### 6.5 Foreground + composite (`mask.cpp`, beta)
Foreground stack = same frames, **identity transforms**, sigma clip (stars trail → clipped or smeared;
ground stays sharp). If "long foreground exposures" toggle on, capture 2 extra frames at 4× exposure /
min ISO after darks and use only those. Sky mask on the star-aligned stack: luma at 1/8 res → Otsu
threshold + "sky must touch top edge" flood fill + close(5) → upsample, 40 px feather. Composite:
`final = mask·skyStack + (1−mask)·fgStack` with exposure-ratio normalization. Toggle "Composite
foreground" (default on only in Insane); always also save the pure star-aligned stack.

### 6.6 Meteor detection (`meteor.cpp`, beta)
Per frame vs running median-of-last-5 luma: diff > bg + 6σ → components; streak = length ≥ 25 px,
aspect ≥ 4, solidity along fitted line ≥ 0.7. Classification: present along same line in ≥ 3 consecutive
frames → satellite/plane (reject); blinking pattern (alternating presence) → plane (reject); single-frame
(or 2-frame) → meteor candidate. Composite: lighten-blend candidate's diff region (aligned) onto base
stack. Save candidates list w/ frame ids to session.json.

### 6.7 Post pipeline (`post.cpp`) — all stages toggleable via StackConfig
1. Gradient removal: sample 32×32 grid medians excluding star mask + brightest 20 % cells → fit 2nd-order
   2-D polynomial per channel (least squares, 6 coeffs) → subtract, re-add global median.
2. White balance trim: gray-world on background pixels only (cap gain trim 0.9–1.1).
3. Stretch: black point = 0.25-percentile per channel − 0.5σ; asinh stretch
   `y = asinh(k·x)/asinh(k)` with k auto-chosen so sky background lands at 0.09 (search k in [10, 900]);
   optional extra midtone gamma 0.9.
4. Saturation: ×1.25 in chroma (YCbCr), highlight-protected.
5. Chroma denoise: 2× box-blur passes on Cb/Cr at 1/4 res, luma untouched.
6. Star sharpen (off by default): unsharp 1.5 px amount 0.3 masked to star mask dilation.
7. sRGB encode (CCM applied back in stacking stage: raw→sRGB linear via sensor CCM from sraw header).

### 6.8 Self-test (`selftest.cpp`)
`runSelfTest(tmpDir)`: synthesize 16 frames 2048×1536 RGGB: 400 Gaussian stars (FWHM 2.2 px, flux
log-uniform), known per-frame similarity transforms (rot up to 0.15°, drift up to 18 px cumulative),
Poisson-ish noise, 200 fixed hot pixels, 1 satellite streak crossing 6 frames, vignetting + linear
gradient. Write as sraw → run the full real pipeline (detect/align/stack/post) → assert: median
alignment residual < 0.35 px, hot pixels removed (no output pixel > 5σ at hot locations), streak
suppressed (< 15 % of its single-frame amplitude), star FWHM in stack ≤ 1.15× input FWHM.
Returns JSON {pass, metrics per assertion}. SelfTestScreen shows PASS/FAIL + numbers. This is the
morning's daylight QA tool.

## 7. JNI contract (`NativeEngine.kt` ⇄ `jni_bridge.cpp`) — keep EXACTLY in sync

```kotlin
object NativeEngine {                      // System.loadLibrary("starcam")
  external fun createSession(configJson: String): Long          // 0 on failure
  external fun addFrame(h: Long, bayer: ByteBuffer, width: Int, height: Int, rowStrideBytes: Int,
                        metaJson: String): String               // per-frame stats JSON
  external fun addDarkFrame(h: Long, bayer: ByteBuffer, width: Int, height: Int, rowStrideBytes: Int,
                            metaJson: String): String
  external fun getPreview(h: Long, bitmap: Bitmap): Boolean     // ARGB_8888, engine scales into it
  external fun finalizeStack(h: Long, optionsJson: String, cb: ProgressCallback): String // result JSON
  external fun abortSession(h: Long)
  external fun destroySession(h: Long)
  external fun analyzeFrame(bayer: ByteBuffer, width: Int, height: Int, rowStrideBytes: Int,
                            metaJson: String): String           // {bgFrac, starCount, medianFwhm} for AF/probe
  external fun runSelfTest(tmpDir: String): String
  interface ProgressCallback { fun onProgress(stage: String, percent: Int) }
}
```
- `configJson`: {sessionDir, mode, cfa, whiteLevel, blackLevel[4], width, height, keepCache, livePreview}.
- `metaJson` per frame: {exposureNs, iso, timestampNs, wbGains[4], ccm[9] (sensor→sRGB linear, row-major),
  gyroRms, index}.
- addFrame returns {index, saved, starCount, medianFwhm, dx, dy, rotDeg, inliers, rejected, reason}.
- finalize options: full StackConfig (§9) as JSON. Result: {ok, jpegPending (RGB in shared Bitmap via
  `getFinalImage(h, bitmap)`? — NO: simpler, engine writes `out/final_rgb.raw` W·H·3 u8 + returns dims;
  Kotlin loads into Bitmap, saves JPEG+EXIF via MediaSaver), tiffPath, stats{framesUsed, framesRejected[],
  rejectionPct, fwhmRef, fwhmStack, elapsedMs, peakRssMb}}.
- RAW_SENSOR ImageReader plane: rowStride may exceed width·2 — native must honor rowStrideBytes.
- ByteBuffers are direct; native copies what it needs before returning (buffer is recycled by ImageReader).

## 8. UI/UX spec

- Theme: near-black `#0A0B10` surfaces, star-white text, single accent `#7FB4FF` (cool astro blue);
  Material3 dark only. **Night mode** toggle: palette swaps to reds (`#2A0000` bg, `#FF5544` text/accent)
  + full-screen dim scrim slider (10–100 %). Persisted.
- CaptureScreen: full-bleed preview; top HUD (mode name, thermal, battery, gyro stillness dot, star
  count/FWHM once probing, chosen exp/ISO with reasoning); bottom: mode chips `ASTRO · TRAILS · METEOR ·
  PRO`, quality chips `FAST · BEST · INSANE` (Astro only), big round shutter **ASTRO** button (72 dp,
  accent ring; becomes progress ring frame i/N during capture with per-frame countdown sweep), small
  buttons: Pro panel (sliders icon), Sessions (gallery icon), Night mode (moon icon), Probe/SelfTest via
  overflow. During Stacking: LiveStackView fullscreen with progress + stage label. Done: result preview,
  "Save/Open/Share", stats card (frames used/rejected + why, FWHM, duration).
- ProPanelSheet (modal bottom sheet, grouped, all persisted via SettingsStore/SharedPreferences):
  Exposure (auto/manual: shutter slider log 0.1–max s, ISO stops, frame count 4–99, start delay,
  trailing cap), Focus (AutoStar/Infinity/Manual slider + "run sweep now"), Capture (RAW size
  binned/full, Save DNGs, darks on/off + count, auto-reshoot bumped, cooldown gap), Stacking (on-device
  stack on/off, sigma k, hot-pixel fix, live preview, keep raw cache), Post (gradient removal, stretch
  strength subtle/normal/strong, saturation, chroma NR, star sharpen, foreground composite,
  long-fg-exposures), UI (night mode, red intensity, screen dim, haptics). Every row = label + control +
  info tooltip (one-liner explaining like a photographer).
- SessionsScreen: cards (thumbnail = final jpg or "RAWs only", date, mode, N frames, status);
  SessionDetail: final image viewer (pinch zoom), stats, buttons: Re-stack (opens StackConfig subset →
  re-runs finalize from kept cache/sraw), Export session folder path hint, Delete.
- ProbeScreen §3.1; SelfTestScreen: run button, live log, PASS/FAIL banner.
- Icon: adaptive icon, dark navy bg, 4-point star vector.

## 9. Persistence

- `SettingsStore`: SharedPreferences-backed, one `AppSettings` data class with every toggle above,
  `StackConfig.toJson()` derived from it.
- Sessions root: `context.getExternalMediaDirs()[0]/StarCam/sessions/<yyyyMMdd_HHmmss>_<mode>/`
  (visible over USB at `Android/media/com.wm.starcam/StarCam/...`), subfolders `lights/ darks/ cache/
  out/ meta/`. `meta/session.json` written incrementally (settings, probe result, per-frame records,
  rejections, timings, thermal readings). Final JPG additionally inserted into MediaStore
  `Pictures/StarCam/` with EXIF (exposure=sum, ISO, timestamp, model, software=StarCam).

## 10. Implementation order (each step ends compiling-in-principle; tick PROGRESS.md)

1. Gradle scaffold + manifest + theme + icon + empty MainActivity rendering "StarCam".
2. `common.h`, `sraw`, `tiffout`, `bayer`, `stars` (+ CMakeLists) — pure C++, no JNI yet.
3. `align`, `stack`, `post`, `mask`, `trails`, `meteor`, `selftest`, `engine`.
4. `jni_bridge.cpp` + `NativeEngine.kt` + `StackConfig` + `EngineResult`.
5. `CapabilityProbe` + ProbeScreen (first useful screen).
6. `CameraController` (open/preview) + CaptureScreen viewfinder.
7. `MotionMonitor`, `ThermalMonitor`, `AutoExposure`, `FocusSweep`, `DngWriter`, `CamMeta`.
8. `CaptureEngine` state machine wiring everything; `SessionRepository`; `MediaSaver`.
9. Full CaptureScreen HUD/chips/button states + LiveStackView + ProPanelSheet + SettingsStore.
10. Sessions screens + re-stack path; SelfTestScreen.
11. README.md + MORNING.md (Android Studio install → open → sync → build APK → sideload → test
    checklist incl. self-test + probe + first-night field guide).
12. Review sweep (multi-agent): JNI signature match, Camera2 API misuse, Compose state bugs, C++ UB /
    bounds / stride handling, Gradle/version sanity, manifest permissions, TODO hunt. Fix everything.

## 11. Risk register & fallbacks

- **OEM exposure cap** unknown → engine already scales frame count (§5.1); nothing assumes 8 s.
- **DngCreator quirks** on ColorOS → DNG saving is toggleable and failure-tolerant (log, continue).
- **Focus calibration UNCALIBRATED** → AutoStar sweep is the primary path, not the focus scale.
- **Thermal throttling** → cooldown gaps + defer stack option ("stack later" leaves cache + session
  restackable from SessionDetail).
- **Memory**: binned RAW default; full-res path stacks output in two halves; live preview at ≤ 1080 px.
- **Uncompiled tonight** → conservative APIs only, pinned versions, review sweep, and MORNING.md
  includes "if sync fails" triage section.

## 12. Notes for the morning conversation

User (tabletech2025@gmail.com) sleeps during the build; wants: perfect finish, self-review pass, then
morning manual testing. Phone: OnePlus 15. PC has NO Android toolchain (user chose "code only, no
installs") → deliverable = complete project + MORNING.md. First live task tomorrow: install Android
Studio, open project, fix any sync/compile errors together, build APK, sideload, run SelfTest +
Probe, then night field test.
