# StarCam — build & test status ☕

> **2026-10-21:** the PC was rebuilt on 2026-08-30 and the Android toolchain went with it. Builds
> now run from the command line: `tools\setup-toolchain.ps1` once, then `tools\build.ps1`. See
> DEBUGGING.md, which also covers the logs, the camera test and pulling field logs over USB.

> **UPDATE (2026-08-18): this is all done.** The toolchain is installed, the app builds, and it is
> installed and verified on the phone. The install instructions further down are kept for reference
> (e.g. rebuilding on another machine). What you actually need now is the section directly below.

## Where it stands

- **Builds clean.** `BUILD SUCCESSFUL` — Kotlin + the native C++ engine, arm64.
  Rebuild any time: `gradlew.bat assembleDebug` (JAVA_HOME is the Microsoft JDK 17 in Program Files).
  APK: `app\build\outputs\apk\debug\app-debug.apk`, also copied to `D:\Code\StarCam.apk`.
- **Installed and running** on the phone (OnePlus CPH2769, Android 16). UI, permission flow,
  capability probe and self-test all verified on-device.
- **Engine self-test: PASS** — the whole align/stack pipeline validated on the phone in **2.2 s**:

  | check | result | limit |
  |---|---|---|
  | alignment RMS vs ground truth | **0.0062 px** | 0.35 px |
  | hot pixels (with dark-frame map) | **200/200 removed** | ≥95 % |
  | satellite streak suppression | **0.0083** | 0.0313 |
  | stacked star FWHM | **2.330 px** | 2.530 px |

## What the capability probe found (this is the good news)

Your main camera (id 0) is **hardware LEVEL_3** and exposes everything this app needs:

- **Exposure up to 30 seconds** to third-party apps — the best case. No need for the
  "shoot more, shorter frames" fallback.
- **ISO 100–6400, fully analog** (no digital-gain fakery).
- **RAW 4096×3072 (12.6 MP), BGGR**, white level 1023, black 64.
- Manual focus (min distance 10 dpt, APPROXIMATE), f/1.88, 5.59 mm.
- **OEM NIGHT extension is available** — OnePlus exposes its own computational night pipeline to
  third-party apps. Not used yet; it's a future "OnePlus Night Engine" mode alongside our stacker.
- Camera 3 is the **ultrawide** (1.68 mm f/2.2, fixed focus, also RAW + 30 s). Genuinely interesting
  for Milky Way work — a wider lens tolerates much longer sub-exposures. Worth adding a lens picker.

Because 30 s is available, the per-frame cap is now computed from the lens itself using the **NPF
rule** rather than my old hardcoded 8 s guess. It's the "Auto trail limit" toggle in the Pro panel,
which shows the computed value; turn it off to set the cap by hand.

## Calibration (2026-08-28)

- **Lens vignetting** is now divided out using the map the camera publishes, before stacking. It
  does nothing unless this phone reports a usable map — check the capture screen's calibration
  line, which says `vignetting: map 1.31x` or `vignetting: no map`. If it says "no map", the
  engine self-test still verifies the correction itself (check 5), so the code is known good and
  the gap is the phone's metadata.
- **Darks are reused** for up to 14 days when the lens, exposure and ISO match exactly, so
  repeat sessions skip the lens-covered minute. The capture screen always says which set is in
  use. Both are Pro-panel toggles.

## ⚠️ One real finding: leave dark frames ON

The self-test measured this directly. A hot pixel is *static on the sensor* while the sky drifts
only ~1 px per frame, so sigma clipping alone removes about **96.8 %** of it — good, but the
remainder is still visible against a stacked noise floor. With the **dark-frame hot-pixel map**,
removal is **complete (200/200)**. So: when the app asks you to cover the lens, do it. That prompt
is the difference between clean pixels and coloured speckles. (Darks default to ON for Best/Insane.)

---

# Original setup guide (for reference / other machines) ☕

Everything is coded. What's left is compiling it (needs Android Studio, ~30 min mostly waiting)
and sideloading the APK. **Important honesty note:** this code was written overnight without a
compiler on this PC, so the first sync/build may surface a few small errors. That's expected —
just open a Claude session in this folder, paste the error, and we fix them in minutes.

## Step 1 — Install Android Studio (~15 min)
1. Download from https://developer.android.com/studio (big green button, Windows installer).
2. Run it; accept defaults ("Standard" setup). It installs its own JDK — you need nothing else.
3. First launch: let it download the Android SDK when it asks.

## Step 2 — Open the project
1. Android Studio → **Open** → select the folder `D:\Code\StarCam`.
2. It will "sync" — during this it auto-downloads Gradle 8.9, NDK 27, CMake (accept any license
   prompts). First sync can take 10–15 min. Coffee time.
