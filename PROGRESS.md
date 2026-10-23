# StarCam build progress (PLAN.md §10)

## The second night, 0.5.0 (2026-10-24)

### Autopilot (ASTRO)

The capture used to shoot its plan and find out at the end what the sky had done. The autopilot
(camera/Autopilot.kt) now judges every light frame against the first clear ones and does what a
photographer next to the tripod would:

- **Cloud**: a frame with far fewer stars, or far fewer of the reference stars matched by the
  alignment, is left out at once (the stacker would drop it anyway) and an extra frame is added
  to the plan. The match count is the reliable signal: the star detector keeps only the
  brightest 300, and under cloud hot pixels fill that list (found with the simulator below), but
  cloud dims the stars below the alignment's flux gate, so the matches fall. Two cloudy frames
  in a row and it **waits**: the next exposures are checks, measured against the reference by
  the engine (`checkFrame`) and never kept, until the stars are back; then it carries on. After
  15 minutes (Settings › Autopilot) it finishes with the frames it has.
- **Soft stars** with as many stars still matched: the focus drifting as the lens cools. A frame
  past 1.7× the reference star size is left out and replaced; two soft frames in a row and it
  refocuses: FocusSweep.refine, five points around the current focus, walking further while the
  sharpest is at an end, then the parabola's vertex (about half a full sweep). Still soft right
  after: dew or wind, which it says, and it stops refocusing.
- **Dawn, the Moon, lights**: three frames in a row with the sky 2.5× brighter than at the start
  end the burst with the good frames (the darks are still taken). A brief bright spell does not.
- **Shoot for** 10/20/30/60 minutes instead of a frame count: as many frames as fit, finished on
  time with the good ones.
