# Stelli's look (0.6.0, November 2026)

The style the app is built in, from the owner's main-screen design (an image AI's mockup, 5 Nov
2026). The code is `app/src/main/java/com/wm/starcam/ui/theme` (tokens, type, icons) and
`ui/components/Components.kt`. Give this page to whoever designs the next screen, with screenshots
from `tools\ui-shots.ps1`, so it fits.

## Feel

A deep purple night sky, lavender light, a soft violet glow on whatever is chosen or primary.
Few words: icons, numbers and pictures first, a short label where an icon alone would be unclear,
explanations behind an (i). Dark only: the brightest thing on screen is the shutter's lavender
disk, because a bright glance undoes 20 minutes of dark adaptation. Night vision turns everything
red on black.

## Simple first, more behind a tap

The owner's rule (5 Nov 2026): a screen shows the few things most people use, with a word or two
each; the rest waits one tap away.
- **A line under a setting only when its control cannot say it** ("30 s at most on this lens",
  "Needs dark frames"), never "On · ..." beside a switch or a value a segment strip already shows.
- **More options** at the bottom of each settings tab (Advanced: Troubleshooting), naming what is
  inside; the search still finds everything.
- **A row that opens** (`ScMoreRow`: an icon, the name over one line, a chevron) for the parts of
  a page few people need: Re-process, Details, What now?
- **One row with one button** for a thing to do (What's in this photo: Find out; a video: Make,
  then Play), never a paragraph above a full-width button.
- **Why and how behind the (i)**, as before.

## Colours

| Token | Normal | Night vision | Use |
|---|---|---|---|
| background | `#0E0820` | `#120404` | the screen; a gradient from `#0F0822` (top) to `#1B0D35` (bottom) |
| surface | `#1B1431` | `#1C0807` | cards |
| raised | `#261D40` | `#260B09` | the top of a button's gradient, raised tiles |
| buttonSecondary | `#1E1535` | `#1C0807` | buttons, icon squares |
| hairline / strong | lavender `#C9B2FF` at 16 % / 27 % | red at 18 % / 28 % | edges |
| buttonBorder | `#B184FF` at 55 % | red at 28 % | a secondary button's edge |
| text / text2 / text3 | `#F5F0FF` / `#C9BEE4` / `#948CB1` | `#FF5544` / `#D9483A` / `#A8392E` | |
| accent | `#B88AFF` | `#FF5544` | chosen things, icons, rings |
| accentText | `#D3B6FF` | `#FF6A5A` | accent words |
| accent2 | `#E7A0FF` | `#FF7A5C` | the pink of the icon's horizon: gradients' bright end |
| selected fill / edge | `#8C55EA` at 38 % / `#B184FF` | red at 20 % / 60 % | the chosen segment, tab, chip |
| primary | gradient `#6B37C6` to `#45208A`, edge `#B993FF` | `#4A120B` to `#320A06` | the main button |
| glow | `#9F5CFF` at 55 %, blurred 10-20 dp | half as strong | behind primary and chosen controls |
| warning / error | `#FFC870` / `#FF7E96` | `#FF7B5C` / `#FF5544` | |
| success | `#5FE0A4`, fill at 18 % | `#FF6A5A` (no green at night) | done: a finished step's check |
| two-tone icons | lines `#E6D9FB`, detail `#C98DFF` | `#D9483A`, `#FF5544` | |

## Type

**Noto Sans** (Google Fonts, SIL OFL), one variable file. Every number in tabular figures.

| Style | Size / weight | Example |
|---|---|---|
| title | 26 sp SemiBold | a step's or verdict's headline |
| screenTitle | 20 sp SemiBold | a screen's name in its top bar |
| headline | 16 sp SemiBold | a card's name |
| body / bodySmall | 15 / 13 sp Regular | text |
| button | 15 sp Medium | buttons, tabs |
| label | 11 sp SemiBold, spaced 0.12 em, capitals | TONIGHT, TIME LEFT |
| display / countdown | 64 / 80 sp Light | the frame count, the hold-still countdown |
| stat | 22 sp Medium | numbers on tiles |

## Shapes and spacing

- Buttons and chips are **pills**. Tool buttons are **rounded squares** (16 dp corners, 44-48 dp).
- Cards 22 dp corners, tiles 18 dp, the bottom sheet 28 dp at the top.
- Screen margin 16 dp; 8-12 dp between things in a card; touch targets at least 44 dp, the
  shutter 84 dp on the capture screen. The capture screen is kept slim, as in the design: a 44 dp
  top row, nothing between it and the viewfinder, a 42 dp row over the viewfinder's foot (the
  session length, the lenses), 46 dp tabs, 58 dp gallery and Adjust squares. (The mockup draws its
  buttons smaller still; 44 dp is the least a finger, in a glove, can hit.)

## Components

