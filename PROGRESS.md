# StarCam build progress (PLAN.md §10)

## UI redesign, 0.3.0 (2026-10-21)

The whole interface was rebuilt on the design handoff in `design/handoff/` (24 boards, tokens,
Geist type, one icon set, red night vision), made more compact than the mockups so the controls
leave the viewfinder room. Frontend only: the camera, capture engine, stacking and settings model
are untouched; every screen reads the same state and calls the same functions as before.

- **Theme** (`ui/theme`): design tokens for dark and night vision, Geist + Geist Mono bundled
  (SIL OFL, licence in Settings › Advanced), 40 stroke icons ported path for path from the
  handoff. Night vision recolours every surface red; photos and the live stack are shown through a
  grayscale × red filter; the camera's own preview (a SurfaceView, untouched so the camera
  pipeline stays exactly as tested) is dimmed and tinted instead.
- **Components** (`ui/components`): buttons, chips, segmented controls, switch, slider, rings,
  spinner, the seven-step session strip, frame tape, shutter, status pill, notices, setting rows.
- **Capture screen**: status + lens on one 54 dp row, the viewfinder (rounded, thirds grid, plan
  line inside it), mode tabs, one preset/duration row and one bottom bar - about 240 dp of
  controls instead of the mockup's ~310. The plan line shows what the shutter will do (exposure
  from the lens's NPF limit, frames, darks); it is computed with the engine's own rule, before
  shooting, without inventing the ISO the test frame will pick. Wider-lens tip (tap to switch),
  spirit level as a horizon line, night dimmer on the viewfinder in night vision.
- **Pro sheet**: shutter / ISO / frames tiles with a stepper, focus, start delay, four switches.
- **Session flow**: one full screen per step (hold still with a live gyro trace, reading the sky,
  star focus with the sweep position, capturing with live stack, frame ring, time left and frame
  tape, cover the lens, dark frames, stacking stages, done with Share / Open / Re-process, error
  with what was saved and what to do next). The idle layout stays composed underneath, so the
  viewfinder surface that is part of the camera session is never destroyed mid-capture.
  Stopping a capture now asks first.
- **Settings** replaces the Pro panel: eight tabs plus search over every setting, each with its
  (i) explanation. Troubleshooting switches, Diagnostics, probe and self-test live under Advanced.
- **Sessions** is a grid with status per session (finished, RAWs only, interrupted, meteors);
  **session detail** has pinch-zoom, stats, the same re-stack and delete, and Share / Open.
- **First launch**: three intro cards, then the camera permission asked in context.
- Smaller fixes on the way: photos are decoded downsampled in the session detail (a 50 MP stack
  was decoded at full size); the camera-problem card no longer blocks Sessions and Settings.

## Field-debugging pass, 0.2.0 → 0.2.1 (2026-10-21)

Field sessions kept dying at the 2nd light frame with `capture failed: capture failed: reason 0`
(Camera2 `onCaptureFailed`, `REASON_ERROR`). The single immediate retry failed identically, and
nothing was stacked. Nothing on the phone recorded why, and there is no laptop in the field. So
this pass makes every failure explainable from the phone alone, stops a camera failure from costing
the whole night, and, with the phone on USB, found and fixed the actual cause.

**Correction to the history above.** The phone was still running the **2026-08-18** build (0.1.0,
`lastUpdateTime 2026-08-18 12:10`). The 08-27 and 08-28 passes below were written and built but
never installed, so none of their changes were ever field-tested.

**Root cause: a camera driver bug, reproduced on the bench (details in DEBUGGING.md).** On this
phone's Qualcomm CamX HAL, the first request after a long exposure in the same capture session
never completes. CamX logs `MCTFE node has 3 unsignaled fences ... Feature RealTimeMCX is
waiting for 2 external output(s)` and fails it on a PCR timeout, after which every request on that
session fails at once. With 3 × 22.5 s at ISO 1600:

- one session for the burst: **FAIL**, frame 2 hung 60 s, retry failed in 7 ms (the field symptom);
- fresh session after each long exposure: **PASS** 3/3, no hangs;
- repair ladder alone: PASS 3/3, but after two 60 s hangs.

**Fix (0.2.1).** `CameraController` marks a session tainted once it has taken an exposure ≥ 2.5 s
and rebuilds it before the next request of any kind (frame, dark, probe, viewfinder), for about
0.1 to 0.2 s per frame. Pro → Debug "Fresh camera session after long exposures" (default on); off
reproduces the bug.

