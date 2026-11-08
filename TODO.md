# Stelli: to do before Google Play

Updated 2026-11-08 on 0.6.1 (versionCode 12: the owner's new design, and the star alignment the
phone's own sessions showed to be wrong, see PROGRESS.md). Everything that needed no
decision is done: the upload key and the signed AAB, the privacy policy and terms of use (on the
web and in the app), the reworded card for phones without RAW, the release build checked on the
emulator, and the store listing texts and graphics (`store/`). What is left happens on GitHub, in
Play Console and on the phone: the easy part first, at the PC, then the part that needs the
phone. PLAY_STORE.md has the policy check (4 Nov 2026) and the answer to every Play Console form.

Already fine: target SDK 36, arm64 only, 16 KB pages, no internet or advertising-ID permission (so
Data safety can say "no data collected"), coarse location only after "Use my location", inexact
night alerts, the data and font licences credited in Settings.

## Now, at the PC

Easy: every answer is written down. In this order, as each Play Console step needs the one before.

- [ ] **Back up the upload key.** Copy `D:\Code\keys` (the key and the README with its password)
      off this computer: a password manager, USB stick or cloud drive.
- [x] **Publish the privacy policy and terms.** GitHub Pages (Settings › Pages, `main`, `/docs`),
      on since 4 Nov 2026: https://motje3.github.io/starcam/legal/privacy.html and
      `.../legal/terms.html`. The app shows its own copy either way.
- [x] **Trader status.** Declared 4 Nov 2026 (Manage account › Account details › About you).
- [x] **Create the app.** "Stelli: Night Sky Camera", `com.wm.stelli`, Free (a free app can never
      become paid, but it can sell in-app products).
- [ ] **Store listing**: the texts in `store/listing.md`, `icon-512.png`, `feature-graphic.png` and
      the screenshots (to be made from the new screens, "The new look" below); category
      Photography, email info@siteaanpakkers.com.
- [ ] **App content** (the answers in PLAY_STORE.md): privacy policy (the URL above), ads no, app
      access without restrictions, the content rating questionnaire, target audience 13+, data
      safety: no data collected or shared, advertising ID no; news, government, financial and
      health apps: no. Trader status: confirm it and give the terms link.
- [ ] **Upload the bundle**: `app/build/outputs/bundle/release/app-release.aab` (0.6.1,
      versionCode 12; rebuild it first, the one there dates from 5 Nov 02:10, before the last
      design rounds) to Internal testing, with Play App Signing (Google's key). Then download the APK Play makes from it (App
      bundle explorer › Downloads › signed universal APK) and check it has no INTERNET permission
      (automatic protection, PLAY_STORE.md).

## Later: test with friends, then launch with Pro

The plan since 4 Nov 2026, after an outside review: friends test the Play build on their own
phones first, and the public launch comes with **Stelli Pro**, a one-time €9.99 upgrade. Which
features are Pro is decided after the testing.

- [ ] **Foreground service declaration** (App content; it appears once the bundle is uploaded):
      the texts in PLAY_STORE.md, and a video of a session carrying on with the screen off,
      recorded on the phone (the steps are there too), as an unlisted YouTube link.
- [ ] **The Play build on the phone.** First copy `Android/media/com.wm.stelli/Stelli` to the PC:
      a Play build can't update the debug build, and uninstalling deletes every session. The old
      StarCam build (0.5.0) may go: its sessions were copied to
      `field-logs\phone-20261105\backup` on 5 Nov 2026. Then uninstall the debug build, roll out
      the internal release and install Stelli from Play.
- [ ] **Five friends test it.** Internal testing › Testers: an email list with their Google
      accounts, then send them the join link. Before the public launch: finished sessions on 5+
      phones from 3+ brands (a Pixel and a Samsung among them), the screen-off capture and the
      phone's heat checked, no lost photos. Ask each how the photo turned out.
- [ ] **Side by side.** The same sky with Stelli and the OnePlus 15's own night mode: if Stelli is
      not clearly better, better to know now; if it is, the pair belongs in the listing.
- [ ] **Merchant account**, to sell Pro (Play Console asks for it with the first in-app product).
- [ ] **Stelli Pro** (code): the one-time purchase through Play Billing, restored on a new phone;
      promo codes give the testers Pro. The billing library brings the internet permission, so
      the privacy policy, Data safety and the app's "no internet access" lines change with it
      (PLAY_STORE.md). With it: "How did your photo turn out?" after a session, with a report to
      send, and "estimated" before the Bortle class in the app.
- [ ] **Public launch**: Production with Pro, aimed at mid-December (the Geminids peak on 13–14
      December) if the testing passes, and sent for review.

## Done

- [x] **Upload key.** Created 4 Nov 2026 in `D:\Code\keys` (outside the repository), with its
      password in the README beside it and in `keystore.properties` (git-ignored).
- [x] **Privacy policy and terms of use.** `docs/legal`, shown in the app under Settings ›
      Advanced from the same files. Contact: info@siteaanpakkers.com (chosen 4 Nov 2026:
      `wm-websolutions.nl` is not registered, so info@wm-websolutions.nl receives nothing). Give
      the Play listing the same email.
- [x] **Check the release build.** `tools\emulator-test.ps1 -Release`, see PROGRESS.md.
- [x] **Play Console account.** Organisation W&M Websolutions with its D-U-N-S number (in the
      local `.env`, gitignored, kept out of this public repo). An organisation account skips the
      12 testers / 14 days closed test.

## The new look

The owner had an image AI redesign the main screen (4-5 Nov 2026) and asked for the whole app to
match it. Built in 0.6.0; `design/STYLE.md` is the style, PROGRESS.md says what changed.
- [x] Build it in Compose, every screen, the red night-vision variant included (5 Nov 2026).
- [ ] The owner looks at the new screens and has the AI improve whatever they do not like; built
      the same way. Every screen and setting of 0.6.1 is in Desktop › Stelli screens › all screens
      5 Nov (open `all screens.html`); `tools\screen-tour.ps1` takes them again. Built so far
      (5 Nov 2026): the cover-the-lens step with the owner's picture, the session's header and
      step circles, the Adjust sheet, the hold-still step, the light map with its panel; then
      every other screen made simple first, more behind a tap (settings, the photo page, the
      result, Tonight, the intro); then the capture screen's Quick tools, tonight behind a moon
      on the top row and the session-length pill; then, from the store pictures (8 Nov), the
      calendar's next big night, Night alerts on its top bar and the slider in Edit.
- [x] On the phone: 0.6.1's debug build installed over wireless adb on 5 Nov 2026 (a new app
      beside the old StarCam; this PC's debug key, so `tools\build.ps1` updates it, sessions kept).
- [ ] Under a real sky.
- [ ] Store screenshots: eight from the owner's image AI (Desktop › Stelli screens › final, 8 Nov
      2026), being remade at 1080 × 1920. Before the public listing: real Stelli photos in 1 (the
      Milky Way), 2 (1 photo vs 40, the same scene on both sides) and 5 (star trails), as
      PLAY_STORE.md requires. One clear, dark night gives all three (new moon 9 Nov, 9 Dec; from
      here the bright core is only up in daylight until spring). Also: 7 shows the Geminids (the
      app says Mon, Dec 14) rather than a crescent on a new moon; 4 a darker spot than "may be
      faint"; 1 the photo full-screen, spilling out of the phone; 6 a session running; 8 What's in
      this photo. The ones in `store/screenshots` still show the old look.

