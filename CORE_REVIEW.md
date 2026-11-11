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
   sharpness. A real star is about 4.4 x 3.1 RAW px (6-7 px by plain moments), 1.25 to 1.65 times
   as long as wide, the direction turning across the field: the lens, not trailing. (My first
   reading of "9-16 px, twice as long as wide on one side" was house lights: 86 of 108 detections
   in that third fail the motion test. The app's own "Star size" has the same flaw: it measures
   the 30 brightest blobs, which in a town are lit windows.)
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
| 4 | **Colour calibrated on the stars.** Matched catalogue stars give the white balance (a solar-type star is white); the stars of the finished picture are developed with it, and the camera's calibrated balance is remembered for nights that do not solve. | `post2.cpp` (gains), Kotlin (fit) | unit tests; star colour against B-V on the real sessions; the real photos with and without, block by block; a test session drawn to a known answer, on the emulator |
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

- **Sharpening the stars with the lens's own blur: not built.** A blur measured from 87 real
  stars (it changes over about 200 px of the frame) and Richardson-Lucy on the stars alone makes
  the brighter fifth of them tighter (4.2 x 3.1 px to 2.9 x 2.0) but no rounder (1.36 to 1.51),
  shows only at full zoom, and at screen size the brightest stars come out a quarter dimmer
  because the look clips their cores. On the whole frame it turns noise into 549 false stars.
  A kernel that does round them leaves ripples. The scripts and pictures are kept
  (`field-logs\research\star-sharpening-src`, local only); an opt-in "sharpen stars" could come
  from it, nothing automatic should.

### The engine and the app's own solver (11 Nov 2026)

- **The app's plate solver, given the engine's star lists, solves both real photos blind.** With
  every detected blob (lit windows included), as it was fed until now: not recognised, either
  session. With the sky-verified stars only: 57 catalogue stars matched (RA 285.70, Dec +68.69,
  rms 1.1 px of the full picture, distortion k 0.0197) and 53 (RA 269.10, Dec +67.48), in 0.3 s
  each, no hint needed.

- **The pilot frame** (`fe1be88`). The real case of 31 Oct (ISO 800, sky 1.5 % of white) is now
  corrected to ISO 1600 before the burst goes on; a dark site whose probe promised too much goes
  to the camera's top stop; inside 2.5-8 % of white the pilot simply is frame 1. 18 unit tests
  on the decision, and an emulator check (`exposure`) that runs a simulated session with a
  biased probe (pilot dropped, ISO raised, 4 of 4 frames) and one where the first guess is right
  (pilot kept, no extra exposure). On the phone itself: not yet (the emulator has no per-frame
  black level).
- **Hot pixels from the lights** (`9f024cc`). 2646 sites on the 8-frame session, 2915 on the
  4-frame one, where no session had any repaired before. 85 % of one session's list shows up in
  the other, taken 10 minutes earlier (16 % for random sites): a pattern of the sensor, not
  noise. The stack is as sharp as before (peak share 0.93 on the 8-frame session, where it was
  0.94, and 0.96 on the 4-frame one; an earlier text here said 0.98, which was measured against
  another reference frame). The first design let star cores through when the stars stand still
  and took lit window edges for hot pixels when the phone moved; three more tests fixed both
  (`hotpix.cpp` says which, with the numbers).
- **Stars that followed the sky** (`out/stars.json`). 158 real stars of 364 detections, 139 of
  413: positions, light per channel, surroundings, width. Detection keeps 500 blobs instead of
  300, so a town's lit windows leave room for stars; a frame's width and the choice of the
  reference frame stay on the brightest 300, or the reference moved (frame 3 to 7) and with it
  what the look made of the buildings. "Star size" is now measured on real stars: 2.2 px (luma),
  not the 5.8 px of lit windows.
