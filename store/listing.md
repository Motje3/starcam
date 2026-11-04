# Stelli: the Play Store listing

What goes into Play Console, ready to paste. The graphics in this folder are drawn by
`tools\store-graphics.ps1`. Every claim below is something the app does today; keep it that way
(Play's metadata policy, PLAY_STORE.md).

## Main store listing

**App name** (24 of 30): `Stelli: Night Sky Camera`

**Short description** (77 of 80):

```
Night sky photos from your phone: long exposures, stacked into one clear shot
```

**Full description** (under 4,000):

```
Stelli turns your phone into a night sky camera. Put it on a tripod, tap ASTRO, and Stelli takes a series of long exposures, lines them up and stacks them on the phone into one clean photo of the stars, the Milky Way or star trails.

Stelli needs a phone whose back camera gives apps RAW photos. On other phones it cannot take photos, but the planner, the sky view and the dark-sky map still work.

TAKE THE PHOTO
• Astro: 8, 20 or 40 frames stacked into one photo with less noise and more stars
• Star trails, and a meteor watch (beta), that keep running with the screen off
• Autopilot: leaves out frames spoiled by cloud or a knock, refocuses when the focus drifts, and stops when dawn comes
• Darks: cover the lens for a few frames and Stelli takes out the hot pixels
• Studio: looks, light pollution removal, Milky Way detail and smaller stars, saved as a new photo
• A replay of your photo building up, a timelapse of the night and cut-outs of the meteors you caught
• What's in this photo: Stelli recognises the stars and constellations in your shot and labels them

PLAN THE NIGHT
• Tonight: when it gets dark, where the Moon and the Milky Way are, and what is worth a shot
• Sky view: hold the phone up and see the Milky Way, the stars and the Moon drawn over the camera; Find leads you to a galaxy, a planet or the North Star
• Sky calendar: meteor showers, new and full moons, eclipses, planets at their best and the Milky Way season for where you are, with night alerts if you want them
• Dark-sky map: how dark your sky is (its Bortle class) and where the nearest darker sky is

PRIVATE
Stelli works fully offline, with no accounts, no ads and no tracking. Your photos and your location stay on your phone.

TIPS
Use a tripod, or lean the phone against something that does not move. Get away from street lights, and pick a night without much Moon: the planner shows when.
```

**App icon**: `icon-512.png` · **Feature graphic**: `feature-graphic.png` ·
**Phone screenshots**: `screenshots\*.png` (1080 x 1920, in order), taken on the emulator by
`tools\store-shots.ps1` and framed by `tools\store-graphics.ps1`. Add screenshots of real
results from the phone (a stacked photo, the studio) when you have them: put the phone's
screenshot in `tools\store-graphics.ps1`'s list with a caption and run it again.

## Store settings

- **App or game**: App · **Category**: Photography
- **Tags**: pick from Play's list, e.g. Photography, Camera, Astronomy
- **Email**: info@siteaanpakkers.com, as in the privacy policy · **Website**: optional, the
  GitHub Pages address https://motje3.github.io/starcam/ · **Phone**: optional

## Links

- **Privacy policy**: `docs/legal/privacy.html`, published at
  https://motje3.github.io/starcam/legal/privacy.html once GitHub Pages is on (repository
  Settings › Pages › Deploy from a branch: `main`, `/docs`)
- **Terms of service** (asked for with the trader declaration):
  https://motje3.github.io/starcam/legal/terms.html

## Release notes for the first release

```
The first release of Stelli.
```
