# StarCam: to do before Google Play

Checked 2026-10-29 on 0.5.0 (versionCode 9). `gradlew bundleRelease` builds a 12 MB AAB, but it
is unsigned and none of the items below is done yet.

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
      in Play Console.
- [ ] **Check the release build.** R8 minifies it and can break things the debug build doesn't
      show; the last release build checked was 0.3.3. `tools\emulator-test.ps1` drives debug
      builds only (release builds ignore its session hook), so go through the 0.4 and 0.5 screens
      by hand on the emulator: a session, studio, replay video, planner, sky view, calendar,
      autopilot, timelapse, meteors.
- [ ] **Play Console account.** Sign up as an organisation for W&M Websolutions with its D-U-N-S
      number (in the local `.env`, gitignored, kept out of this public repo), with the name and address as D&B and the KVK have
      them, and pay the USD 25 fee. The company name, address, email and phone become public on
      the listing. An organisation account skips the 12 testers / 14 days closed test.
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

- [ ] Try 0.5.0's new features under a real night sky before the upload.
- [ ] Put "needs a phone with a RAW camera" in the store description: RAW is optional in the
      manifest, so Play offers the app to any phone with a camera. Or make it required
      (`launch and revenue plan.md`, item 3); the OnePlus 15 declares the RAW feature.
- [ ] Tablets and foldables: with target SDK 36, Android 16 ignores the portrait lock on large
      screens. Test on a tablet emulator, or exclude tablets in Play Console.
- [ ] Before putting a Play build on the phone (internal testing first), copy
      `Android/media/com.wm.starcam/StarCam` to the PC: a Play build can't update the debug
      build, and uninstalling deletes every session.
