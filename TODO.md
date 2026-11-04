# Stelli: to do before Google Play

Updated 2026-11-04 on 0.5.1 (versionCode 10). Everything that needed no decision is done: the
upload key and the signed AAB, the privacy policy and terms of use (on the web and in the app),
the reworded card for phones without RAW, the release build checked on the emulator, and the store
listing texts and graphics (`store/`). What is left happens on GitHub, in Play Console and on the
phone: the easy part first, at the PC, then the part that needs the phone. PLAY_STORE.md has the
policy check (4 Nov 2026) and the answer to every Play Console form.

Already fine: target SDK 36, arm64 only, 16 KB pages, no internet or advertising-ID permission (so
Data safety can say "no data collected"), coarse location only after "Use my location", inexact
night alerts, the data and font licences credited in Settings.

## Now, at the PC

Easy: every answer is written down. In this order, as each Play Console step needs the one before.

- [ ] **Back up the upload key.** Copy `D:\Code\keys` (the key and the README with its password)
      off this computer: a password manager, USB stick or cloud drive.
- [ ] **Publish the privacy policy and terms.** On GitHub: the repository's Settings › Pages ›
      Deploy from a branch, `main`, `/docs`. They are then at
      https://motje3.github.io/starcam/legal/privacy.html and `.../legal/terms.html` (or put
      `docs/legal` on another site; the app shows its own copy either way).
- [ ] **Trader status.** Play Console › Manage account (Account details) › About you: trader.
- [ ] **Create the app.** Name "Stelli: Night Sky Camera", English (United Kingdom), App, Free (a
      free app can never become paid, but a Pro unlock can be added later). The package,
      `com.wm.stelli`, is fixed for good by the first upload.
- [ ] **Store listing**: the texts in `store/listing.md`, `icon-512.png`, `feature-graphic.png` and
      `screenshots/`; category Photography, email info@siteaanpakkers.com.
- [ ] **App content** (the answers in PLAY_STORE.md): privacy policy (the URL above), ads no, app
      access without restrictions, the content rating questionnaire, target audience 13+, data
      safety: no data collected or shared, advertising ID no; news, government, financial and
      health apps: no. Trader status: confirm it and give the terms link.
- [ ] **Upload the bundle**: `app/build/outputs/bundle/release/app-release.aab` to Internal
      testing, with Play App Signing (Google's key). Then download the APK Play makes from it (App
      bundle explorer › Downloads › signed universal APK) and check it has no INTERNET permission
      (automatic protection, PLAY_STORE.md).

## Later, with the phone

- [ ] **Foreground service declaration** (App content; it appears once the bundle is uploaded):
      the texts in PLAY_STORE.md, and a video of a session carrying on with the screen off,
      recorded on the phone (the steps are there too), as an unlisted YouTube link.
- [ ] **The Play build on the phone.** First copy `Android/media/com.wm.stelli/Stelli` to the PC:
      a Play build can't update the debug build, and uninstalling deletes every session. The old
      StarCam build stays on the phone beside Stelli until it is uninstalled: copy its
      `Android/media/com.wm.starcam/StarCam` first. Then uninstall the debug build, roll out the
      internal release with yourself as tester and install Stelli from Play.
- [ ] **Send it for review.** Promote the internal release to Production and send it for review.

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

## Worth doing

- [x] Try 0.5.0's new features under a real night sky before the upload.
- [x] Reword the card a phone without RAW shows: "No RAW camera", and that the planner, Find and
      the dark-sky map still work.
- [x] "Needs a phone whose back camera gives apps RAW photos" in the store description. (Making
      RAW required in the manifest instead would hide the app from phones that deliver RAW
      without declaring it: `launch and revenue plan.md`, item 3.)
- [ ] Screenshots of real results for the listing (a stacked photo, the studio): phone
      screenshots, added to `tools\store-graphics.ps1`'s list.
- [ ] Tablets and foldables: with target SDK 36, Android 16 ignores the portrait lock on large
      screens. Test on a tablet emulator, or exclude tablets in Play Console (Device catalog).