- **The stars' white balance in the look.** Four tries on the real photo:
  1. gains on every pixel that is not bright: under a dark sky the landscape is darker than the
     sky, so a brightness of the frame cannot say what is sky;
  2. gains where the picture is not "bright and starless", judged against the sky's level at
     its own stars: right about the sky, but the look measures the sky's glow and background on
     the balanced picture and takes them off the lit landscape too, which turned a lamp-lit
     pillar cooler, and its landscape detection changed its mind about the houses;
  3. everything the look measures and removes left as it is without gains, the gains acting at
     the stretch on what stands above the sky's one neutral level, weighted by the same "not
     bright and starless" mask. The lit pillar came out 169/157/119 with and without gains, and
     that was taken for proof. Measured over the whole frame, in blocks of 16 px away from
     every star, it was not: 48 % of those blocks moved by more than a level of 255, the
     mid-tones by 7 (red up, blue down), dimly lit brick from 90 to 101 in red, the row of
     houses by 5, the sky's glow went greener (17/50/66 to 14/50/61) and its darkest part
     bluer. A sky is not one colour all over, so "above its neutral level" is the glow, the
     gradient's leftovers and every wall too dim for the mask;
  4. **as built: only the stars.** The gains act on a star's own light: on what stands out of
     the sky right beside it (the median of a ring of 16 pixels round it), is small (nothing a
     square of 26 px fits into), is not long and thin (a roof's lit edge), stands in the sky on
     every side (the corner of a lit window does not) and has stars that followed the sky
     around it (a row of lit houses has none). The same photo: 0.25 % of the starless blocks
     move by more than a level, the houses and the window frames by 0.00, the pillar by 0.1,
     the brick by 0.9 (specks of mortar on the wall's edge against the sky); 0.55 % of all
     pixels change by more than 2 levels, and those are the stars. Their red over green goes from 0.30 to
     0.63 and blue over green from 1.43 to 1.20 (the 4-frame session: 0.51 to 0.89, 1.28 to
     1.06): from all cyan to white, with the orange and the blue ones their own.
  What is broad in the sky is left too: the Milky Way's band keeps the camera's colour. Try 3
  would give it the stars' balance and is right for it in principle, but there is no dark-sky
  frame here to judge it on, and under a town it is the glow that takes the colour.
  The self-test holds it to this (`wb_gains`, case i: a glow with a colour of its own, a roof's
  edge, a dim window and a row of lamps come out as without gains, to a millionth of the range,
  while the stars' red over green rises 1.47 times).
  The mask's sky points are the stars that followed the sky, were really carried along by it
  and have a star's width (`sky_point`): where the sky hardly moves, a lamp follows it too, and
  such a session gets no balance at all.
- **A halo the stars always had.** With their colours right, the brightest orange star had a
  dull red ring 10-14 px out (7-11 levels of red on a sky at 1). Nothing in the linear picture
  is there: the chroma denoise gave each pixel the average colour of its surroundings, stars
  included, so every star lent its colour to a ring round it - blue on blue until now. The
  average is now taken without the stars; the ring is gone, and so is the blue one in a photo
  developed without the stars' balance (blue 22-26 down to 21, the sky's own, at that star).
- **Found on the way, not fixed: the look's landscape detection is fragile on lit scenes.** The
  same session rendered with another reference frame was given a "landscape" of 3 % of the
  frame along the bottom, drawn as dark blocks with hard edges over the lower houses. It is
  the old one-horizon model (dark, starless land along one edge); a town through a window is
  not that. The two-layer work below is the real answer.

### Sky and ground as two layers: prototype (11 Nov 2026)

Each frame aligned twice - with the sky, and with the ground's own motion - and a matte from
which of the two each pixel agrees with over time, then a composite: sky from the frames where
that pixel is sky, ground from the ground-aligned stack.
- **On a synthetic tripod session it works.** Ground as sharp as a single frame (0.94-0.96 of
  its edge sharpness, against 0.08-0.23 in today's star-aligned stack), matte right for 90-93 %
  of the ground and 94-96 % of the sky at 8, 20 and 40 frames, no bright rim, sky noise at the
  horizon a fifth of the plain stack's smear. It needs about 10 px of motion between sky and
  ground over the session (8 frames are enough below declination 50; near the pole 18 and
  more); with less, everything is called sky and the result is today's stack.
- **On the real session it fails for the nearest things.** The phone moved in the window, so
  the window frames a metre away moved by parallax like neither sky nor ground, and being out
  of focus and featureless they were called sky. Houses, lit windows and the tree came out
  right and visibly sharper than today.
- **Not built yet.** It is a large port (registration, evidence, a geodesic fill, a second
  stack) and the only real data is the one scene it cannot do. It wants a real session on a
  tripod with a horizon first; then it is the next big thing. The prototype, its synthetic
  scenes and the port-ready description are kept in `field-logs\research\two-layer-matte-src`
  (local only).

### The app: the stars put to work (11 Nov 2026)

Between the stack and the look the engine hands the app its star list and waits for an answer
(`StackCallback.onStars`, found by name through JNI; `proguard-rules.pro` keeps it in the release
build). There the photo is solved from the stars that followed the sky, the catalogue's stars are
found on them, their colours give the white balance and their light the sky's brightness; all of
it is kept in `meta/sky.json`.

- **Both real sessions, from the star lists their stacks wrote** (`RealSkyTest`, on the PC):
  solved blind in 0.2 s (57 and 55 stars matched, 1.1 and 1.4 px rms of the full picture).
  White: R x 1.353, B x 0.778 from 56 stars, and R x 1.361, B x 0.777 from 52; as the sensor's own
  balance R 2.138 / B 1.786 and R 2.137 / B 1.756, two sessions and two pointings 0.05 % and 1.7 %
  apart. (Blue is 5 % above the prototype's 0.74, probably the other aperture.) The sky: 17.45
  and 17.39 mag per square arcsecond.
- **Which stars, and when a fit is used.** The first version took every star of snr 10 and more
  and refused a fit whose stars scatter more than 15 %. Both were wrong:
  - What matters is how well the white point is known, not how much single stars scatter: ninety
    faint stars that scatter 15 % each fix it to 2 %, a dozen do not. A fit is now used when the
    standard error of the white point is under 3 % (the real sessions: 0.5-0.9 %), with a held
    slope's own doubt added when the stars' colours all lie to one side of the Sun's.
  - The engine's "snr" is the star's green light over the noise of single pixels. A demosaiced,
    aligned stack shares its noise between neighbours, so the light in an aperture is two to
    three times less sure than that says (red and blue more): simulated stars of snr 20-27
    scatter 0.16 in ln(r/g), three times what that number would give. And the logarithm of a
    noisy ratio leans one way: with the stars of snr 20-30 in, the simulated sky's red came out
    3.6 % off; from the stars of snr 30 and more, 0.1 % (blue 1.1 %). So the best-measured stars
    are asked first (snr 30+, then 20+), fainter ones never. Every star the catalogue has in the
    real photos is at 24 or more.
- **The camera's own balance is remembered** per lens (the session's camera gains times what its
  stars said, smoothed over sessions) and used on a night that does not solve or whose stars are
  too poor. A re-stack and a simulated sky teach it nothing. No balance is applied, or claimed
  on the session's page, when fewer than 10 stars mark the sky for the look.
- **"What's in this photo" has its answer when the stack ends** (`whats_in.json`, "source":
  "stack"); asked later, a stacked photo is solved from `out/stars.json`, any other photo from its
  JPEG as before.
- **The studio develops with the same balance** (the gains of `sky.json`, the sky's points of
  `stars.json`), so the preview, the looks and a saved edit match the photo. A re-stack takes the
  Star colours setting of the day, as it takes the look.
- **The solver stops at a deadline.** A field with no sky in it costs every pair of stars in
  full (2.4 s on the emulator's release build by the build's own log, more on a phone). The photo
  waits 4 s at most; the first version ran the solver on a second thread and left it running
  beside the look after that. The search now ends by itself and checks what it had found.
- **Not shown: how faint the stack reached.** Two ways to say it were both wrong. The faintest
  stars of the list are magnitude 6.0: that is where a list of the 500 brightest blobs ends, not
  the photo. From the engine's noise a 5 sigma star would be magnitude 7.8 (8 frames) and 7.2
  (4 frames), about a magnitude too deep for the reason above. It wants the engine to measure the
  scatter of empty apertures; until then the session's Details show the star colours and the
  sky's brightness only.
- **Checked end to end against a known answer.** The test session's catalogue stars are now drawn
  in their own colours (a star like the Sun white at red x 2.11 and blue x 1.75, redder and bluer
  by B-V) in frames balanced for the sky (2.45, 1.59): the stars must give 0.861 and 1.101.
  On the PC: 0.847 and 1.086 from 48 stars, slopes +0.30 and -0.40 (drawn: +0.30, -0.42), and
  developed with them the stars' red over green changes 0.80 times and blue over green 1.08
  times while no starless block moves (the worst by 0.12 of a level).
  On the emulator, debug and release builds alike: the session re-stacked from its page, solved
  from its own stars while it was stacked (94 matched), white 0.845 and 1.104 from 41 stars of
  snr 30 and more (known to 1.3 % and 0.9 %, slopes +0.29 and -0.43), the engine's report
  showing the photo developed with those gains by 101 sky points, and the studio opening with
  them. On the release build that is also the proof that R8 kept the hook.
- **Found on the way: the test sessions' noise was not independent from frame to frame.** Each
  frame had its own `new Random(1000 + f)`, and this Random's numbers run in step for seeds next
  to each other: thousands of sites stood above their neighbours in every frame. The engine
  took them for hot pixels, rightly: 2583 (its cap) where 300 were drawn. With one generator for
  all frames it finds 298 of the 300, and its threshold is the 6.0 DN the noise predicts.
- **Not yet on the phone.** The phone runs the Play build; all of this reaches it with 0.7.0.