## From the phone's own sessions (5 Nov 2026)

What the sessions of 31 Oct showed (PROGRESS.md, 0.6.1).
- [x] The star alignment: a homography, fitted to every star, the sky over the lights below it
      (stacks sharp to the edges; the Deep session's "cloud" was clear sky).
- [ ] A Deep session under a clear sky with 0.6.1: no waiting for cloud that is not there.
- [ ] A faster star focus: 65 s before the first frame, 10 steps each waiting 4.1 s for a 0.2 s
      "settle" frame.
- [ ] Lights below the sky can hide cloud from the autopilot: under cloud the alignment may take
      them for the sky. And when the phone moves, they blur in a stack aligned on the stars
      (they are nearer); the foreground blend is off by default.
- [ ] The darks: the Quick session waited a minute for the lens to be covered, then went on
      without.

## Worth doing

- [x] Try 0.5.0's new features under a real night sky before the upload.
- [x] Reword the card a phone without RAW shows: "No RAW camera", and that the planner, Find and
      the dark-sky map still work.
- [x] "Needs a phone whose back camera gives apps RAW photos" in the store description. (Making
      RAW required in the manifest instead would hide the app from phones that deliver RAW
      without declaring it: `launch and revenue plan.md`, item 3.)
- [ ] Screenshots of real results for the listing (a stacked photo, 1 frame vs stacked, What's in
      this photo): phone screenshots, first in `tools\store-screens.ps1`'s list. Also: the sky
      calendar with its Meteors filter (the Geminids) for the meteor screenshot.
- [ ] **Feature graphic** from the owner's artwork (asked of an image AI, 4 Nov 2026), with the
      name and tagline on top; the current one still has the old star.
- [ ] Tablets and foldables: with target SDK 36, Android 16 ignores the portrait lock on large
      screens. Test on a tablet emulator, or exclude tablets in Play Console (Device catalog).
