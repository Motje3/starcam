You are a senior mobile product designer. Design a complete, modern, dark-mode UI for an Android astrophotography camera app called StarCam (working name). Produce high-fidelity phone mockups (portrait only, ~20:9, e.g. 1080×2400) for every screen and state listed below, plus a component sheet. Quality bar: premium, award-level polish, but built for real use outdoors at night.

## What the app does
StarCam turns a phone on a tripod into an astrophotography camera. The user presses ONE big button and the app does what an expert would do:
1. Waits until the tripod is perfectly still (gyroscope).
2. Takes a test frame to measure the sky and picks shutter speed, ISO and number of frames automatically (e.g. "22.5 s · ISO 1600 · 20 frames").
3. Focuses on the stars themselves (automatic star focus, ~30 s).
4. Shoots a burst of long RAW exposures (10–30 s each; a session takes 3–20 minutes).
5. Asks the user to cover the lens for a few "dark frames" (removes hot pixels; auto-skips after 60 s).
6. Aligns the stars across all frames and stacks them on the phone, removes light-pollution gradients, brings out faint detail, balances colour.
7. Saves a finished photo to the gallery, plus pro files (16-bit TIFF, RAW DNGs) for desktop editing.

Modes:
- ASTRO: stars and Milky Way. Quality presets FAST (8 frames), BEST (20 frames + dark frames), INSANE (40 frames + dark frames + sharp-foreground blend).
- TRAILS: star trails over 5–60 minutes.
- METEOR: watches the sky for up to 2 hours and catches meteors (beta).
- PRO: full manual control.

Other features: lens picker (1× main, 0.3× ultrawide) with a tip when the wider lens is better; a live stacked preview that improves as frames arrive; per-frame quality readout (star count, sharpness, drift); automatic recovery when the camera hiccups; battery and heat awareness; session history with re-processing ("re-stack") in a different look; red night-vision mode with extra dimming; a black screen shield for long sessions; diagnostics (logs, camera test) for troubleshooting.

## Who uses it, and where (this drives every decision)
- Hobbyist to enthusiast night-sky photographers, from first attempt to experienced.
- Outdoors in near-total darkness, phone on a tripod, often cold, sometimes wearing gloves.
- Their eyes are dark-adapted: a bright screen ruins night vision for 20+ minutes. The UI must never flash white or show large bright areas.
- Touching the phone shakes the tripod, so the ideal flow is: set up, press once, step back. During capture the screen is glanced at from a distance.
- Long exposures look frozen. The UI must always show that work is happening (live countdowns, progress, the current step).

## Design principles
1. Dark mode only: near-black backgrounds, low-luminance surfaces, one accent colour, nothing bright.
2. Simple by default, powerful on demand: the default capture screen shows only the viewfinder, mode, quality and one big shutter button. Every detailed control lives behind a clearly marked "Pro" entry point (toggle, drawer or sheet). Diagnostics live deeper, inside settings.
3. Glanceable during capture: huge numbers (frame 7 / 20, time left), a calm progress ring, the live stack preview, one line saying what it is doing right now.
4. Big targets: primary controls ≥ 56 dp, shutter ~84 dp, usable with gloves; no small icons without labels.
5. Red night-vision theme: every screen also has a red-on-black variant (red tones only, no blue or white) with a brightness dimmer.
6. Honest and friendly: plain-language explanations behind (i) buttons, warnings in amber, and errors that say what was saved and what to do next.

## Visual style
- A premium "astronomical instrument" feel: precise, calm, technical but warm. A high-end camera app crossed with a planetarium app.
- Starting palette (refine freely): background #0A0B10; surfaces #12141C and #1A1D28; text #E8ECF4; accent astro blue #7FB4FF; warning amber #FFC66E; error #FF6E6E. Night vision: background #120404, text/accent #FF5544.
- Typography: clean geometric or grotesk sans for UI; tabular or monospaced figures for every number (exposure, ISO, timers, counts).
- Subtle depth: translucent dark glass panels over the viewfinder, hairline borders, soft accent glows. At most very subtle star or constellation motifs; no clip-art, no cartoon moons.
- Icons: one consistent rounded outline set.
- Show motion as states: progress rings, a ticking frame counter, the live stack image building up.

## Screens and states to design
1. Capture, idle, simple: 4:3 viewfinder, mode switch, quality presets, big shutter; small Pro, Gallery, Settings and Night-vision buttons; a compact plan line ("Auto · 22.5 s · ISO 1600 · 20 frames · ~8 min"); lens chips 1× / 0.3×.
2. Capture, Pro revealed: manual shutter (0.5–30 s), ISO (200–6400), frame count (4–99), focus (Auto stars / Infinity / Manual slider), start delay (0 / 3 / 10 s), toggles for dark frames, RAW saving, brighter framing preview, spirit level. Camera-style dials and steppers, not a long form.
3. Session flow, one mockup per step:
   - Hold still: countdown plus a stillness meter.
   - Reading the sky: the test frame being measured.
   - Star focus: step 4 / 10 with a small sharpness graph.
   - Capturing: frame 7 / 20, big time-left, an exposure countdown ring, live stack preview, "stars 312 · FWHM 1.6 px · drift 0.4 px", buttons Cancel, Finish & stack now, and a screen-shield button.
   - Cover the lens: prompt with a 60 s auto-skip; buttons Lens covered and Skip.
   - Stacking: stages (aligning, stacking, finishing), percent, the image appearing.
   - Done: final photo as the hero; "20/20 frames · 7 min 30 s total exposure · took 9 min"; saved to gallery; Share, Open, Re-process.
4. Screen shield: pure black, a tiny dim frame counter and clock, "double-tap to show".
5. Error: what happened in plain words, "8 frames are safe", buttons Share log and OK.
6. Settings / Pro panel: groups Exposure, Focus, Capture, Stacking, Look, Motion, Interface, Advanced, with tabs or search. Each row has a label, current value, control and an (i) that expands an explanation. The Look group has a before/after style preview (Natural vs Nightfall).
7. Sessions gallery: grid or list of past sessions with thumbnail, date, mode, frame count and status (finished / RAWs only / failed, frames saved).
8. Session detail: zoomable image, stats, "Re-process with different settings" (stretch strength, style, saturation, colour noise reduction), export info, delete.
9. Night-vision red versions of screen 1, the Capturing step and screen 6.
10. Diagnostics (tucked away): live log list, "Share debug report" button, a Camera test card.
11. First launch: 2–3 onboarding cards (use a tripod; press once and step back; cover the lens when asked) plus the camera permission request.
12. Optional: app icon and wordmark.

## Realistic content to use
"22.5 s · ISO 1600 · 20 frames", "Frame 7 / 20", "~5 min 12 s left", "Exposing 14 / 22.5 s", "stars 312 · FWHM 1.6 px · drift 0.4 px", "Stacked 20/20 frames", "Darks: reused (2 days old)", "Battery 86% · cool", "0.3× holds stars for 26 s vs 8 s — better for wide Milky Way".

## Avoid
Light mode, white backgrounds, bright full-screen flashes, tiny text, cluttered HUD on top of the viewfinder, ads, generic stock space wallpapers, cartoonish or overly playful visuals.

## Output
One consistent design system across all screens (spacing, type scale, components). Each screen as a separate labelled mockup. Plus a component sheet: shutter button states (idle / running with progress ring / disabled), chips, toggles, dials and sliders, cards, progress rings, toasts and a bottom sheet.