- **Pill button with icon** (Light map, Find): a two-tone icon and a word, a lavender edge and a
  faint glow. **Primary button**: the purple gradient with a glow (Next, Share, Save).
- **The capture screen's top** (the owner's design, 5 Nov 2026): the Light map pill and the
  **moon** (a crescent with a star, round) on the left, Find and the **Quick tools** square (four
  rings, always lit) on the right. Nothing else over the viewfinder's top.
- **A card from the top row** (Quick tools, Tonight): violet glass with a lavender edge, hanging
  just under the row, its curved tail pointing up at its button; its name and a cross. The button
  is lit while it is open; a touch anywhere else closes it.
- **Quick tools**: 2 × 2 tiles (an icon, the name, and under it a switch or "Open ›"; Settings'
  chevron beside its name). The switches leave it open.
- **Tonight** (from the moon): a line each for the Moon, the dark hours, your sky's Bortle class
  and the best thing to shoot (its time under it), an icon before each, and Plan tonight.
- **A pill that opens a list** (the session length, over the viewfinder's foot): a clock, the
  value and a chevron in a dark glass pill; tapped, the choices stand on it in a glass card, the
  chosen one lit. The lenses share its row, on the right.
- **A pill with a switch** (Night alerts, on the calendar's top bar): a bell, the name where it
  fits beside the title, a small switch (38 × 24); the whole pill is the switch.
- **The next big night** (the head of the calendar, from the owner's store picture, 8 Nov 2026): a
  card 200 dp tall, stars over two lines of hills against a violet horizon; on its left what the
  night is about (meteors, the Milky Way, an eclipse, a planet, a pairing, or a new moon as it is:
  only just there); the date in lavender, the name at 26 sp, a few words, the rating as five dots.
  It opens that night in the planner.
- **Before and after** (the photo page's 1 frame vs stacked, Edit): a lavender line and a round
  handle with a lavender edge, following the finger. In Edit it comes once there is an edit to
  compare, without words on the photo.
- **Segment strip** (focus and the start delay in Adjust): a dark glass pill of choices; the
  chosen one lit, edged and glowing.
- **Tabs** (the modes): icon and word, the chosen one lavender with a glowing bar under it.
- **Shutter**: a lavender disk inside a ring that runs from pink to violet, glowing; dim and flat
  when it cannot fire.
- **Cards**: violet glass with a hairline edge; a card's head is its icon in lavender and its name.
- **Icon badge**: an icon on a softly lit disc (list rows, empty states, a settings page's head).
- **Progress ring**: lavender, pink at its start, with a soft glow.
- **A session's head** (every step of a capture, 5 Nov 2026, the owner's design of the
  cover-the-lens step): the sparkles and what it is doing (Capturing, Stacking) at 22 sp, the
  screen shield as a glowing pill on the right where it is offered. Under it the **step strip**:
  seven circles on a line, the done ones green with a check, the current one lit and numbered
  inside a ring of how far it has got, the later ones dim and numbered; the line green as far as
  the session has got.
- **Pictures** (the cover-the-lens step): the owner's illustrations, their edges faded into
  transparency so they melt into the background, drawn edge to edge.
- **The Adjust sheet** (the owner's design, 5 Nov 2026): Auto or Manual in one selector; the three
  exposure numbers as tiles, a tapped one lit with its stepper under the row; focus and start
  delay as segment strips; Dark frames and Save RAW as switch cards (an icon, a thin divider, the
  name over what it does, the switch); the framing aids folded under More options. Section labels
  in capitals between them. No summary line, no disabled switch, no second way to Settings.
- **Sky label** (the sky view): a ring round a dot, a thin leader line, the name in capitals in a
  glass pill with a lavender edge.
- **Status pill** (hold still): a dot and two words, "Tripod steady"; amber when it is not.
- **A step's clock line** (every capture step): a clock icon, what it is doing, the time against
  its length, and a thin lavender bar filling under the words; a spinner instead where the length
  is unknown.
- **A map with a panel** (the light map, the owner's design, 5 Nov 2026): the map fills the
  screen under a floating top bar; one panel of violet glass with a lit top edge and a handle
  rests at three heights (the name alone, the summary, everything) and follows the finger. The
  summary: a pin, the place's name and way, its Bortle badge, a chip with the class, one line
  comparing it with your sky, Navigate and Plan here, and your own sky as a row that opens. The
  legend rides on the panel's top edge.

## Icons

The owner's set, redrawn as vectors (1.6 stroke on a 24 grid, round ends): the sparkle (Stars),
the orbits (Star trails), the meteor, the sliders on their side (Manual) and standing up
(Adjust), the map with its pin (Light map), the crosshair with a star (Find), the constellation
(sky view), the gear (Settings), the eye (night vision), three sparkles (Tonight), a crescent with
a star (the top row's Tonight), four rings (Quick tools; the right two filled while open). The rest of the app's icons are drawn in the same
stroke. Two-tone where the design has a bright detail.