3. **If sync fails**: don't debug it yourself — copy the red error text, open Claude Code in the
   StarCam folder, paste it. Most fixes are one line.

## Step 3 — Build the APK
1. Menu **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
2. When done, click "locate" in the popup — or find it at
   `app\build\outputs\apk\debug\app-debug.apk`.

## Step 4 — Install on the phone
1. On the OnePlus: Settings → About device → Version → tap **Build number** 7× (enables Developer
   options). Then Settings → Additional settings → Developer options → enable **USB debugging**
   (optional but useful).
2. Easiest install: connect USB, copy `app-debug.apk` to the phone, tap it in the Files app,
   allow "install unknown apps" when prompted.
   (Alternative: in Android Studio with USB debugging on, just press the green **Run ▶** button.)

## Step 5 — First-run tests (daylight, indoors, 10 min)
Do these in order — they verify everything without needing stars:
1. **Grant camera permission** when asked.
2. **⋮ menu → Engine self-test** → Run. This synthesizes a starfield and pushes it through the
   real align/stack pipeline. You want a green **PASS** (~1 min). If FAIL, screenshot it for me.
3. **⋮ menu → Capability probe** → wait → **Copy** → paste the report into a Claude session (or
   save it). This is THE document that tells us what your OnePlus 15 really exposes — especially
   the line `Exposure: ... <-- KEY VALUE`. The app already adapts automatically, but I want to
   tune the defaults to the real numbers.
4. **PRO mode smoke test**: switch mode chip to PRO, open Pro panel (sliders icon), set Shutter
   0.5s / ISO 200 / Frames 4, point at anything, press SHOOT. You should get 4 frames + a stacked
   (ugly, daylight) image in Sessions. That proves capture + stacking end-to-end.

## Step 6 — First night out 🌌
- Tripod. Phone in landscape or portrait, doesn't matter. Kill the case if it wobbles.
- Mode **ASTRO**, quality **BEST**. Press the big button and *don't touch the phone* — it waits
  for stillness, reads the sky, autofocuses on stars (~40 s), then shoots 20×. Watch the live
  stack build.
- When it asks for **dark frames**: cover the lens with something truly opaque, tap "Lens
  covered". Worth it — it maps your sensor's hot pixels.
- Result lands in the gallery under Pictures/StarCam, full session on the phone under
  `Android/media/com.wm.starcam/StarCam/sessions/`.
- **Night vision**: moon icon toggles the red UI. **Framing boost**: eye icon brightens the
  preview to compose in the dark.
- If the sky is milky in the result: try Stretch "Subtle" in the Pro panel, or re-stack from
  Sessions with different settings (turn on "Keep raw cache" first).
- For your PC pipeline: copy the session folder over USB — `lights/*.dng` drop straight into
  Siril/Sequator.

## What happened overnight (short version)

The whole app is written: ~5,500 lines of Kotlin/Compose and ~5,000 lines of C++ engine, no
third-party runtime libraries. After it was written, I ran a review sweep — 10 specialist reviewers
plus a skeptic agent attacking every single finding to throw out false alarms. 22 findings survived;
all are fixed. Highlights of what that caught (full list in `PROGRESS.md`):

- Two hard **compile errors** (a call to a function that never existed in `selftest.cpp`, and a
  self-recursive callback override in `Restacker.kt`).
- **The result overlay never went away** — after every capture the Done card would have been stuck
  on screen with a dead shutter button. That alone would have wrecked this morning's test.
- A **use-after-free** between the live preview and session teardown, and the ANR risk introduced by
  the first attempt at fixing it.
- Three **out-of-memory** paths (stacking tile buffers scaling with frames × CPU cores, full-res
  bitmap decoding, foreground compositing at 50 MP) and a **disk-filling** path where a 2-hour
  meteor session would try to write tens of gigabytes.
- `-ffast-math` silently deleting the engine's NaN guards.

Everything is documented in the code where it matters. It still has never been compiled, so treat
the first build as a bug hunt, not a formality.

## Known limits (v0.1)
- Foreground composite + Meteor mode are flagged **beta** — functional but the least battle-tested.
- A session now survives the screen going off and the app being backgrounded (foreground service
  + wake lock, 2026-08-28), and the display is allowed to sleep behind the screen shield. Losing
  the camera to another app is recoverable in place via **Reopen camera**.
- OnePlus may cap third-party exposure shorter than its own camera app; if the probe shows e.g.
  1s max, StarCam automatically shoots more, shorter frames instead (that's by design).
- DNG saving on ColorOS occasionally produces quirky files; if DNGs look broken, the in-app stack
  is unaffected (it uses its own raw cache).

Have fun. When you have the probe report + first results, come back and we tune it. 🔭