**Logging (see DEBUGGING.md).** `AppLog` now writes every line to
`Android/media/com.wm.starcam/StarCam/logs/app_<start>.log` on a background thread, keeps a
4000-line ring for the new Diagnostics screen, and copies each session's lines (from the button
press, before the folder existed) into that session's `meta/session.log`. `Diagnostics` adds a
crash handler that flushes the log before the process dies, reads `ApplicationExitInfo` at start
(native crashes with the tombstone saved, ANRs, low-memory kills, force stops), mirrors the app's
own logcat into `logs/logcat_*.txt` (camera framework client, native engine, ART), logs lifecycle,
screen on/off, doze, power saver and memory pressure, and appends a condition snapshot (battery,
temperature, thermal headroom, RAM, heap, native heap, storage, screen, process importance) to
every frame. `CameraController` logs every request with its settings and every callback with ms
since submit: exposure started, result values against what was asked for, failure reason, frame
number, imageCaptured, lost buffers, image arrival, stale images, and session idle/active/closed.
`DebugBundle` zips logs + recent sessions' metadata + probe + settings and opens the share sheet,
from the error card's **Share log** or the Diagnostics screen.

**Capture robustness.**
- The lens shading map is requested on the probe frame only (the never-installed 08-28 build
  asked on every frame). It is a lens property, so one per session is enough. Not the cause.
- `captureFrame` replaces the single blind retry with a repair ladder: abort + drain → rebuild
  the session (RAW-only) → close and reopen the device. Every step is logged. Cancel and "Finish
  & stack now" are honoured between steps. On the bench, stage 1 alone un-wedged the driver.
- If the camera cannot be recovered, the frames already shot are stacked anyway (≥ 2 frames) and
  the Done card carries a warning. A session that needed repairs but recovered also says so.
- Session builds are serialized (`configMutex`). `surfaceCreated` + `surfaceChanged` used to
  launch two racing builds, and a viewfinder rebuild could land mid-burst. An `exclusiveOwner`
  flag now blocks viewfinder rebuilds and the preview's repeating request while a session or
  test owns the camera.
- `SurfaceView` hands out the same `Surface` object across destroy/recreate, so identity could not
  detect a stale preview output. The surface is now forgotten when destroyed and the session is
  rebuilt when it returns.
- Stale images are drained before every capture. Capture timeout raised to 2×exposure + 15 s
  (sensors can finish a free-running long frame before latching the next request). Timeouts and
  failures are typed (`CaptureFailedException`, `CaptureTimeoutException`) so the UI can say what
  happened in words.
- `startForeground` refusal is caught and logged instead of crashing.
- The screen shield keeps the black screen on rather than letting it sleep (fewer moving parts
  mid-burst; nearly free on OLED). Not the cause.
- From the independent review: a `closed` flag plus 10 s bounds on camera open and session
  configure (no hang on a dead camera thread); the device is published in `onOpened` so an
  immediate disconnect is not mistaken for a stale one; a failed reopen is reported; "Reopen
  camera" no longer tears the camera down under a running session; a session-generation counter
  stops a finished session's wind-down from killing the next one's foreground service; app
  teardown waits for native code to leave the image buffers before closing the camera.

**Tools.** Diagnostics screen (fixed live viewfinder, live log with filters, share bundle,
**camera test**: N frames exactly as a session shoots them, with optional ingest + DNG work,
per-frame results). Pro panel **Debug** tab. adb hooks (debug builds only) to start a camera test
with per-run switch overrides, or a real session. `tools/setup-toolchain.ps1` (the PC rebuild on
2026-08-30 had wiped the Android toolchain; sdkmanager is pinned to cmdline-tools 19.0 because 20+
delegates to a new "Android CLI" that could not reach its repository), `tools/build.ps1` (refuses
to install an APK not signed with the phone's original debug key, since uninstalling would delete
every session), `tools/pull-logs.ps1`, `tools/logcat.ps1`, `tools/device-test.ps1`.

## Calibration pass (2026-08-28)

### Lens shading correction

The phone publishes a measurement of its own vignetting and the app was throwing it away —
`STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES` was printed in the capability probe and used
nowhere. RAW_SENSOR is deliberately never shading-corrected by the device, which is exactly why
the map exists.

It matters for more than flat corners. `post.cpp` fits a 2nd-order polynomial to a 32x32 grid of
star-masked medians and subtracts it, and that polynomial cannot tell f/1.88 falloff from real
large-scale structure — so on a Milky Way frame it was removing some of the Milky Way along with
the vignetting. Correcting multiplicatively upstream leaves the gradient fit with only the real
(additive) skyglow to model.

