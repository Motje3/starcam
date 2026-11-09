# Stelli on Google Play: the policies and the form answers

Checked on 4 November 2026 against Google Play's policy pages (last policy update: 29 October
2026) and the built 0.5.1 APK; the 0.6.0 build (5 Nov, the new look) has the same permissions. A
reading of the policies, not legal advice: when Play Console asks something this file does not
cover, read the page it links to.

## Advance notice to App Review: not needed

Google takes advance notice only for written permissions (another party's brand or other
intellectual property, a government, a healthcare organisation), gambling in Korea, accessibility
services, non-profits, police and regulators. None applies: the bundled data is public domain or
CC0, its licences and the fonts' are credited in Settings, and the listing uses no other brand's
name or logo.

## Already fine

- **Target API 36**, which new apps need since 31 August 2026; 16 KB pages; arm64.
- **Permissions** (read from the built APK): camera, coarse location, camera foreground service,
  wake lock, notifications, boot, vibrate. No internet, no advertising ID, no permission to read
  photos or videos, no exact alarms, no background location, no accessibility service.
- **Asked in context.** The camera is asked for after the intro says why. Location only when
  "Use my location" is tapped (in the planner, the sky calendar, the light map or the sky
  view), under a line saying what it is for and that it stays on the phone; "Enter coordinates"
  works if it is refused.
- **No location in photos** (no GPS in EXIF). The planner's place is kept on the phone, for the
  planner and the night alerts, and nowhere else.
- **No accounts, ads, purchases or third-party SDKs.** Nothing leaves the phone unless the user
  shares it.
- **The name**: 24 characters, no emoji, no ranking or price words; no app of that name found.

## To do before the first submission

