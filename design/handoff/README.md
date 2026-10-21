# StarCam — design handoff

Dark-mode Android UI for StarCam, an astrophotography camera app. 24 boards: every screen and state from the brief, night-vision red variants, onboarding, foundations and a component sheet.

## What's here

| Path | What it is |
|---|---|
| `index.html` | Open in a browser to see every screen on one page. |
| `screens/*.html` | One standalone HTML file per screen. No build step, open directly. |
| `assets/sky.svg` | Placeholder night-sky illustration used for the viewfinder, stack previews and thumbnails. Replace with the live camera preview and real photos. |
| `design-tokens.json` | Colours (dark + night vision), type scale, spacing, radii, sizes. |
| `source/` | Original design-canvas files (`.dc.html` + `canvas.json`). Only needed to re-import into the canvas. |

## Units

Phone frames are 390 × 866 = 1080 × 2400 px at 2.77×. **Read every px value in the HTML as dp** (font sizes as sp). All styling is inline on each element, so exact sizes, colours and spacing can be read straight from the markup.

## Screen map

| # | File | Screen |
|---|---|---|
| 01 | `Main.html` | Capture, idle (simple): viewfinder, mode, quality, plan line, shutter |
| 02 | `CapturePro.html` | Capture with the Pro sheet: shutter/ISO/frames dial, focus, start delay, 4 toggles |
| 03a | `HoldStill.html` | Session: hold still (countdown + stillness meter) |
| 03b | `ReadingSky.html` | Session: reading the sky (test frame, histogram, chosen settings) |
| 03c | `StarFocus.html` | Session: star focus (step 4 / 10, star-size graph) |
| 03d | `Capturing.html` | Session: capturing (frame 7 / 20, time left, exposure ring, live stack, frame tape) |
| 03e | `CoverLens.html` | Session: cover the lens (60 s auto-skip) |
| 03f | `Stacking.html` | Session: stacking (stages, percent, image revealing) |
| 03g | `Done.html` | Session: done (hero photo, stats, Share / Open / Re-process) |
| 04 | `Shield.html` | Screen shield (pure black) |
| 05 | `Error.html` | Error: capture interrupted, frames saved, next steps |
| 06 | `Settings.html` | Settings: tabs + search, Look group with Natural/Nightfall before-after |
| 07 | `Sessions.html` | Sessions gallery with status per session |
| 08 | `SessionDetail.html` | Session detail, full scroll (390 × 1480): zoom, stats, re-process, files, delete |
| 09a–c | `RedCapture.html`, `RedCapturing.html`, `RedSettings.html` | Night-vision red variants |
| 10 | `Diagnostics.html` | Diagnostics: camera test, live log, share debug report |
| 11a–d | `Onboard1.html` … `Onboard3.html`, `Permission.html` | First launch + camera permission |
| — | `Foundations.html` | Colour, type, spacing, icon set, app icon, wordmark |
| — | `Components.html` | Component sheet: shutter states, chips, buttons, toggles, dials, sliders, rings, cards, toasts, bottom sheet |

## Flow

- First launch: Onboard1 → Onboard2 → Onboard3 → Permission → Main.
- Capture: Main → (shutter) → HoldStill → ReadingSky → StarFocus → Capturing (⇄ Shield) → CoverLens → Stacking → Done. Any step can end in Error.
- FAST preset has no dark frames, so it skips CoverLens.
- Main → Pro button or PRO mode → CapturePro (sheet over the viewfinder).
- Main → Gallery → Sessions → SessionDetail.
- Main → Settings → Advanced → Diagnostics.
- The Night vision button switches every screen to the red theme.

## Rules a static mockup can't show

1. **Never flash white or show large bright areas**, including app start, splash, dialogs, ripples and transitions. Set the window background to `#0A0B10` before the first frame.
2. **Night vision is red only**: no blue, white or grey anywhere, including photos (grayscale, then multiply with `#FF3B2A`). The dimmer lowers brightness further.
3. **Something always moves during capture**: countdowns tick every second, the exposure ring fills clockwise from 12 o'clock, the frame tape fills, the live stack refreshes after each frame, and one line says what is happening now.
4. **Hold still**: the countdown restarts if the gyroscope detects movement.
5. **Screen shield**: pure black `#000` during long sessions (after 10 s by default). Only a dim frame counter and clock. Double-tap anywhere to show the capture screen.
6. **Cover the lens**: starts by itself when the frame goes dark, and skips after 60 s.
7. **Stopping** a running capture asks to confirm and keeps saved frames. "Finish & stack now" stacks the frames taken so far.
8. **Errors** say what happened, what was saved, and what to do next. Warnings are amber, errors red.
9. **Touch targets**: at least 44 dp everywhere, 56 dp for primary controls, shutter 84 dp. Every icon has a visible label or an accessibility label.
10. **Numbers** always use tabular figures (Geist Mono).
11. **Lens tip**: the 0.3× lens holds stars for 26 s vs 8 s on 1×. The idle screen shows 0.3× selected so the 22.5 s plan is consistent.

## Tokens at a glance

- Dark: background `#0A0B10`, surface `#12141C`, raised `#1A1D28`, text `#E8ECF4` / `#AEB6C7` / `#8891A5`, accent `#7FB4FF`, primary button `#24426F` with `#E3EEFF` text, warning `#FFC66E`, error `#FF6E6E`.
- Night vision: background `#120404`, surface `#1C0807`, text/accent `#FF5544`, secondary text `#D9483A`.
- Type: Geist (UI) and Geist Mono (numbers), both free on Google Fonts (SIL Open Font License).
- Radii: chip 14, button 18, card 22, sheet 28. Spacing on a 4 dp grid, 14 dp screen margins.
- Icons: 24 dp grid, 1.75 stroke, rounded caps and joins (inline SVG paths in the screens).

All copy and numbers are realistic sample content from the brief.