- Requested per frame via `STATISTICS_LENS_SHADING_MAP_MODE`, but only when the HAL advertises the
  mode: an unsupported key on a request does not no-op, it can fail the capture.
- `CamMeta.shadingMap` reduces the map to RGB (the two greens averaged) and normalizes it to the
  brightest node, so it only ever redistributes brightness — it cannot change exposure or WB.
  Doing it in RGB rather than raster order sidesteps the CFA-layout question entirely: the map's
  channel indices are semantic (RggbChannelVector order), the raster positions are not.
- Applied in `stack.cpp` after demosaic and before the warp — vignetting is fixed to the lens, so
  it lives in frame coordinates, not sky coordinates. The trails path gets the same treatment.
- Stored as `meta/shading.json` inside the session rather than passed through the session config,
  so a later re-stack of the same folder picks up the identical correction for free.

Every failure mode is a silent no-op, checked on both sides: `SENSOR_INFO_LENS_SHADING_APPLIED`
true (device already corrects RAW — applying on top would brighten corners twice), no map, a map
too small to interpolate, a non-finite or out-of-range gain, or a map that is effectively flat.
Pro-panel toggle, default on. The honest cost is stated in its help text: brightening the corners
brightens their noise too.

**Self-test check 5.** `synth_frame` already renders a 15% radial falloff, so the matching
correction map is pushed through the real stacking path and the corner-to-centre ratio must land
in [0.97, 1.03] (about 0.89 uncorrected). Costs a second stack pass, which is worth it for a
stage that silently changes every pixel of every result.

### Dark library

Darks are only valid for the exact camera, geometry, exposure and ISO they were shot at, so all
four are in the filename and only an exact match is reused. A match younger than 14 days is copied
into the new session as its master dark and the "cover the lens" step is skipped entirely; the
second and later sessions of a night usually save that minute. Reuse expires because hot pixels
and dark current drift with the sensor's age and temperature, and an old dark is worse than none.
Six sets are kept (each is a full-resolution frame, ~25 MB).

This also fixed a latent bug: `finalize` wrote `cache/master_dark.sraw` with a comment saying a
later re-stack could reuse it, but nothing ever read it back. Re-stacks were silently losing dark
calibration. The same load path serves both.

The capture screen now carries a calibration line — `darks: reused (3d old) - vignetting: map
1.31x` — because silently reusing old calibration is exactly the kind of thing that should never
be invisible. `stack_report.json` gained `darkSource`, `darkFrames`, `lensShading` and
`lensShadingMaxGain`.

### Wider-lens hint

A suggestion, never a switch. When another RAW lens tolerates a meaningfully longer sub-exposure
(1.5x or better on the NPF rule), a line under the lens chips says so and why: *"0.3x holds stars
for 26s vs 8s - more light per frame for wide Milky Way shots"*. Nothing is auto-selected and the
main sensor stays the default; the exposure planner already adapts to whichever lens is chosen.

## Session survivability pass (2026-08-28)

Three things that only show up in the field, all fixed together because they are one story: a
session has to survive the app not being on screen.

**Camera loss was unrecoverable.** `CameraDevice.StateCallback` fires for the whole life of the
device, but `CameraController.open` only used it to resolve the open continuation. Once open, a
revoked camera (another app grabbed it, the system took it, a hardware error) closed the device
while this class kept pointing at the dead handle: every later call threw from inside Camera2
with a meaningless message, `_ready` stayed true, and nothing ever reopened it — the app had to
be killed. Now `lost()` handles the post-open case: drop every handle, log the reason, and call
the new `onDeviceLost` hook. `captureRaw` throws a typed `CameraLostException` instead of a
generic `IllegalStateException`, the ViewModel exposes `cameraLost`, and the camera-problem card
has a **Reopen camera** button (`reopenCamera()` — forced, because the dead controller still
reports the same lens id and the plain lens-switch path would see "already on this lens"). The
engine's own Error card now wins over the camera card once a failed session has produced one, so
the session folder stays findable.

**Backgrounding killed the session.** Android revokes camera access to background apps, so
locking the phone or checking a star map lost a 20-minute burst. Added `CaptureService`
(`foregroundServiceType="camera"`) plus a partial wake lock — the FGS keeps camera access, the
wake lock stops the CPU suspending between frames with the screen off. It owns no camera state;
the engine still runs in the ViewModel. `SessionBeacon` carries a status line to the notification
(sampled at 1 Hz) and two actions back: **Finish now** (`finishEarly`, keeps the frames) and
**Cancel** — "stop" from a lock screen is ambiguous and the two answers are very different.
`POST_NOTIFICATIONS` is requested but never required: the service runs either way, only the
notification is affected.

