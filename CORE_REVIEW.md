# The core, reviewed against the phone's own frames (10-11 Nov 2026)

A review of the one thing Stelli is for: turning a burst of RAW frames into a photo of the night
sky. Everything here was measured on the sessions the phone kept (`field-logs\phone-20261105`,
the night of 31 Oct 2026: a OnePlus CPH2769, 4096 x 3072, 10-bit RAW, 22.5 s at ISO 800) and on
the engine built natively on the PC (`tools\native`, below). The numbers are from those frames,
not from theory; where a conclusion is an inference it says so.

## How to test the engine now: `tools\native`

The engine has no Android code outside `jni_bridge.cpp`, so it builds and runs on the PC inside a
small Docker image (Linux, g++): no emulator, no adb.

    powershell -ExecutionPolicy Bypass -File tools\native\run.ps1 -SelfTest             (15 s)
    powershell -ExecutionPolicy Bypass -File tools\native\run.ps1 -Session <sessionDir> (40 s)

`-Session` is what `tools\stacklab\run.ps1` does on the emulator (same `lab.cpp`), with the same
numbers: the 8-frame session of 31 Oct gives peak share stack/frame 0.94 on both. `-Set key=value`
changes stacking options, `-Keep` keeps the linear master and the finished picture as raw files.

## What the frames say

1. **The exposure the app plans is not the exposure it gets.** The probe (1 s, ISO 3200) read a
   sky of 0.89 % and 0.83 % of white; scaled to 22.5 s at ISO 800 that promised 5.0 % and 4.7 %
   against a target of 6 %. The frames have 1.0-1.5 % (median 13 DN over black in green, 6-8 DN
   in red and blue). Both night sessions, a factor 3-5. A brighter indoor test (23 Oct) was off
   by less than 2 the other way. An offset of about 6 DN in the 1 s ISO 3200 frame explains all
   three (inference: the probe RAW is not kept). Nothing checks the real frames against the
   target, so the error stays for the whole session. Under a city sky it costs little (the sky
   still dominates the noise: variance 0.09 x signal + 0.2 DN^2, measured); under a dark sky the
   same offset reads as "bright sky", the ISO stays 2-3 stops too low and the sky sits at about
   1 DN of a 10-bit range.
2. **No session has ever had its hot pixels repaired.** Every report says `darkSource: none,
   hotPixels: 0`: the list of hot pixels comes only from dark frames, and the cover-the-lens step
   was skipped in the field. The frames hold about 50 pixels that are hot in every frame (5 DN
   and more over their neighbours, up to 180 DN) and a few thousand warm ones. Sigma clipping
   removes a hot pixel only where the stars move over it between frames: not near the pole, not
   in a short session, never in the unaligned (foreground) stack.
3. **"What's in this photo" failed on the real photo.** `whats_in.json` of 31 Oct has no
   solution. The solver is given the 400 brightest blobs of the finished 8-bit JPEG, and in a
   town those are lit windows and lamps (the alignment work of 5 Nov found 101 of the 150
   brightest "stars" were house lights). The engine knows better: a real star is one that moved
   with the sky from frame to frame.
