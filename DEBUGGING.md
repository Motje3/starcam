# Debugging StarCam

From version 0.2.0 the app logs everything it does, on the phone, all the time. There is nothing
to switch on. The aim is that any failure in the field can be fully explained at home afterwards.

## In the field (no laptop)

- **Something failed?** The error screen has a **Share log** button. It zips the logs and the recent
  sessions' details (never the images) and opens the share sheet. Mail it to yourself, or send it
  over WhatsApp or Drive. Or just bring the phone home; everything stays on it.
- The same is under **Settings → Advanced → Diagnostics → Share debug report**, and that screen
  also shows the live log.
- The files live at `Android/media/com.wm.starcam/StarCam/` and are visible in the phone's Files
  app and over USB:
  - `logs/app_<start time>.log`: the app's log, one file per app start.
  - `logs/logcat_<start time>.txt`: everything the app process wrote to logcat (camera
    framework client, native stacking engine, Android runtime).
  - `logs/crash_*.txt`: Java crashes. `logs/exit_*`: native crashes and ANRs from earlier runs.
  - `sessions/<session>/meta/session.log`: that session's complete log, from the button press on.
  - `sessions/<session>/meta/session.json`: every frame's stats, plus an `issues` list.

## At home over USB

One-time setup: enable Developer options (Settings → About → tap Build number 7×), then
**USB debugging**. Plug in and accept the "Allow USB debugging" prompt on the phone.

```powershell
powershell -ExecutionPolicy Bypass -File tools\pull-logs.ps1
```

This creates `field-logs\<time>\` with the app logs, every session's metadata and log, the camera
service's own state (`camera-service.txt`), the phone's full log buffer including camera HAL
messages (`device-logcat.txt`, which only covers recent hours), and the system's record of why
app processes ended (`exit-info.txt`).

Start with the newest `logs\app_*.log`, or the session's `meta\session.log`, and search for
` E/` (errors) or `FAILED`.

## Reproducing camera problems without a night sky

**Settings → Advanced → Diagnostics → Camera test** shoots N frames exactly the way a session does: the same requests,
probe frame, white-balance lock, and the same work between frames (native ingest + DNG). Lay the
phone face down or cover the lens, then run **5 × 20 s**. The field failure happened at the 2nd
light frame, so a test like this replays it in about two minutes. Results show per frame, and
every request and callback is in the log.

**Settings → Advanced** has one switch per camera behaviour. Flip one, rerun the test, and compare:

| Switch | Default | What it controls |
|---|---|---|
| Fresh camera session after long exposures | on | **the fix** for the October failures (below); off reproduces them |
| Repair the camera when a frame fails | on | recovery ladder vs. 0.1's single retry |
| Vignetting map on every frame | off | the never-shipped 08-28 build asked for the map on every frame |
| Set frame duration explicitly | off | HALs that mishandle long exposures without SENSOR_FRAME_DURATION |
| Keep the viewfinder stream while shooting | on | RAW-only capture session when off |

A test can override any of these for one run from the PC, without touching the phone's settings:
`--ez fresh false`, `--ez preview false`, `--ez fd true`, `--ez lsc true`. A real session can be
started the same way with `--ez session true`.

With the phone on USB, one command backs up the logs, installs the current build as an update,
runs the camera test while recording the whole device log (camera HAL included), and pulls the
results:

```powershell
powershell -ExecutionPolicy Bypass -File tools\device-test.ps1                      # 5 x 20 s, no repair
powershell -ExecutionPolicy Bypass -File tools\device-test.ps1 -NoFresh -SkipInstall  # reproduce the old hang
```

The phone keeps only 256 KB of log per buffer by default, which the camera driver overflows in
seconds. Run `adb logcat -G 16M` once per boot before a test you want the driver's messages from.

Or start a test by hand (debug builds only):

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell am start -n com.wm.starcam/.MainActivity --ez camtest true --ei frames 5 --ei exp_ms 20000 --ei iso 800 --ez work true --ez recovery false
powershell -ExecutionPolicy Bypass -File tools\logcat.ps1   # watch it live, full log saved to field-logs\
```