**The screen never slept.** `FLAG_KEEP_SCREEN_ON` was set unconditionally, so a 60-minute trails
run kept the panel lit throughout. With the service holding the session, the display is free to
sleep behind the screen shield: the flag is now cleared while shielded and restored when it comes
off.

Also fixed on the way, and required for any of the above to be safe: `attachSurface` rebuilt the
capture session (and with it the RAW ImageReader) whenever a preview surface reappeared. A screen
waking mid-session would have pulled the reader out from under an in-flight exposure. It now
defers while the engine is running, and `startSession` re-attaches once the session ends.

## Capture feedback pass (2026-08-27)

First night field test: the app worked but *read* as frozen. A capture phase holds one
`CaptureState` for a whole 30 s exposure plus a several-second native ingest, so nothing on
screen moved for ~35 s a frame and there was no way to tell capturing from hung.

Added a second, finer channel next to `CaptureState`: `CaptureEngine.busy: StateFlow<Busy?>`
(label + start time + expected duration) plus `sessionStartedAtMs`. Every step that can take
more than about a second now publishes one — sensor prep, probe exposure, sky measure, each
focus sweep point, each light exposure, each native frame ingest, each DNG write, cooldowns,
dark exposures and ingests, every native stacking stage, and the JPEG save that used to run
*after* the bar hit 100 %.

UI (`CaptureScreen`):
- `BusyRow` under every overlay: countdown ring for steps of known cost, indeterminate spinner
  counting up for the rest, and an amber "slower than usual, still running" once a step
  overruns — honest rather than a bar that lies.
- `SessionClock` in the status strip for the whole session; the screen shield keeps a dim copy.
- "~N left" now ticks in real time (`CapturingLights.frameStartedAtMs`) instead of being
  recomputed once per frame and then sitting still for the whole exposure.
- Gyro magnitude vs threshold on the hold-still card, and that wait counts down to its 120 s
  timeout, so "waiting for stillness" no longer looks like a hang.
- Done card reports the session's total wall-clock time.

Clocks tick at 5 Hz inside leaf composables only, so an overlay is not rebuilt five times a
second for a whole session.

## On-device verification (2026-08-18)

Built, installed and exercised on the real phone (OnePlus CPH2769, Android 16).

- First build: **one** Kotlin error in 4,358 lines (`continue` inside an inline lambda in
  CapabilityProbe — experimental in Kotlin 2.0). The entire 5,400-line C++ engine compiled clean
  on the first attempt. Second build succeeded.
- Three defects found only by *running* it, all fixed:
  1. **Self-test could not write anything** — it was handed `cacheDir/selftest`, which nothing
     created. Fixed on both sides (Kotlin `mkdirs`, plus the engine now creates its own scratch dir).
  2. **Hot-pixel check was measuring the wrong thing.** It demanded sigma-clipping alone reduce a
     static single-pixel defect below 5σ of the *stacked* noise floor. That is not achievable: the
     sky drifts ~1 px/frame so the defect stays put, and the demosaic smears it over 5×5. Measured
     honestly it removes 96.8 % of the amplitude. The check now tests the path the app actually
     uses — the dark-frame hot-pixel map — which removes 200/200, and reports the clip-only number
     as information rather than a gate. This also gave `fix_hot_pixels_region` its first test.
  3. Trailing limit was a hardcoded 8 s guess; the hardware allows 30 s. Now computed per lens with
     the NPF rule (new "Auto trail limit" toggle, on by default, value shown in the Pro panel).
- Self-test result: **PASS** in 2.2 s — align RMS 0.0062 px (limit 0.35), hot pixels 200/200,
  streak 0.0083 (limit 0.0313), FWHM 2.330 px (limit 2.530).


All twelve steps are complete. The project is fully written and self-reviewed; it has **not** been
compiled (no Android SDK on this PC — the user chose "code only, no installs"). See MORNING.md.