- The capture screen says what it is doing ("Checking whether the cloud has passed", with a wait
  clock; "Refocusing on the stars"), the result screen and the session page list what it did
  ("Left out 2 frames with cloud and 2 with soft stars and shot 4 extra · Waited 3 min for cloud
  to pass · Refocused from 0.100 to 0.160 dpt · ..."), and session.json keeps it. A re-stack
  leaves the same frames out.

Tested with a **simulated sky** (debug builds, camera/SkySim.kt + cpp/sim.cpp): armed from adb
(`--es sim "frames=12;exp=300;clouds=5-8;drift=13:0.06;dawn=20"`), every frame of a session - probe,
star focus, lights, darks - is replaced by a rendered star field with drift, hot pixels, cloud
over chosen light attempts, a focus error that blurs with distance from a "true" focus that can
move mid-session, and a brightening sky. On the emulator the whole loop runs as under a real sky:
cloud left out, waited out (3 checks) and replaced, the focus drift caught and refocused to
exactly the simulated new focus (0.160 dpt), the planned 12 good frames stacked with exactly the
4 spoiled ones rejected; dawn ending a 16-frame plan after 12. 17 JVM tests cover the decisions
frame by frame (hot pixels, dew, budgets, the time limit, dawn while waiting). Not yet under a
real sky: the thresholds (30% of the reference matches, 1.45×/1.7× star size, 2.5× sky) are
first guesses to check against real cloud and dew.

## The overnight session, 0.4.0 (2026-10-23)

### StarCam 2 processing (Settings › Look › Processing; Classic kept)

Judged with `tools\postlab` (DEBUGGING.md): the engine's post-processing as a command-line tool on
the emulator, run on a synthetic nightscape with known ground truth — light pollution towards the
horizon and one side, residual vignetting, a Milky Way band with dust lanes, 40 000 coloured
stars, a dark landscape with trees and two house lights, stack noise correlated like demosaiced
data — and measured: sky level and flatness, Milky Way contrast, landscape level, star colour,
noise. On it the classic pipeline left a grey sky (0.36), lifted the landscape to nearly the same
grey (0.33: its gradient polynomial was fitted through the dark ground), and turned every star
white (saturation 0.04).

- **The landscape is found** (post2.cpp): star-free cells darker than the sky, along the edge that
  faces the ground (from the phone's attitude, or every edge is tried), refined to the pixel by
  each column's sky-to-ground step, trees included.
- **The light-pollution gradient is fitted to the sky alone**, Milky Way cells rejected
  iteratively; cubic when the sky fills the frame, quadratic over the smaller sky above a
  landscape (a cubic there eats into the Milky Way). The ground is left as it is.
- **Sky and ground each get their own black point and stretch.** The sky's black point comes
  from the sky (classic took the darkest pixels of the frame: the ground), so the sky is dark
  again; the landscape lands at its own level with its detail. Along the horizon each pixel's
  own brightness decides which curve it gets, so silhouettes stay sharp without the dark outline
  a positional blend drew.
- **Star colour survives**: background neutralized with offsets (not gains), chroma denoise no
  longer runs over stars, extra colour in stars, over-range colours scaled instead of clipped.
- **Noise reduction**: a two-scale a-trous wavelet on the luma, Wiener-style gain against the
  sky's measured noise, stars excluded, in strips of rows (bounded memory at any resolution).
- Nightfall adapted to the darker sky; star sharpening ported.

Synthetic nightscape, classic → StarCam 2: sky 0.36 → 0.14, sky flatness 0.159 → 0.057, landscape
0.33 → 0.07 (with detail), star saturation red/blue 0.04/0.03 → 0.34/0.14, hue right 55% → 95%.
Without a landscape the cubic model flattens the sky 0.162 → 0.069. Classic's output is
byte-identical to 0.3.3's (hash-checked); the self-test gained a v2 sanity check.

### Photos come out upright

Frames were stacked and saved in sensor orientation, so a photo shot with the phone upright (the
way the app is held) came out lying on its side, and one shot in landscape with the phone turned
the "wrong" way came out upside down. Now the phone's attitude is read from the accelerometer
when the burst starts (ImageRotation.kt; aimed at the zenith, the way it was held before): the
JPEG, the master TIFF and the live stack preview are written turned, the DNGs carry the
orientation tag, and the processing knows which edge the ground is on. Older sessions, which
recorded nothing, look for it along every edge.

### Editing studio

Session › Edit photo opens the studio on the session's linear master (studio.cpp): the v2
pipeline runs on a box-averaged preview (long edge 1600) after every change, and on the full
master when saved. Light (sky brightness, contrast, landscape brightness), Colour (style,
saturation, star colour, warmth, tint), Clean-up (light-pollution removal, noise reduction, star
sharpening) and Frame (turn left/right: photos from before 0.4 came out sideways). Hold the
picture to compare with the original photo. Saving writes `out/starcam_<name>_edit.jpg` and
a new gallery photo; the original stays, and the settings are kept in `out/edit.json` so the
studio reopens where it was left. Sessions shot in Classic get the new look too.

### 1 frame vs stacked

Finalize also develops one frame alone, exactly like the stack (dark, hot pixels, demosaic,
vignetting, colour matrix, the same post pipeline), and saves it small as
`out/single_frame.jpg` (up to 20 MP stacks; ASTRO and PRO). The result screen and the session
detail show it against the photo with a handle to drag: the noise and the faint stars that
stacking bought, side by side - the picture to share.

A "Share 1 frame vs stacked" button (result screen, session detail) turns the comparison into one
picture - half a frame, half the stack, labelled, with the numbers underneath - for sharing.

### Watch it build up (replay video)

During capture the live stack is kept after every stacked frame as a small JPEG (`replay/`,
written on its own thread, never on the capture path; past 60 pictures a long session keeps every
second one, then every fourth, evenly spread, so a session holds 6-24 MB of them at most). The
result screen and the session detail offer "Make video": a few seconds (3-8 s, by frame count)
dissolving through the growing stack - "1 frame", "12 frames stacked", ... - then into the
finished photo with its numbers (frames, exposure, ISO, phone) and a small StarCam mark. 720x960
or 960x720 H.264 at 30 fps from the platform encoder (MediaCodec, YUV written directly, no
third-party code), read back with the platform decoder before it counts, kept in the session
(`out/stack_replay.mp4`), copied to Movies/StarCam (making it again replaces that copy) and
shareable. It is made outside the screen, one at a time per session, so leaving the result for
the session page neither stops it nor starts a second one over the same files; both screens show
its progress. Star trails sessions get their trails growing. The first slice of the "photo and
video from one session" idea.

### The capture screen's top bar: the best features, one tap each

(After the first phone test, on the user's call.) The top of the capture screen showed battery,
temperature and the lens switch, which nobody needs at a glance. It now holds three buttons:
**Tonight** ("Dark 20:21", "Dark now"; the planner), **Edit** (the studio on the newest photo
with a master) and **Video** (the newest session's replay: played if made, else made with its
progress on the button and played when done). Videos play in the app (VideoScreen, on a loop,
with Share) instead of Android's "Open with" question; the session page's replay card has Play
too. The lens switch moved into the viewfinder above the shooting plan, where the system camera
keeps its zoom. Battery and temperature show on the viewfinder only when they matter (battery
20% or less, phone warm or hot).

### Sky view: the night sky over the viewfinder

(After the first phone test, on the user's call: the planner gathered the right things but did not
show them in a way that read, and "like those star apps, on the camera preview" was the ask.) The
"Sky view" chip on the viewfinder, and "Show me the sky" in the planner, draw the sky over the
camera's picture where it really is: the Milky Way as a soft band that brightens towards the core,
the core itself ringed (an arrow to it when it is out of the picture, "below the horizon" before
it rises), stick figures of the constellations a night photographer steers by (the Sagittarius
teapot beside the core, Scorpius, the Summer Triangle, Orion, the Plough, Cassiopeia, the Southern
Cross...), 1630 stars down to magnitude 5 in their colours with the brightest named, the five
bright planets, the Moon with how much of it is lit, the Sun in twilight, the horizon and the
compass points. It follows the phone (rotation-vector sensor, smoothed), uses the camera's real
field of view (focal length and sensor size) and corrects the compass with the magnetic
declination at the place; a note asks for a figure of eight when the phone says its compass is
more than 20 degrees unsure. A slider moves the sky up to 12 hours ahead in 10-minute steps, so a
frame can be planned by daylight: aim at the trees, slide to 01:30, see where the core will stand.
Red with the red night UI. Nothing is drawn below the horizon but the arrow to the core.

All of it is worked out on the phone (sky/StarCatalog.kt, Planets.kt, SkyScene.kt): the Yale
Bright Star Catalogue (public domain; `tools\make-star-table.ps1` makes the 62 KB table in the
assets), the Milky Way's outline from galactic coordinates, the planets from JPL's Keplerian
elements (Standish, 1800-2050, well within a degree). JUnit checks it against SkyMath's Meeus
reduction (every star's altitude to 0.005 degrees), Meeus' Sun (0.03 degrees), the 2022-23
oppositions of Mars, Jupiter and Saturn, Venus never more than 47 degrees from the Sun, and the
projection's centre and edges. The planner's old sight (a crosshair onto the core) is gone: the
sky view does that and the rest.

### The Tonight planner answers first

It opens with the answer in one line ("A good night for the Milky Way", "Milky Way time: now",
"The core is down while it is dark", "The core stays low tonight", "The Moon is up while the core
is", ...), judged on the dark hours only (late in the year the core's highest point is in
daylight: "It sets at 20:20, before the sky is fully dark at 20:22"), the best window in large
type, three facts (dark from, the Moon, the core) and "Show me the sky at 01:30", which opens the
sky view at that time. Under it a bar of the night: twilight, moonlight and dark, the best
window, now. The timeline, the Moon card and all the times are one tap away under "Details".

### The live stack looks like the photo it is becoming

The capture screen's live stack (and so the replay video) stretched each channel on its own from
the frame's 10th percentile - on a nightscape that is the ground, so the sky showed as grey fog
with a colour cast - and sampled the stack nearest-neighbour, showing all of its noise. It is now
the photo's sky stretch in miniature (preview.cpp): area-averaged down to the screen, the sky
background neutral and at the photo's level a few noise sigmas above black, an asinh on the
luminance that keeps colours, a little extra saturation (the live stack has no colour matrix).
The Milky Way and star colours show from the first frames; the landscape is a silhouette until
the photo develops it. A twilight or moonlit sky already brighter than that level gets a nearly
linear curve. Judged in the lab (`preview_old,preview` variants) on the synthetic nightscape at a
20-frame stack's noise and at one frame's.

### Sky watch

While the lights come in (ASTRO, PRO), the first two frames with a real star field set a bar;
two frames in a row with under 40% of their stars ("cloud or haze is passing") or stars 1.6x
wider ("dew on the lens, or the focus drifted - wipe it gently") put a warning on the capture
screen, and two good frames clear it. Warnings only: the stacker already weights such frames
down or leaves them out; this tells the photographer while it can still be helped. The first
slice of the "adaptive autopilot" idea.

### Smaller things

- The Insane preset's foreground blend seeds its sky from the edge facing the sky, so it works
  for photos shot with the phone upright (it assumed the sensor's top row was sky).
- A finished re-stack scrolls the session page up to its new photo and says so there; a portrait
  photo replacing the placeholder used to push the message out of sight.
- The synthetic test session's stars are white after white balance (they were magenta).
- Onboarding has a fourth page for the planner, the studio and the comparison.
- Session screens never take the comparison image for the photo (the newest JPEG in out/ is the
  photo unless it is single_frame.jpg), and a re-stack replaces the stack's JPEG, never an edit.

### Tonight planner

The "Tonight" button (it reads "Dark 20:21" once a place is known) opens the planner: when it is
properly dark, the Moon's phase and when it is up, when and how high the Milky Way's core stands,
the best moon-free window for it, a timeline of the night (twilight shading, core and Moon
altitude curves, the best window, now) and all the times; since the phone test it leads with the
answer and hands over to the sky view (above). Other nights with the arrows. The sky maths (sky/SkyMath.kt, NightPlanner.kt)
is pure Kotlin checked by JUnit against the Meeus examples, JPL Horizons and USNO: event times to
seconds. Location is coarse, asked for only in the planner, optional (coordinates can be typed
in) and never leaves the phone: StarCam still has no internet permission.

### Review of the night's work

An independent review of everything above, and the lab, found these; all fixed:

- **Sideways photos aimed high.** The attitude test was OrientationEventListener's, which gives
  up 27 degrees from flat, so a phone in landscape aimed 70 degrees up at the Milky Way counted as
  flat and the photo came out sideways. On a tripod the reading is steady: it is now trusted
  until the phone is within ~9 degrees of flat. Nearer the zenith the way the phone was held
  before it was tilted up is used, marked as a guess (`rotationGuessed`), and the processing then
  looks for the landscape along every edge instead of trusting the guessed one.
- **Bright rim along every silhouette** (found with a new lab metric, `rim`): the landscape mask
  starts two half-res rows above the horizon, and the sky pixels it took in got the ground's much
  steeper curve. The per-pixel horizon decision now covers those rows, and a partly-sky pixel is
  never drawn brighter than the brighter of the two backgrounds. Rim +0.157 → +0.005 (classic
  +0.010), no dark outline.
- **The landscape went black behind trees on a turned photo** (the lab's rotated scene): two
  ground cells with a few bright specks ended the landscape there and left the rest as "sky".
  A cell under half the sky's brightness now counts as landscape unless it is clearly starrier
  than the sky (texture, lit windows, stars mirrored in a lake). The rotated scene now measures
  the same as the upright one.
- **The strip of sky above the horizon counted as stars**: the star mask judged each 32x32 cell
  by its median, and a cell mostly of ground marked the sky part of it as stars - no noise
  reduction there, extra colour. Once the landscape is known, sky and ground are judged each by
  their own side (1.50% → 1.13% of the frame marked).
- Noise reduction: a race between strips (a strip read its neighbour's rows while that one was
  rewriting them) - the margins are now read from a copy; and near-black pixels can no longer be
  scaled into coloured specks.
- The landscape missed its brightness target under a coloured sky (its black point subtracted
  the sky's colour offsets, which it never gets): 0.061 → 0.068 for a 0.07 target.
- Columns without landscape had a 2-pixel "ground" strip along the bottom edge.
- Studio: leaving while a master was still opening leaked the native image (~23 MB a time); Back
  could leave in the middle of a save; two saves shared one temp file; hold-to-compare now shows
  the original photo, turned with the edit; star-trails sessions start without gradient removal,
  like their own photo; an `edit.json` turn that is not a quarter is rounded.
- 1 frame vs stacked: compared with the stack's own photo, not a turned or toned edit; the frame
  is one of the better half that moved least against the reference (a frame from late in a long
  session left black wedges where it did not cover the stack); its development can no longer
  fail the whole finalize.

A second review, of the replay video and the live preview, found more, also fixed:

- The replay writer took the engine for its picture right after each frame, just when the
  capture screen asked for its own, and the screen gave up after one try: the live stack on
  screen could stay a frame behind. The screen now tries again for a moment, and the replay
  waits until the screen has its picture.
- "Make video" twice put two copies in the gallery; leaving the screen mid-video, coming back
  and starting again could have two encoders writing one file. See above: one job per session,
  outside the screen, and the gallery copy replaced.
- An encoder that stops taking or giving buffers ends the attempt after 15 s instead of
  spinning; a missing input image fails it instead of feeding an empty buffer; replay pictures
  are ordered by number (past frame 9999 names stop sorting); pictures it cannot read are named
  in the log.
- The rule that lets a dark, specky cell continue a landscape could, on its own, have started
  one in a dim lower sky (uncorrected vignetting, a moonlit gradient): it now only continues
  landscape already found below it. The lab's numbers are unchanged.
- A studio save cut off by the process dying left a temp file of up to ~150 MB in the cache: old
  ones go when the studio opens.

### Tested

Emulator, Android 14 (the phone's screen): the suite's 10 checks pass - self-test, session flow,
cut-off notice, synthetic stack (7 of 8 frames, portrait, with its single-frame comparison),
studio (open, restyle, save as a new photo), replay video (made, in the session and the gallery,
three frames read back: first frame, mid-build, the finished photo), Tonight planner, sky view
(from the planner, closed, from its chip), camera released on Home, session surviving a swipe
from recents. A live session on the emulator's camera
keeps its replay pictures. Android 16 with 16 KB pages: self-test, session, stack, studio,
replay and planner pass (there the video is made from a live session's pictures: Android 16
does not let an app open pictures adb put into its folder, so the synthetic session's cannot be
used), no crashes. 58 JVM unit tests; lint without errors. The lab: identical output over repeated runs; upright, turned, noisier, other
seeds and no landscape all measured. Not yet on the OnePlus 15: the processing on real night
data, the rotation on the phone's own sensor orientation, the compass, location, the replay
pictures from a real live stack and the phone's own video encoder.

## Production readiness, 0.3.3 (2026-10-22)

- **Target SDK 36.** Google Play takes new apps and updates only when they target Android 16
  (API 36) since 31 August 2026. Compile and target SDK are now 36, with AGP 8.13.2 and Gradle
  8.13 (8.7.3 was only tested up to 35). Checked on an Android 16 emulator with 16 KB pages:
  predictive back reaches the session's Back handling, edge-to-edge and night vision look as on
  14, the 16 KB-aligned engine loads, self-test PASS, a synthetic session stacks.
- **Release build.** R8 minification and resource shrinking (about 5 MB instead of 30+ MB; the
  unused Material icon library is gone from debug too), native debug symbols for Play, signing
  from an uncommitted `keystore.properties`. The minified build passed the same emulator checks.
- **Manifest features.** The optional RAW and manual-sensor features were declared under names
  Android does not have (`android.hardware.camera.raw` instead of `...camera.capability.raw`).
  Harmless while optional, but making one required would have hidden the app from every phone
  on Play. Now the real names, still optional: the app checks each camera's capabilities itself.
- **Sessions survive their screen.** The view model now lives as long as the process (the
  application owns its store). Swiping StarCam out of recents mid-session used to destroy the
  activity and cancel the capture with its view model; now the session carries on under its
  foreground service and reopening the app shows it.
- **Camera released in the background.** Leaving the app (Home, another app, screen off) closes
  the camera unless a session or test runs, and coming back reopens it. Before, it stayed open
  until the system revoked it, with the camera-in-use indicator on for an app nobody was using.
- **Session files out of the gallery.** MediaStore indexes Android/media, so every DNG frame,
  dark and session copy of the photo showed up in Google Photos and other galleries, dozens of
  near-black frames per session around the finished photos. A `.nomedia` marker in the StarCam
  folder (created at start, with a rescan so files indexed earlier drop out too) hides them; the
  finished photo is saved to Pictures/StarCam and stays in the gallery, and USB and file
  managers list the session folders as before. On the emulator: 37 StarCam images in MediaStore
  before, 0 after, the 9 gallery photos untouched; after a full test run (four new sessions,
  one stacked) still 0, with the new photo as the gallery's 10th.
- **Tooling.** `tools\emulator-test.ps1` (six regression checks on the emulator) and
  `tools\make-test-session.ps1` (a synthetic starfield session to stack anywhere); see
  DEBUGGING.md. Lint is clean again under the new AGP (local.properties written in properties
  syntax).

## Fixes from the second (GPT) review, 0.3.2 (2026-10-21)

`gpt review.md` (written against 5ed0d77) was checked finding by finding against the current
code. Already fixed by 0.3.1: #5 re-stack rewriting its source and forgetting rejections, #20
Cancel during the stillness wait, finish-now still asking for darks, re-stack lifetime. True and
fixed now:

- **Camera buffers outlive their reader (#1, #16).** Native ingest reads an Image's buffer
  directly. App teardown waited only for "active" jobs (a cancelled one is not, while its native
  call runs on), and a camera taken away mid-ingest closed the ImageReader at once - both could
  free the buffer under the read. Images handed out are now leased: a reader with an image out
  is retired and closed when the image comes back. The teardown also drained queued images after
  closing the reader, reading invalidated Images on the camera thread (a crash from the
  device-loss callback); the order is reversed and image properties are read before an image is
  published.
- **Camera open and setup races (#18, #19).** A disconnect handled between onOpened and the open
  returning no longer gets its closed device reinstated as ready. Devices and sessions delivered
  to a coroutine that is cancelled before it takes them are closed, not leaked; controllers whose
  open failed are closed with their camera thread. A lens switch turns the shutter off at the tap
  and re-checks camera ownership under its lock.
- **Dark frames against their own black level (#2).** Darks now carry their dynamic black level,
  the master dark is subtracted relative to it (not the light's), and the level is stored in
  master_dark.sraw for re-stacks and the dark library. On this phone the two differ by at most
  0.1 ADU, so photos are unchanged; on a sensor whose baselines differ, faint sky is no longer
  clipped away.
- **Signed calibration (#3).** Values below black stay negative through normalization, demosaic
  and the warp, and are clipped only after frames are combined. Clipping read noise at zero first
  left a positive floor in faint backgrounds. Backgrounds well above black are unaffected.
- **Two-frame "median" (#4).** For an even number of samples the stack returned the upper middle
  value, so two-frame stacks and thinly covered borders were maximum composites. The two middle
  values are now averaged.
- **Allocation failures (#11).** A failed region copy or demosaic left an empty image that the
  stacking tile then read (a native crash). The stack now fails with an error instead; trails and
  meteor blending check too.
- **Session manifest (#6).** session.json is written atomically (AtomicFile), and a manifest that
  does not parse is set aside instead of being replaced by an empty one.
- **Saving (#7).** The engine no longer deletes the frames at the end of the stack: they go only
  after the JPEG is on disk (and only when working files are not kept). A failed JPEG gets the
  failure buzz, not the success one; a missing TIFF is recorded.
- **Storage (#14, #15).** No more forcing 4 frames the disk cannot hold; a session that cannot fit
  two frames says so before it starts. Space is re-checked before every frame, keeping room for
  the outputs; a frame that could not be saved ends the burst instead of being counted. DNG
  failures are reported. A session whose cache is its only copy (no photo, no DNGs) is never pruned.
- **Gyro per attempt (#12).** The bump window restarts for every capture attempt, so a knock
  during a failed attempt or a camera repair no longer rejects the retry; frames with no gyro
  readings are logged as unjudged.
- **Focus sweep (#9).** A short settle frame moves the lens before each 2 s measurement, and a
  measurement still reported as moving is taken again.
- **Exposure (#10).** The decision scales from the probe's actual exposure and ISO. A sky too
  bright for the full exposure even at ISO 200 now gets a shorter one (down to 1 s) with more
  frames, instead of the full exposure with the background far over target.
- **Duration modes (#8, partly).** TRAILS and METEOR shrink their frame count once the real frame
  period is known, so 20 minutes means 20 minutes. Overlapping capture with processing is not done.
- **Meteors (#13).** Blended along a feathered band around the streak instead of its whole bounding
  box, with the stack's vignetting correction, and never from a frame the stack rejected.
- **Smaller.** 16 KB page alignment for the native library (#17); green white-balance gains by row
  parity (they were swapped on BGGR sensors like this phone's, harmless while both are 1.00); the
  luma-to-RAW transform includes the superpixel centre; the camera test reports FAIL or INCOMPLETE
  when its ingest/DNG workload failed or did not run (#24); logs prune while running, cap session
  logs, pause under 200 MB free and bound their queue (#25); the lint error is gone.

**Not done, and why.** #21 (staged thermal response beyond ending the burst at CRITICAL) and the
service-owned session (#1's long-term part) are larger changes. #22 (the 16-bit TIFF clips values
above 1 after white balance) needs a decision: a 32-bit float TIFF keeps them but is twice the
size and not every editor opens it. #23 (gradient removal fitting the landscape) and the chroma
denoise star check change how photos look and need real night shots to judge.

**Tested.** Engine self-test PASS on the emulator; emulator flows (cold start, Back, Cancel,
kill mid-session, re-stack error path) unchanged; lint passes; the library's LOAD segments are
16 KB aligned (llvm-readelf).

## Review fixes, 0.3.1 (2026-10-21)

A review of the whole app after the redesign; every finding was checked against the code before
anything changed. Photos come out of the stacker exactly as before, except where a dark frame was
not dark (now left out) or came from the dark library (now subtracted as structure only).

- **Session screens after a cold start.** The capture screen read the engine once, but the engine
  only exists once the camera is open and is replaced on every lens switch or reopen: a session
  started from a fresh launch could run while the screen kept showing the viewfinder. The view
  model now exposes the engine as a flow.
- **Dark frames.** Each dark is checked before it is used. One whose background is above 35 % of
  the night's sky, or 0.5 % of white, saw light (a lens not fully covered): it is left out, logged
  and recorded in the session's issues, and the result says so. Fewer than two usable darks are
  dropped (one dark adds its own noise to every frame). A 3 s pause after "Lens covered" lets the
  hand or cap settle. A set with any lit frame is never filed in the dark library; library darks
  are reused only within 5 °C of the battery temperature they were shot at (older ones have no
  temperature and are not reused), and are subtracted as structure only - hot pixels, amp glow -
  around their own median, since their black point is another night's. Darks shot in the session
  are subtracted as before. "Finish & stack now" no longer asks for darks afterwards.
- **Stopping.** Cancel during "Hold still" was only seen once the phone was still - never, with
  the phone in hand - or after two minutes; it now stops at once, and Cancel or Finish during the
  probe and the focus sweep stop there too. Once tapped, the buttons say "Stopping…" or
  "Finishing…" until the current exposure is in. Back does what the step's Cancel or Stop does
  (asking first during capture) and closes the result screens. The notification keeps only
  "Finish & stack": its "Cancel" threw a session away with one tap, without the app's question.
- **Finish before two frames.** Tapped during the first exposure, it ended on "Couldn't stack the
  photo"; the frame is now kept as a RAW and the screen says why. Before any frame, the empty
  session folder is removed.
- **Re-stack** runs in the view model, not the screen: leaving the screen no longer throws the
  result away or lets a second re-stack start on the same files. It starts from the options the
  session was stacked with (now recorded in the session header), leaves out the frames the gyro
  flagged as bumped (as the live stack did), opens on the options the current photo was made with,
  and keeps the shutter off while it runs (with a note on the viewfinder). The engine no longer
  rewrites a re-fed frame's cache file.
- **Interrupted sessions.** A session the app was killed in is announced at the next launch
  ("Last session was cut off. 6 frames saved - tap to stack them.") and recorded in its issues;
  its frames are kept for a week even when newer sessions would prune them.
- **Camera back after the screen was off.** When the system took the camera while the app was in
  the background, the app now reopens it as the viewfinder returns instead of showing "Camera
  problem", and a result on screen stays there.
- **Battery and heat.** The 12 % battery floor no longer applies on a charger or power bank (it
  cut such sessions to 4 frames). At thermal CRITICAL the burst ends and what was shot is stacked,
  with a note.
- **Focus reuse** is refused when the battery temperature moved more than 5 °C since the focus was
  found: the lens's infinity position moves with temperature.
- **Saving.** The JPEG is written beside its target and moved into place, so a full disk can no
  longer leave a truncated photo (or truncate the old one on a re-stack); the gallery copy stays
  hidden until complete and is removed if the copy fails. When no JPEG could be written, the
  result screen says so instead of spinning on a preview that never comes.
- **Night vision** reaches dialogs and the Pro sheet, which are windows above the activity's dim:
  they are dimmed the same, and the status-bar icons stay dark (the system's dim behind a dialog
  turned them white). The viewfinder tint is stronger and Diagnostics' preview is tinted too.
- **Smaller.** Session screens are not drawn behind the shield (their clocks kept the display busy
  all session); rings driven by a clock no longer animate (a new tween every 200 ms never
  settled); the frame counter fits three digits beside the ring (sessions run to 900 frames);
  `parallel_for` survives a thread that cannot start under memory pressure (it terminated the app).

**Tested.** On the emulator: cold start into a session, Back during capture (asks), Cancel while
reading the sky ("Stopping…", then idle), a kill mid-session (notice at relaunch, issue recorded,
marker cleared, tap opens the session), a re-stack started and left (error path: the emulator's
frames are saturated), night vision on the Pro sheet and a dialog.

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
- Checked at the phone's own font size (115 %): mode tabs size to their labels, the step strip
  says "Shoot" and stops growing past 115 %, the shield sits on the live stack so "Finish & stack
  now" fits, Pro tiles set units small. Session titles use the local time of capture (the folder
  name), not today's time zone. A failed stack gets its own error wording.

**Tested on the phone (wireless adb, 2026-10-21).** Installed as an update (sessions and settings
kept). Camera test 5 × 20 s: PASS, every frame back in 20.0 s, as with 0.2.1. Two real ASTRO FAST
sessions indoors: 10/10 frames each, no failed attempts, no camera repairs, bumped frames
detected and reshot. The second ended with the engine's own "no reference frame (too few stars)"
(no stars indoors) on the new error screen.

**Fixed: native crash in stacking (pre-existing since before 0.2.1).** The first indoor session,
with the phone moved during capture, crashed at the start of stacking (SIGSEGV in
`copy_region_u16`, report in `field-logs/crash_20261021_160645/`). Bootstrap pairing is not
one-to-one, so several reference stars paired with one current star gave a scale-0 similarity
that fitted them with zero residual (10 inliers, residual 0.000). `finalize` rebased every
transform onto that frame, the inverse blew the mapped tile corners past the int range, and the
saturating double → int conversion plus an int overflow in `stack_frames` became a tiny region
at x ≈ y ≈ 2^31 that passed the area guard. Fix: RANSAC and the refit only accept similarities
with a finite transform and a scale within 5 % of 1 (real frames differ by far less than 1 %);
`Transform::inverse` returns identity for a degenerate transform; tile, meteor-blend and
live-preview coordinates are clamped before any int conversion and NaN counts as outside.
Normal frames give bit-identical results. Re-stacking the crashed session now ends cleanly
("not enough usable frames" - that indoor scene has almost no real stars); self-test PASS.

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