## Building and installing

```powershell
powershell -ExecutionPolicy Bypass -File tools\setup-toolchain.ps1   # once per PC: JDK 17 + Android SDK
powershell -ExecutionPolicy Bypass -File tools\build.ps1             # build, copy to ..\StarCam.apk, install over USB
```

The toolchain goes in `%LOCALAPPDATA%\Android` (JDK in `jdk17`, SDK in `Sdk`), which is also where
Android Studio looks, so a later Studio install reuses it. Bump `versionCode`/`versionName` in
`app/build.gradle.kts` for every build that goes to the phone: every log names the version that
wrote it.

**Do not lose the signing key.** The app on the phone is signed with the debug key at
`%USERPROFILE%\.android\debug.keystore` (SHA-256 `E1:F4:BB:65…`, backed up in
`D:\Personal\configs\.android\` with the trusted `adbkey`). An APK signed with any other key
cannot update the installed app. The only way around that is uninstalling, and uninstalling
deletes everything under `Android/media/com.wm.starcam` (every session, frame and dark).
`build.ps1` checks the signature and refuses to install a mismatched APK.

**Release builds.** `gradlew bundleRelease` (Play) or `assembleRelease` builds a minified (R8),
resource-shrunk build of about 5 MB instead of the debug APK's 30+, with the native symbols
attached for Play's crash reports. It is signed with the upload key named in `keystore.properties`
(copy `keystore.properties.example`; never commit either); without it the output is unsigned.
Create the upload key once and back it up with its passwords:

```powershell
New-Item -ItemType Directory -Force ..\keys | Out-Null    # outside the repository
& "$env:LOCALAPPDATA\Android\jdk17\bin\keytool.exe" -genkeypair -v -keystore ..\keys\starcam-upload.jks `
    -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

A Play-signed build cannot update the debug-signed app on the phone either: before installing one,
copy `Android/media/com.wm.starcam/StarCam` to a computer, then uninstall the debug build.

## Testing on the emulator

`tools\emulator-test.ps1` runs the checks that need no phone and no night sky on the `starcam_ui`
AVD (API 34, the phone's screen, a back camera that delivers RAW): the engine self-test, a session
through the adb hook (Back asks before stopping, Finish, the result screen), the app killed
mid-session, a synthetic starfield session stacked from Sessions (recorded as shot upright: the
photo must come out portrait), that session opened in the editing studio and saved as a new photo,
its replay video made, the Tonight planner opened from the viewfinder (the emulator placed in
Amsterdam with `geo fix`), the sky view (opened from the planner, closed, opened from its chip),
the camera released on Home and reopened, and a session surviving a swipe out of recents.
`-Clean` empties the emulator's sessions first, `-Only stack,studio` runs a subset (studio needs
stack in the same run). JVM unit tests (sky maths, rotation, studio settings) run with
`gradlew testDebugUnitTest`. The emulator's own camera shows a scene without stars, so
its sessions end on "no reference frame"; that is expected.

`tools\make-test-session.ps1 -Push` writes that synthetic session (8 drifting starfield frames with
hot pixels, a satellite trail and one frame recorded as bumped: it should stack 7 of 8) and pushes
it to a connected device - the phone too, to try re-stacking there.

The sky view's stars (`app/src/main/assets/sky/stars.txt`) come from the Yale Bright Star
Catalogue, 5th edition (`bsc5.dat`, public domain, from CDS V/50): `tools\make-star-table.ps1
-Catalog <path>\bsc5.dat` writes the table (to V 5.0). The constellation figures name stars by
Bayer letter (StarCatalog.FIGURES); `SkySceneTest` fails if one of them is missing from the table.

An Android 16 AVD with 16 KB pages (`starcam_a16`, image `android-36.1;google_apis_ps16k`) checks
the target-SDK behaviour and the native library's page alignment. On this PC its screenshots and
recents crash in the emulator's graphics (system_server restarts): drive it without leaving the
app, take screenshots with `adb emu screenrecord screenshot <dir>` and dump the UI to
`/data/local/tmp`.

## Judging the processing: tools\postlab

`tools\postlab\run.ps1` builds the engine's post-processing (post.cpp, post2.cpp) as a
command-line tool for the x86_64 emulator, runs it there and pulls the pictures back into
`field-logs\postlab\<time>\` as PNG: the whole frame at 1/4 and three 1:1 crops (horizon, a dense
part of the Milky Way, plain sky) per variant. Without arguments it renders a synthetic
nightscape with known ground truth and prints metrics for classic and v2 side by side:

```
classic  sky 0.358 flat 0.1585  mw +0.0975  ground 0.331  horizon +0.1401  rim +0.010/-0.011  starSat red 0.039 blue 0.028 ...
v2       sky 0.137 flat 0.0567  mw +0.1154  ground 0.068  horizon +0.0196  rim +0.005/-0.003  starSat red 0.343 blue 0.144 ...
```

sky = display level of the clean sky; flat = spread of its 64 px block medians (lower is flatter);
mw = Milky Way above sky; ground = landscape level; horizon = sky just above the horizon against
the rest; rim = brightest / darkest pixel within a few rows of the true horizon against the same
window in plain sky (a bright or dark outline along silhouettes; both near 0 is right);
starSat = colour of clearly red / blue stars; noise = pixel-scale luma and chroma noise.
`-LabArgs` passes options: `--nofg`, `--nomw`, `--nograd`, `--noise 2.5` (times a 20-frame stack's
noise), `--seed 11`, `--rot90` (landscape on the left; measured turned back, so its numbers should
match the upright scene's), a variant list (`classic`, `v2`, and `preview` / `preview_old` for
the capture screen's live-stack preview now / until 0.4) and post parameters (`sky=0.14
gap=0.2 ground=0.05 gs=1 nr=0.8 starc=0.6 style=1 sharpen=1 order=3 down=0`). `-NoBuild` reruns
the last build. `-Tiff <session>\out\master_linear16.tiff` develops a real session's master
instead: the way to judge a change on real sky data.

## Reading a log line

```
2026-10-18 01:26:41.512 E/cam [DefaultDispatcher-worker-3] #7 FAILED +3012ms: reason=ERROR(0) frame=212 ...
```

Date and time, then level (`D` debug, `I` info, `W` warning, `E` error), then tag, then thread,
then the message. Tags:

| Tag | What |
|---|---|
| `cam` | every Camera2 request (`#n`, with its settings) and callback, with ms since submit; device/session state |
| `session` | the capture state machine: settings, decisions, each frame's timings and stats, repairs, the summary |
| `camtest` | the Diagnostics camera test |
| `focus` | star autofocus sweep points |
| `life` | activity lifecycle, screen on/off, power/doze, memory pressure |
| `diag`, `exit`, `crash` | startup info, why the previous run ended, crashes |
| `vm`, `service`, `thermal`, `ui`, `bundle` | camera open/lost, foreground service, heat, navigation, debug bundle |

Each frame also gets a condition snapshot: battery, temperature, thermal status, free RAM, heap,
native memory, free storage, screen on/off, and whether the app was in the foreground.

## What else a session leaves behind (0.3.1)

- `meta/running` exists while a session runs. One still there at the next app start means the app
  was killed mid-session: the app adds `interrupted: ...` to the session's `issues` and says so on
  the viewfinder.
- Dark frames are checked before use. A lit one logs `dark 2/4 is lit: background 3.10% of white,
  limit 0.70% (sky 2.00%) ... not used` and adds an issue. Fewer than two usable darks: `only 1
  usable dark frame(s) - stacking without darks`.
- Library darks (`StarCam/darks/*.sraw`) have a `.json` beside them with the battery temperature
  they were shot at; `dark library: ... shooting fresh darks` says why one was not reused. A
  session that used one has `cache/master_dark.library`, and the engine logs `finalize: library
  dark, pedestal ...`.
- Stop requests are logged where they are made (`cancel requested (state: ...)`,
  `finish-early requested`), so the time to the session's end shows how long the frame in
  progress took.
- `not reusing focus: the phone's temperature moved 6.2°C since it was found`, and the issue
  `the final JPEG could not be written (storage full?)`, are what they say.

## More of the same (0.3.2)

- `cam`: `RAW reader retired with 1 image(s) still being read` means the camera went away (or the
  app was closing) while a frame was being ingested; `retired RAW reader closed` follows when the
  frame is done. `camera 0 was taken away while it was opening` is a disconnect that raced the
  open.
- `session`: `storage nearly full (… MB free) — finishing early`, `light N could not be saved
  (storage full?)` (also an issue in session.json), `no gyro readings during the exposure` (that
  frame could not be judged for bumps), and for TRAILS/METEOR `duration 20 min at 31500ms a
  frame: 38 frames fit, not 40`.
- `focus`: `the lens was still moving — measuring again`.
- Native (`StarCamEngine` in logcat): `finalize: master dark black 64.0 64.0 64.0 64.0`, and
  `stack_frames: … region copy failed (out of memory?)` when a stack fails for memory.
- A `session.json.corrupt-<time>` beside session.json is a manifest that did not parse, kept as it
  was found. In the app log, `(N log lines dropped …)` and `(log files paused …)` mark gaps.

## The October 2026 field failure (root cause found)

**Symptom.** Every session on 2026-10-18 (four attempts in seven minutes, 22.5 s at ISO 1600)
saved exactly one good light frame, then died with `capture failed: capture failed: reason 0`.
The phone was running the 2026-08-18 build (0.1.0); later builds had never been installed.

**Cause: a camera driver bug, reproduced on the bench on 2026-10-21.** On this phone (OnePlus 15,
Qualcomm CamX HAL; camera 0 is a logical multi-camera), the first request that follows a long
exposure in the same capture session never completes. The exposure does not even start. The
driver's own log, at the moment it gives up:

```
CamX: Smart error code : 1501440, Verbose error string : MCTFE node has 3 unsignaled fences
      for requestID 34 , nodeReqStatus : SUBMIT ... waitedTime: 60004 ms
CamX: CHI triggered PCR timeout recovery: ErrorCode:1501440, ErrorString: MCTFE, FenceUnsignaled
CamX: Feature RealTimeMCX is waiting for 2 external output(s) from the session
      (FeatureName RealTimeMCX, GraphName MCXSuperFG, UsecaseName MultiCamera)
```

The driver's real-time temporal filter stage (MCTF) waits for outputs that never come. After that,
every request on the session fails within milliseconds. 0.1.0 timed out after 37.5 s, retried
straight into the wedged session, and the retry failed with `reason 0`, which is the message on the
error card. The first long frame always worked because only short frames (the 1 s probe, the 2 s
focus sweep) came before it. Even a 1 s frame sent after a 22.5 s one hangs.

**Bench results** (3 × 22.5 s, ISO 1600, preview stream in session, ingest + DNG between frames):

| Configuration | Result |
|---|---|
| one session for the whole burst (0.1.0 behaviour) | **FAIL**: frame 2 hung 60 s, retry failed in 7 ms |
| fresh capture session after each long exposure | **PASS** 3/3, each frame back in exactly 22.5 s |
| repair ladder only | PASS 3/3, but only after two 60 s hangs |

**Fix (0.2.1).** The camera controller rebuilds the capture session after every exposure of
2.5 s or more, before anything else is sent to it: the next frame, darks, the next session's probe,
or the viewfinder. It costs about 0.1 to 0.2 s per frame. The repair ladder stays as the safety
net (stage 1, abort and drain, is enough to un-wedge the driver). If the camera still cannot be
recovered, the frames already shot are stacked anyway.

**Also changed in 0.2.x, not related to the cause.** The lens shading map is requested on the
probe frame only (the never-installed 08-28 build asked on every frame). The screen shield keeps
the black screen on rather than letting it sleep.