- [x] 1. Gradle scaffold + manifest + theme + icon + MainActivity
- [x] 2. C++ core: common.h, json_mini.h, sraw, tiffout, bayer, stars, CMakeLists
- [x] 3. C++ pipeline: align, stack, post, mask, trails, meteor, selftest, engine
- [x] 4. JNI bridge + NativeEngine.kt + StackConfig + EngineResult (+ Restacker)
- [x] 5. CapabilityProbe + ProbeScreen
- [x] 6. CameraController + viewfinder CaptureScreen
- [x] 7. MotionMonitor, ThermalMonitor, AutoExposure, FocusSweep, DngWriter, CamMeta
- [x] 8. CaptureEngine state machine + SessionRepository + MediaSaver
- [x] 9. Full CaptureScreen UI + LiveStackView + ProPanelSheet + SettingsStore + theme
- [x] 10. SessionsScreen + SessionDetail + re-stack + SelfTestScreen
- [x] 11. README.md + MORNING.md
- [x] 12. Multi-agent review sweep (51 agents, adversarially verified) + fixes applied

## Review sweep results

10 reviewers by dimension (C++ signatures, engine call sites, JNI contract, native memory, two
math/correctness areas, two Kotlin areas, Compose, build config), each finding independently
attacked by a skeptic agent. 22 findings survived verification; duplicates collapse to 16 distinct
defects. All fixed:

**Compile errors (would have failed the build outright)**
1. `selftest.cpp:478` called `fw_size_helper(n)`, which never existed — leftover placeholder from
   the agent that was interrupted by a usage limit. → `std::vector<float> fw((size_t)n);`
2. `Restacker.kt` — inside `object : ProgressCallback`, `override fun onProgress(...) = onProgress(...)`
   resolved to the object's own member, so the expression body was self-recursive and its return
   type could not be inferred. → capture the lambda as `progressFn` and use a block body.
3. `CaptureScreen.kt` — `remember` inside an elvis branch (conditional composable call) and
   `MutableStateFlow(null)` inferring `MutableStateFlow<Nothing?>`. → hoisted, explicitly typed.

**Crashes**
4. Use-after-free: `fillPreview` (UI thread pool) and `cancel()` (main thread) handed `engineHandle`
   to JNI while `runSession`'s finally could `destroySession` it. → all handle uses under `handleLock`.
5. `stack.cpp` per-thread tile buffers scaled with frames × threads: ~800 MB at 40 frames, multiple
   GB for a long METEOR session. → adaptive tile size against a per-thread/total memory budget.
6. Long-mode disk explosion: up to 900 frames × full-res `.sraw` (+DNG) could write tens of GB and
   fill the phone. → `capFramesToDisk` limits the plan to 60 % of free space and logs the cap.
7. Foreground composite at full-res RAW held sky + ground + blend + mask (~2 GB at 50 MP).
   → suppressed when `fullResRaw` is on.
8. `CamMeta.pickMainCamera` let `CameraAccessException` from `cameraIdList` escape a coroutine
   (process kill instead of the "no usable camera" message). → wrapped.
9. `MediaSaver.rgbToBitmap` materialised the image three times (file bytes + IntArray + Bitmap).
   → streams row blocks via `setPixels`.
10. `DoneCard` decoded the final JPEG at full stack resolution (~200 MB at 50 MP).
    → `decodeDownsampled` with `inSampleSize`.

**Bugs**
11. **The Done/Error overlay never cleared.** `cancel()` only set flags; after a session finished,
    nothing reset `_state`, so the result card was permanent and the shutter stayed dead — every
    capture would have "hung" the app. → `dismissResult()`, wired to both terminal cards.
12. Truncated `.sraw` (disk full) fed to a re-stack spliced the *previous* frame's pixels into the
    tail of the buffer. → short reads detected and the frame skipped.
13. Edge-to-edge on Android 15 (targetSdk 35) drew the HUD under the status bar and the shutter
    under the gesture bar. → `windowInsetsPadding(WindowInsets.systemBars)`.
14. `-ffast-math` implied `-ffinite-math-only`, folding away the engine's deliberate NaN guards in
    post/tiffout/meteor/mask and `std::isfinite` in json_mini. → `-fno-math-errno` instead.
15. Star autofocus ignored Cancel for ~30 s. → `isCancelled` polled between exposures.
16. The preview surface was never fixed to a camera-supported size (stretched viewfinder, and
    strict HALs can reject the stream). → `CamInfo.previewSize()` + `setFixedSize` + `aspectRatio`.

Refuted by the skeptics and deliberately NOT changed (recorded so they aren't "re-found" later):
reference-frame re-selection in finalize, meteor blend ordering vs TIFF write, live-preview warp of
unaligned frames, `LENS_FOCUS_DISTANCE` above `minFocusDistance` (platform clamps it), and adding an
explicit kotlinx-coroutines dependency (lifecycle-runtime-ktx exports it as `api`).