4. **Star colours come from the camera's guess at white on a 1 s night frame.** The phone's
   colour matrix is the identity (its DNG carries placeholder matrices: sRGB's own), so colour is
   the auto white balance of the probe frame and nothing else, and the look then multiplies star
   chroma by 1.75. In the stack of 31 Oct every star is cyan. The catalogue the app ships has the
   B-V colour of each star to magnitude 5: once the photo is solved, the stars themselves say what
   white is.
5. **Star shapes are the lens's, not the stacking's.** The stack keeps 0.94 of a single frame's
   sharpness. But a single frame's stars are 6-7 RAW px wide and, on one side of the frame, twice
   as long as wide (9-16 px, the same direction in every frame: coma, not trailing).
6. **The landscape.** With the phone leaning in a window the camera itself moved between frames
   (the view jumped 12 px, then crept), so neither "the ground stands still" nor one mask from
   brightness holds. The look found no landscape in a photo that is half buildings.
7. Not a problem on this phone: lens shading (the RAW is already corrected; the leftover map is
   1.00-1.10), row and column pattern (0.04 DN), quantisation under a city sky.

## What is being built from it (0.7.0)

| | Change | Where | Checked by |
|---|---|---|---|
| 1 | **A pilot frame.** The first light frame is measured at the real settings; when the sky is more than about 2/3 stop off the target band the ISO (or exposure) is corrected before the burst goes on. The pilot is kept as frame 1 when nothing changes. The probe's offset is learnt per camera, so the first guess gets better. | `AutoExposure`, `CaptureEngine`, `SkySim` | unit tests with the numbers of 31 Oct; the simulated sky made to depend on exposure and ISO; emulator check |
| 2 | **Hot pixels from the lights.** A pixel far above its same-colour neighbours in every frame, with neighbours that do not share its light, is hot: no darks needed. Joined with the dark's list when there is one. | `engine.cpp`, `bayer.cpp` | self-test without a dark map; the real sessions' reports |
| 3 | **Sky-verified stars.** The engine writes the stars that followed the sky through the session, measured on the linear stack (`out/stars.json`); the plate solver uses those instead of the JPEG's blobs. | `engine.cpp`, `WhatsInPhoto` | the real sessions solve, as a unit test |
| 4 | **Colour calibrated on the stars.** Matched catalogue stars give the white balance (a solar-type star is white); the finished picture is developed with it, and the camera's calibrated balance is remembered for nights that do not solve. | `post2.cpp` (gains), Kotlin (fit) | unit tests; star colour against B-V on the real sessions |
| 5 | Research first: stars sharpened with the lens's own blur, measured from the stars; sky and ground as two layers, each with its own motion. Built only if the prototypes hold up on the real frames. | prototypes | pictures and numbers from the real session |

The dark-frame step stays as it is: the owner's design. Change 2 makes a session that skipped it
as clean as one that did not.

## Results

### Prototypes on the real frames (Python, before any engine change; 11 Nov 2026)

- **Stars verified by motion.** A reference-frame star counts as real when the sky's own motion
  puts a star there in most other frames. 8-frame session: 191 of 344 detections pass, every one
  in true sky; every blob on the houses, lit windows, blinds and brick wall fails (checked by
  eye on an overlay). 4-frame session: the sky moved under 3 px in all, so the test passes
  almost everything (255 of 371): there a star's own width and a quiet neighbourhood (few bright
  pixels within 30 px) have to do the sorting (216 left, nearly all sky).
- **The photo then solves.** From the verified stars: 54 catalogue stars matched at 0.57 px rms
  (centre RA 285.5, Dec +68.9: Draco, Ursa Minor, Cepheus; the window faces north), focal
  length 1404.9 px against the header's 1397.5, radial distortion k = 0.019. The 4-frame session:
  52 matched at 0.69 px, but only when the search started near the right part of the sky: a
  blind search needs a hint (the phone's own sense of where it points) or a better star list.
- **Star colour follows the catalogue.** ln(B/G) against B-V: correlation -0.94 (8 frames),
  -0.86 (4 frames). The gains that make a solar-type star white, relative to the camera's
  balance: R x 1.33, B x 0.74 in both sessions, which as absolute sensor gains is R 2.11 / B 1.69
  and R 2.10 / B 1.68: two sessions, two pointings, the same answer within 0.5 %. The camera's own
  balance (R 1.58, B 2.30, right for the lamp-lit houses) is what turns every star cyan.
- **A lit landscape must keep the camera's balance.** With the stars' gains applied to the whole
  picture the window frames turn yellow and clipped highlights get yellow blocks; applied to the
  sky only (and never to clipped pixels) the stars get their colours and the houses stay as shot.
- **The sky's brightness can be read off the same numbers.** The zero point (V + 2.5 log flux) is
  9.46 in both sessions with a scatter of 0.17 mag; with the sky's level per pixel that is about
  17.3 mag per square arcsecond: a town sky, as it is.
- **Lens distortion does not limit this alignment.** A homography between undistorted star
  positions fits no better than a plain one (0.45 px either way, 27 px of motion). Over a session
  of tens of minutes the sky moves several times further; untested, no data.

(engine and app results are added below as each change lands)
