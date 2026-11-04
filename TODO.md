# Stelli: to do before Google Play

Checked 2026-10-29 on 0.5.0 (versionCode 9). `gradlew bundleRelease` builds a 12 MB AAB, but it
is unsigned. Updated 2026-11-03: the Play Console account is verified, the night-sky shoot is
done, and the app is renamed **Stelli** in 0.5.1 (app id `com.wm.stelli`, store name "Stelli:
Night Sky Camera"; PROGRESS.md has the details). The rest below is still open. PLAY_STORE.md
has the policy check (4 Nov 2026) and the answer to every Play Console form.

Already fine: target SDK 36, arm64 only, 16 KB pages, no internet or advertising-ID permission (so
Data safety can say "no data collected"), coarse location only when the planner asks, inexact night
alerts, the data and font licences credited in Settings, a clear message on phones without RAW.

## Must do

- [ ] **Upload key.** Create it once with the keytool command in DEBUGGING.md ("Release builds"),
      copy `keystore.properties.example` to `keystore.properties`, and back up the `.jks` and its
      passwords outside the repo (a lost upload key means asking Google for a reset). Then build
      the signed AAB with `gradlew bundleRelease`.
- [ ] **Privacy policy.** Play wants one in Play Console and, because the app uses the camera and
      location, a link inside the app too. Write it (what the app reads, that it has no internet
      permission, whether location goes into the photos), publish it on a public page (e.g. the
      W&M Websolutions site), add the link to Settings (`ui/SettingsScreen.kt`) and enter the URL
      in Play Console. PLAY_STORE.md lists what it has to cover.
- [ ] **Trader status and terms of service.** Declare W&M Websolutions a trader (Manage account ›
      Account details › About you, then per app under App content). A trader's apps each need a
      terms of service link: publish a terms page next to the privacy policy.
- [ ] **Check the release build.** R8 minifies it and can break things the debug build doesn't
      show; the last release build checked was 0.3.3. `tools\emulator-test.ps1` drives debug
      builds only (release builds ignore its session hook), so go through the 0.4 and 0.5 screens
      by hand on the emulator: a session, studio, replay video, planner, sky view, calendar,
      autopilot, timelapse, meteors.
- [x] **Play Console account.** Sign up as an organisation for W&M Websolutions with its D-U-N-S
      number (in the local `.env`, gitignored, kept out of this public repo), with the name and address as D&B and the KVK have
      them, and pay the USD 25 fee. The company name, address, email and phone become public on
      the listing. An organisation account skips the 12 testers / 14 days closed test.
- [ ] **Create the app.** Name "Stelli: Night Sky Camera", package `com.wm.stelli` (the first
      upload fixes it for good), English (United Kingdom), App, Free.
- [ ] **Play Console forms.**
  - [ ] Store listing: 512 x 512 icon, 1024 x 500 feature graphic, at least 2 screenshots, a short
        description (up to 80 characters) and a full one, category Photography.
  - [ ] Price: Free. A free app can never become paid, but a Pro unlock can be added later.
  - [ ] Content rating questionnaire and target audience (13+ or 18+).
  - [ ] Data safety: no data collected or shared.
  - [ ] Foreground service declaration for the camera service (`foregroundServiceType="camera"`),
        with a link to a short video of a session carrying on with the screen off.
  - [ ] The rest of App content: ads no; app access: everything works without a login; news,
        government, financial and health apps: no.

## Worth doing

- [x] Try 0.5.0's new features under a real night sky before the upload.
- [ ] Reword the "Camera problem … Stelli cannot run" card a phone without RAW shows: say what
      the camera lacks and that the planner, sky view, calendar and map still work. A reviewer's
      phone may be such a phone.
- [ ] Put "needs a phone with a RAW camera" in the store description: RAW is optional in the
      manifest, so Play offers the app to any phone with a camera. Or make it required
      (`launch and revenue plan.md`, item 3); the OnePlus 15 declares the RAW feature.
- [ ] Tablets and foldables: with target SDK 36, Android 16 ignores the portrait lock on large
      screens. Test on a tablet emulator, or exclude tablets in Play Console.
- [ ] Before putting a Play build on the phone (internal testing first), copy
      `Android/media/com.wm.stelli/Stelli` to the PC: a Play build can't update the debug
      build, and uninstalling deletes every session. The old StarCam build stays on the phone
      beside Stelli until it is uninstalled: copy its `Android/media/com.wm.starcam/StarCam`
      first.