1. **Trader status (EU and UK).** New accounts must declare it straight away: Play Console ›
   Manage account (Account details) › About you, then confirm it per app under App content.
   W&M Websolutions is a business, so it is a trader: Play asks for a **terms of service link**
   for each app ("You will also be asked to provide the link to the terms of service for each of
   your apps", Trader status declaration, read 4 Nov 2026), and from 1 March 2027 the listing
   shows the contact details, the D-U-N-S number and that link. Deadline for all accounts:
   1 February 2027; new accounts at once.
2. **Privacy policy**: written, `docs/legal/privacy.html` (public HTML, titled as a privacy
   policy, naming W&M Websolutions and Stelli), and shown in the app under Settings › Advanced
   from the same file; contact info@siteaanpakkers.com. Still to do: publishing it (GitHub Pages,
   TODO.md) and its URL in Play Console (App content › Privacy policy).
3. **Terms of service**: written, `docs/legal/terms.html`, in the app beside the privacy policy;
   published with it.
4. **Foreground service declaration** with a video (text below). The video needs the phone.
5. **Store listing**: `store/listing.md` and the graphics beside it.
6. The other **App content** forms (answers below).
7. **The release build**: checked on the emulator with `tools\emulator-test.ps1 -Release`.

## Review risks, and what to do about them

- **A reviewer's phone without RAW** shows the "No RAW camera" card: that Stelli makes its
  photos from RAW frames and this phone gives apps none, and that Tonight, Find and the
  light map at the top still work (they do). The description says in its second paragraph
  that the app needs such a phone.
- **Screenshots**: the long side may be at most twice the short one. The phone's 1272 × 2800
  screenshots are 2.2 : 1 and are refused as they are: `tools\store-screens.ps1` places them on
  a 1080 × 1920 canvas (9:16, which Play recommends anyway) under an icon and a headline.
- **Photos in the listing** must be real Stelli results, like the shoot's; no stock astro photos.
- **Wording**: no names of other apps (Siril, PixInsight…), no device or brand logos, no "best",
  "#1", "free" or "new" in the title, icon or feature graphic, no comparisons with other apps, no
  unattributed testimonials.
- **Automatic protection** (on by default when the app is created) adds an installer check to the
  code; Google says it works offline. After the first upload, download the APK Play makes (App
  bundle explorer › Downloads › signed universal APK) and run `aapt2 dump permissions` on it: the
  app and the privacy policy promise no internet access, so INTERNET must not be in it. If it is,
  turn automatic protection off.
- **A grown-up listing.** No cartoons or mascots in the icon and graphics, so that Play does not
  judge the app to be aimed at children; the Families rules would then apply (a children's app may
  not ask for location).
- **Later, not now:** from 27 January 2027 apps that target Android 17 (API 37) must use Android's
  location button for one-off location requests.
- **A paid upgrade brings the internet permission.** Play Billing Library 9.1.0 depends on
  Google's datatransport libraries, and `transport-backend-cct` 3.1.8 declares INTERNET and
  ACCESS_NETWORK_STATE (read from the libraries on Google's Maven, 4 Nov 2026). So the listing
  says "works fully offline", which stays true, rather than "no internet permission". When an
  in-app purchase comes, the privacy policy, the Data safety answers and the app's own "Stelli has
  no internet access" lines (Tonight, the sky calendar, the light map) change with it.

## Form answers

### App content

The Dashboard's "Set up your app" list opens each of these forms; the page itself is under Monitor
and improve › Policy and programs › App content. All answered on 9 Nov 2026.

| Form | Answer |
|---|---|
| Privacy policy | the page's URL |
| Ads | No, the app does not contain ads |
| App access (the Dashboard's "Sign-in details") | All functionality is available without any access restrictions |
| Content rating | see below |
| Target audience | 13–15, 16–17 and 18+ (not under 13); appeals to children: No |
| Data safety | see below |
| Government apps | No |
| Financial features | None |
| Health | None |
| News app | No |
| Advertising ID | No, the app does not use an advertising ID |
| Foreground service | Camera, see below |

### Data safety

- Does the app collect or share any of the required user data types? **No.** Stelli has no
  internet permission: the photos, the place and the settings stay on the phone, and data used
  only on the phone is not "collected" in Play's sense. What a user passes on with the share sheet
  (a photo, a video, a debug report) is their own action, carried out by the app they pick.
- The questions about encryption in transit and deletion requests fall away with that answer.

### Content rating (IARC questionnaire)

Category: the one for all other apps (not a game). Violence, sexuality, language, drugs, gambling:
none. Users can interact or exchange content: No. Shares the user's location with other users:
No. Digital purchases: No. Unrestricted internet access: No. Also asked, all No: online content;
cash rewards, gift cards, crypto or NFTs; primarily a news or educational product. Result (9 Nov
2026): ESRB Everyone, PEGI 3, USK 0, ClassInd L, IARC 3+. Digital purchases changes with Pro.

### Foreground service: Camera

- **Use case**: "Background camera streaming" is the only one listed for the camera type; if it
  does not fit, "Other" with the text below.
- **What the feature does**: "Stelli photographs the night sky with long exposures. A session is a
  series of exposures of many seconds each, from a few minutes up to an hour for star trails or a
  meteor watch, which the phone aligns and stacks into one photo. The user starts it with the ASTRO
  button. It keeps running with the screen off and the phone locked, because a lit screen spoils
  the photo and drains the battery. A notification shows its progress, with Finish and Cancel, and
  it stops when the last frame is taken or the user stops it."
- **If it is deferred or interrupted**: "The series of exposures breaks: frames are lost or no
  longer line up, and the night's photo cannot be stacked. The user has to start again, and the
  sky may no longer allow it (cloud, moonrise, dawn)."
- **Video** (an unlisted YouTube link): the phone on a tripod, Stelli open → tap ASTRO → it starts
  shooting → press the power button → wait a minute → unlock: the frame count has gone on → pull
  down the notification (Finish, Cancel) → tap Finish → the stacked photo.

### Store listing

The texts to paste and the graphics are in `store/` (`listing.md`). The specs they meet:

- **App name**: "Stelli: Night Sky Camera", 24 of 30 characters. (The create form counted 25:
  check for a stray space.)
- **Short description** up to 80 characters; **full description** up to 4,000.
- **Icon**: 512 × 512, 32-bit PNG, up to 1 MB.
- **Feature graphic**: 1024 × 500, JPEG or 24-bit PNG (no transparency).
- **Phone screenshots**: 2 to 8, JPEG or 24-bit PNG, each side 320 to 3,840 px, the long side at
  most twice the short one; 1080 × 1920 recommended.
- **Tablet screenshots**: optional (Google's page does not require them, and "Tablet assets" has no
  star on its heading, read 9 Nov 2026), left empty.
- **Category** Photography; a contact email is required. Tags: Camera and Photography (Play has no
  Astronomy tag).
- **AI asset declaration** (the review step of the listing): "Label assets as created or edited
  using AI" for whatever an image AI made or edited, the eight screenshots among them. Each image
  is declared on its own, with no exception for mock-ups or stylised art; the page states no effect
  on ranking and no penalty (https://support.google.com/googleplay/android-developer/answer/17262077,
  read 9 Nov 2026). Pictures made from real photos and screens without an image AI need no label.
  The label does not lift the rule against misleading screenshots.

### The privacy policy: what it covers

Written in `docs/legal/privacy.html` against this list (the address is left out, as on the W&M
site, where it is still to be filled in; the KVK number is in):

- **Who**: W&M Websolutions (the name on the Play listing), its address, and an email address
  for privacy questions.
- **What the app uses, and why**:
  - the camera, to take the photos;
  - approximate location, only after "Use my location" in the planner: to work out the darkness,
    the Moon, the Milky Way, the night alerts and the dark-sky map for that place. It is kept on
    the phone, never written into photos, and coordinates typed in work instead;
  - the motion sensors, to wait until the phone stands still;
  - notifications, for a running session and the night alerts.
- **What leaves the phone**: nothing by itself. No internet permission, no accounts, ads,
  analytics or third-party code that collects data.
- **Sharing**: only what the user chooses to share (photos, videos, a debug report), with the
  app they pick. A debug report sent to W&M holds the app's logs and recent sessions' details (no
  photos); it is used only to fix the problem and deleted afterwards (say within how long).
- **Keeping and deleting**: photos and sessions stay on the phone until the user deletes them.
  Uninstalling removes the app's own folder and settings, not the photos saved to the gallery.
- **Children**: not aimed at children under 13.
- **Security**: everything stays in the phone's storage, under Android's own protection.
- **Changes**: the date of the current version; changes are published on the same page.

## Sources

- Policy centre: https://play.google/developer-content-policy/ (update of 29 October 2026:
  https://support.google.com/googleplay/android-developer/answer/18100766)
- Metadata (title, icon, screenshots, description):
  https://support.google.com/googleplay/android-developer/answer/9898842
- User data (privacy policy, prominent disclosure):
  https://support.google.com/googleplay/android-developer/answer/9888076
- Permissions and location: https://support.google.com/googleplay/android-developer/answer/12579724
- Foreground services: https://support.google.com/googleplay/android-developer/answer/13392821
- Families: https://support.google.com/googleplay/android-developer/answer/9893335
- Impersonation and intellectual property:
  https://support.google.com/googleplay/android-developer/answer/9888374,
  https://support.google.com/googleplay/android-developer/answer/9888072
- Trader status: https://support.google.com/googleplay/android-developer/answer/17594752
- Target API level: https://developer.android.com/google/play/requirements/target-sdk
- Automatic protection: https://support.google.com/googleplay/android-developer/answer/10183279
