# StarCam future ideas

Product ideas for a paid Google Play release. Prepared on 21 October 2026.

The central product promise: **help someone find a worthwhile sky shot, capture it successfully, and finish the photograph on their phone.** These ideas extend the existing capture, focusing and stacking foundations. They are proposed scope, not claims that the features are already implemented.

## Priorities at a glance

| Feature | Main reason to pay | Relative effort | Suggested stage |
| --- | --- | --- | --- |
| Adaptive Astro Autopilot | Fewer failed sessions and less guesswork | Medium–high | First paid release |
| Astro editing studio | Finished results and reusable edits without a laptop | Medium–high | First paid release |
| Find and frame targets | Know what to aim at and verify the composition | High | Next major feature |
| Photo and video from one session | Several useful, shareable outputs from the same night | Medium–high | After capture cadence is dependable |
| Meteor highlights | Find worthwhile events without reviewing hundreds of frames | Medium–high | After meteor capture/detection is dependable |
| Guided Milky Way panoramas | A dramatic wide composition beyond a single frame | Very high | Later flagship release |

Effort is a relative planning estimate, not a development schedule. The first release should deliver a complete, dependable workflow with a small number of features.

## 1. Adaptive Astro Autopilot

**The idea:** extend automatic capture into a session that responds to the images it actually receives. Instead of choosing settings once, monitor focus, star elongation, sky brightness, clipped highlights and usable integration throughout capture.

**Why it is good:** beginners often cannot tell whether a session is working until it ends. Experienced photographers also lose time to changing conditions. Explaining what is happening and preventing avoidable failures makes the app useful every clear night.

An initial version could:

- Validate exposure and focus with a test image before the main burst.
- Distinguish elapsed session time from accepted exposure time.
- Detect a sustained deterioration in sharpness or star visibility and suggest a response.
- Pause or flag interruptions, then resume when measurements recover.
- Let users choose a time budget and finish with the verified frames already captured.

Later, estimate diminishing returns and suggest when additional frames are unlikely to add much useful improvement. Present that as an estimate based on observed data.

**Important boundaries:** star counts alone do not prove clouds are present, and a change in brightness does not always require a setting change. Begin with conservative recommendations and bounded adjustments. Large exposure changes need appropriate normalization/calibration when combining frames.

**How to validate it:** compare automatic sessions against fixed settings on the same supported devices. Measure usable integration, star sharpness, failed sessions and how often recommendations are correct.

**Paid role:** a strong headline Pro feature because it sells confidence and successful capture, building on the engine rather than requiring an entirely separate product.

## 2. Astro editing studio

**The idea:** turn the preserved linear master into a reusable project. Users can finish or revisit a photograph without repeating the capture or re-running alignment for every adjustment.

**Why it is good:** a technically successful stack can still look flat or have an uneven sky. Bringing the finishing stage into StarCam closes the gap between collecting data and getting a photograph someone wants to keep.

An initial version could offer:

- Sky brightness/stretch, black point and natural color controls.
- Gradient correction with sky/foreground protection.
- Separate control of foreground brightness, star highlights and noise reduction.
- Immediate previews, before/after comparison and saved edit versions.
- Full-resolution export with the original master preserved.

Start with a few understandable controls and useful defaults. More advanced masks and selective adjustments can follow after the core pipeline is predictable.

**Important boundaries:** editing cannot restore data that was clipped or never recorded. Preserve the master, keep processing recipes reproducible, and protect small-star color. Fast previews should agree closely with the final export.

**How to validate it:** have testers finish their own captures and compare exported results with the originals. Check star color, noise, sky gradients, preview/export consistency and whether they still need another editor.

**Paid role:** likely the clearest initial upgrade alongside Autopilot. It also gives users a reason to return to the app between capture nights.

## 3. Find and frame celestial targets

**The idea:** users select a suitable target or composition, take a short test capture, and receive guidance on where to move the phone. Identify the actual star field to verify framing; this is called plate solving.

**Why it is good:** a dark viewfinder makes aiming difficult, and many beginners do not know what is visible from their location. Connecting target selection directly to the camera makes planning actionable.

Start with a small catalog of suitable targets, such as Orion and Milky Way regions, plus a limited set of tested camera fields of view. Provide approximate pointing guidance first, then refine it when a captured star field can be identified. Distinguish verified framing from an approximate estimate.

Useful additions include remembering a previous composition and returning to it another night. Core target calculations and downloaded catalogs should work offline.

**Important boundaries:** this is substantial new engine work. Sparse stars, clouds and unsuitable fields of view can prevent a solve. Compass pointing should not be presented as precise verification. Target recommendations must reflect the phone's capabilities and observing conditions.

**How to validate it:** measure solve success, time to correct framing and false identifications across supported lenses and real sky conditions. Failed solves should still leave a useful capture workflow.

**Paid role:** the strongest candidate for a later standout feature, especially when paired with Autopilot.

## 4. Photo and video from one session

**The idea:** a capture session can produce a finished photograph plus an optional timelapse, animated star trails or a short video showing the stack improving.

**Why it is good:** users get more value from the time spent outside. The moving results are easy to demonstrate in a store trailer and share, which can help people understand the app before buying.

Begin with a short stack-progress video and a modest-resolution timelapse from retained session data. Add animated trail rendering and higher-quality video after consistent exposure, color and pacing are established.

Give users control over duration and output orientation, and let them preview before exporting. Video generation can run after acquisition to avoid reducing exposure duty cycle.

**Important boundaries:** processing gaps become obvious in video, and exposure/color changes cause flicker. RAW storage, decoding, battery use and video encoding need a bounded budget. Decide which intermediate data must be retained before promising later re-export.

**How to validate it:** inspect smoothness, flicker, star motion, export speed and storage use on supported phones. Check whether users actually save/share the secondary output.

**Paid role:** an attractive Pro extra with a clear visual demonstration. Treat it as added value rather than the sole differentiator.

## 5. Meteor highlights

**The idea:** after a long meteor session, show a short list of candidate events with original crops and timestamps. Users confirm the events they want, then export a combined photograph, contact sheet or highlight video.

**Why it is good:** reviewing hundreds of exposures is tedious. Useful curation saves time even before detection becomes sophisticated, particularly after a meteor-shower night.

An initial version should prioritize reviewable candidates and preserved originals. Include keep/remove controls and explain which source exposure contributed to an exported result. Compose confirmed events using narrow masks so unrelated stars and noise are not reintroduced.

**Important boundaries:** call detections candidates. Aircraft, satellites, hot pixels and other artifacts can resemble meteors. Continuous capture coverage and safe long-session storage matter as much as detection quality.

**How to validate it:** use labelled real sessions to measure both missed events and false candidates. Include nights with aircraft and satellites, and verify that users can find their worthwhile exposures faster.

**Paid role:** a strong specialist feature once the basic mode is dependable. Its appeal is more seasonal than Autopilot or editing.

## 6. Guided Milky Way panoramas

**The idea:** guide users through overlapping panels, verify coverage and combine the panels into a wide sky panorama.

**Why it is good:** the result is visibly different from a single-frame photograph. A successful wide nightscape is a strong demonstration of why someone would choose a dedicated astrophotography app.

Keep the first version narrow: two or three panels, one lens, a tripod and simple scenes. Guide overlap, lock suitable capture settings, check panel quality before moving on, and allow a failed panel to be recaptured.

A later version could handle a separate foreground pass and more complex compositions.

**Important boundaries:** stars move between panels while the landscape stays fixed. Sky and ground require different alignment/blending, and trees or moving objects are difficult. Exposure consistency, distortion correction, memory use and temporary storage make this the most ambitious idea here.

**How to validate it:** inspect seams, duplicate stars, horizon continuity, color/brightness consistency and the success rate of guided capture. Start with controlled scenes before expanding supported compositions.

**Paid role:** a later flagship feature or major Pro upgrade after the core workflow has earned trust.

## Release strategy

1. **Establish the foundation:** resolve and recheck the relevant reliability/image-quality findings in [gpt review.md](gpt%20review.md). Confirm supported devices, durable saving, background ownership and repeatable processing.
2. **First paid product:** Adaptive Autopilot plus the editing studio. Start with a focused version of each that works together from capture to export.
3. **Next major feature:** Find and frame targets, beginning with a small supported catalog and tested lenses.
4. **Broaden the outputs:** add photo/video packages and meteor curation as the capture and storage pipelines support them.
5. **Later flagship:** guided panoramas after single-field capture and editing are consistently good.

Validate priorities with people photographing their own skies on their own phones. Track successful finished photographs, repeat usage and paid-upgrade conversion; feature count alone is a poor success measure.

## Suggested paid model

A starting experiment is a free compatibility check and genuinely usable basic capture, with a **one-time Pro unlock around EUR 14.99**. This is a pricing hypothesis to test, not established willingness to pay.

Pro could include adaptive capture, advanced finishing, saved edit versions and additional output types as they become available. Clearly describe what is included at launch. Keep reliable saving, recovery and access to already captured images part of the core experience.

Google Play supports permanent premium upgrades through [non-consumable one-time products](https://developer.android.com/google/play/billing/one-time-products). Consider recurring payment only if a later service delivers continuing value and carries continuing costs.

## Market context

Current listings show that [DeepSkyCamera Pro](https://play.google.com/store/apps/details?id=de.seebi.deepskycamera.pro) already offers live stacking, trails and video outputs, while [PhotoPills](https://play.google.com/store/apps/details?id=com.photopills.android.photopills) offers extensive sky-planning tools. These sources were consulted during the feature discussion on 21 October 2026.

The proposed opportunity is the quality of the connected workflow: guidance, adaptive capture, dependable preservation and finished results on the phone. The ideas above are product hypotheses to validate, not claims of market exclusivity.

## Further research: creative expansion and Google Play growth

Research date: 21 October 2026. This section extends the six original ideas above. Product proposals, pricing choices and growth priorities are hypotheses to test. Competitor descriptions below are advertised capabilities, not independent performance tests; individual reviews are qualitative clues rather than evidence of how frequently a problem occurs.

### What the research changes

More processing modes alone are unlikely to create a durable advantage. The reviewed products already cover considerable ground:

| Product/source | What it establishes | Implication for StarCam |
| --- | --- | --- |
| [DeepSkyCamera Pro](https://play.google.com/store/apps/details?id=de.seebi.deepskycamera.pro) | RAW/JPEG live stacking, trails and video; its description expects astrophotography knowledge and external finishing. | A dependable path from beginner capture to finished photo is a more focused proposition than another manual camera. |
| [AstroCam](https://play.google.com/store/apps/details?id=com.amnlapps.astrocam) | Advertises hardware discovery, stacking, trails, timelapse, lucky imaging and drizzle. | These capabilities should not be marketed as exclusive to StarCam. |
| [Eagle Image Stacker](https://play.google.com/store/apps/details?id=com.tssystems.librawtest) and [AstroShader](https://www.astroshader.com/home) | Advertise imported-image stacking/processing; AstroShader is an iPhone product. | Importing existing photos is an established workflow. Android execution, clarity and reliability would need to distinguish StarCam. |
| [OpenLiveStacker](https://play.google.com/store/apps/details?id=org.openlivestacker) | Supports internal/external cameras, calibration and plate solving. | Telescope/USB support is a substantial competitive category, not an easy empty market. |
| [PhotoPills](https://play.google.com/store/apps/details?id=com.photopills.android.photopills) and [PlanIt](https://www.planitphoto.com/pro) | Established sky/landscape planning tools. | Prefer a small amount of guidance tied to the actual capture over recreating a full planetarium/planner. |
| [Pixel Camera help](https://support.google.com/pixelcamera/answer/9708795?hl=en) | Documents built-in astrophotography and automatic timelapse. | Users with a capable stock camera need a specific additional benefit. |
| [ProShot Evaluator](https://play.google.com/store/apps/details?id=com.riseupgames.proshotevaluator) | Already provides a free camera capability report. | A hardware report is useful; a successfully captured, processed and saved test image is stronger evidence. |

Visible reviews on the AstroCam listing describe ads and device/exposure problems; reviews on Eagle describe stacking/import frustrations; ProShot Evaluator reviewers ask to test actual saved output. Some of these reports concern older versions. Treat them as prompts for interviews and device tests, not proof that current competitors universally fail.

**Strategic hypothesis:** become the app people recommend because it produces understandable, reproducible results on their phone, helps them improve over time, and makes those results easy to share.

### Stronger later ideas: free and paid roles

These are ranked proposals. Free features should deliver a complete useful outcome; payment should unlock additional creative capability, scale or saved effort.

| Idea | Useful free version | Plausible Pro value | Effort |
| --- | --- | --- | --- |
| Build a photograph across several nights | Project journal, per-night images and a limited combined-project trial | Multiple projects, calibrated accumulation, overlap guidance and comparisons | High |
| Personal phone/lens lab and realistic target catalog | Actual test capture, verified capabilities and suitable starter targets | Personal calibration tools, multiple rig profiles and advanced plans | Medium–high |
| A sky atlas made from the user's photographs | Common identifications, personal collection and clean photo exports | Rich annotations, printable fieldbooks and project-history views | High; reuses future plate solving |
| Import and rescue existing captures | File compatibility check, basic finishing and preserved originals | Supported batch alignment/calibration and large projects | High |
| Second-phone field controller | Ordinary remote shutter support and basic status where supported | Live project monitoring, full armed-session control and alerts | Medium–high |
| Reusable capture procedures and share cards | Starter procedures, recipe import and optional result cards | Custom multi-stage capture/processing sequences | Medium |
| Telescope adapter coach and Moon studio | Adapter alignment help and ordinary capture | Video frame selection, lunar processing and rig presets | High–very high |
| Guided group observing | Join a session, follow a recipe and share chosen results | Host displays and organizer tools if demand appears | Medium initially; high with online services |

#### 7. Build a photograph over several nights

**The experience:** “I have ten clear minutes tonight; add them to the Orion photograph I started last week.”

A persistent project stores original exposures, per-night calibration, framing, accepted integration and processing history. Returning users recover the composition, capture more data and compare the actual improvement across visits. This creates progress with lasting personal value.

Start with two nights, one phone, one physical lens and a suitable target. Handle overlapping sky coverage explicitly and preserve each session independently. Avoid starting with mixed cameras or merging foregrounds from unrelated nights.

The technical model is established: [Siril documents calibrating sessions independently and registering their preprocessed frames together](https://siril.org/faq/#how-do-i-process-several-sessions). Combining rendered night images indiscriminately loses important information.

**Test:** do users return to improve an existing project, and does the combined result improve measurable noise/quality without introducing artifacts? Show accepted exposure and measured progress; avoid invented “detail percentages.”

#### 8. Personal phone/lens lab, linked to achievable targets

**The experience:** “Show me what this particular phone can actually photograph, and which lens works best.”

Go beyond listing Camera2 flags. Perform a small capture/stack/save test, validate requested versus actual settings, and build a personal profile for useful exposure, focus behavior, noise and resolution choices. Connect that evidence to realistic target size and difficulty.

A beginner could see suitable choices for their balcony or campsite instead of receiving the same spectacular nebula promises as every other user. Authentic examples from matching devices would help set expectations.

Keep compatibility evidence free. Advanced personal calibration and multiple phone/telescope configurations could support Pro. Record device, lens and software identity so profiles can be rechecked after updates.

**Constraint:** share measured compatibility and procedures, not universal dark-frame files for supposedly identical phones. Individual calibration belongs to the user's sensor and conditions. Start with a few verified devices; camera characteristics vary. [Android camera metadata reference](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics).

#### 9. A personal sky atlas and fieldbook

**The experience:** “These are the constellations, regions and changes I have photographed myself.”

After identifying a real captured field, offer optional annotations, named objects that can actually be located in the image, and a private collection organized by sky region and date. Let users compare the same region across seasons and explain simple observations such as star drift and saturation.

This extends target framing into a result people can learn from, revisit and share. The free experience can include common annotations and a small fieldbook; premium exports might provide detailed posters, project comparisons and a richer offline catalog.

**First slice:** identify a few common fields, save the clean original/result, and export an optional annotated copy. Omit labels when confidence is insufficient. A catalog object's expected position does not establish that its faint detail was detected.

**Test:** identification accuracy, repeat visits to the collection and voluntary sharing. Avoid turning engagement into a daily streak that penalizes cloudy weather.

#### 10. Import and rescue photographs taken elsewhere

**The experience:** “I already took these photos with my phone's own camera. Help me finish them.”

Support a deliberate handoff from compatible manufacturer-camera files into StarCam. Basic editing remains useful on phones whose third-party camera access is restricted. For supported sequences, offer alignment, selection, calibration and project reuse.

Start with a clearly defined set of DNG/JPEG inputs and a compatibility preview before a long operation. Add other formats only when decoding, metadata and color handling are verified. Imported photos should retain their origin and original files.

**Why it could matter:** it expands the useful audience beyond people who capture everything inside StarCam. The differentiation would be a reliable workflow, not being the first mobile stacker; Eagle and AstroShader already advertise import functionality.

**Constraint:** a rendered computational JPEG and a linear RAW exposure need different treatment. Processing cannot recreate unavailable RAW information or guarantee an improvement over the stock camera. Validate with actual source files from each supported camera app.

#### 11. A second phone becomes the field controller

**The experience:** put one phone on the tripod or telescope, then monitor it comfortably from another phone/tablet.

Pair locally, show low-bandwidth previews, capture progress, storage, battery and relevant alerts, and allow an orderly finish/save. This also serves people who cannot comfortably reach a high or awkwardly mounted camera.

Start with one capture device and one controller, with the session explicitly armed on the camera phone. Keep camera ownership and saving independent of the connection so losing the controller never loses the photographs.

**Paid opportunity:** full control and monitoring can provide useful convenience without requiring a cloud subscription. Basic shutter support and safe recovery should stay free.

**Constraint:** Android supports local device networking, but camera foreground/background restrictions still apply. Do not promise unattended remote camera startup after reboot. [Wi-Fi Direct](https://developer.android.com/develop/connectivity/wifi/wifi-direct), [foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

#### 12. Capture procedures that explain and reproduce a result

**The experience:** a shared photo includes “Here is how I made it; test this procedure on your phone.”

Share a versioned recipe containing the goal, a short validation capture, settings, acceptance checks and processing choices. Adapt it to the receiving device's capabilities rather than applying exposure settings blindly.

Keep trustworthy starters, importing and optional share cards free. Pro could add authoring of multi-stage procedures, reusable processing and supported project automation. Start with a small settings file and local card export; a hosted recipe directory can follow if people use them.

**Why it is good:** helpful photographs become demonstrations and tutorials that others can act on. This is a plausible distribution loop without first building a social network.

Include the actual phone/lens, useful integration and relevant equipment on optional cards. Hide precise location by default. Recorded settings are useful context, not forensic proof of authenticity.

#### 13. Telescope adapter coach and Moon/planet studio

**The experience:** help a user with an inexpensive phone adapter get past a black view, bright circle or blurry Moon.

Guide centering over the eyepiece, camera selection, vignetting checks, exposure and focus. A paid processing stage could select and align sharper video frames, beginning with the Moon before more demanding planetary targets.

This opens an equipment-assisted audience and offers a striking before/after demonstration. Adapter guidance should be free; advanced processing and rig presets are plausible upgrades.

**Constraint:** lunar/planetary processing needs a different registration/quality pipeline from wide-field star stacking. Optics, atmospheric conditions, compression and sampling remain real limits. AstroCam already advertises lucky imaging; the potential advantage is making the complete adapter-to-result workflow easier.

Prototype with a few actual telescope/adapter combinations before committing to USB cameras or mount control. [Celestron's phone-adapter workflow](https://www.celestron.com/products/smartphone-adapter-dx-kit-1-25) and [AutoStakkert's processing guides](https://www.autostakkert.com/wp/guides/) are useful technical starting points.

#### 14. Group observing with a shared outcome

**The experience:** an astronomy club host helps a small group take their first successful sky photographs.

Share a target, instructions and compatible capture recipe. Participants keep their own captures and can opt to contribute finished results to an event album. A tablet display could show progress during outreach.

Begin with downloadable/local event instructions and recipe files; joining should be free. Add paid host displays or organizer tools only after clubs demonstrate a repeated need. A full public social feed, mixed-phone RAW combination and scientific meteor triangulation should not be the first version.

**Why it could matter:** clubs, campsites and educators provide a practical setting for onboarding, real-device testing and useful word of mouth.

**Test:** successful first captures per event, setup/support time and whether hosts choose to run another event. Sharing must be deliberate; precise location and photographs stay private until users choose otherwise.

### More experimental creative directions

- **Two views of the same night:** render a conventional horizon-fixed video and an observed sky-stabilized version, illustrating Earth's rotation. This extends the earlier video idea and needs reliable registration, coverage cropping and exposure consistency.
- **A private backyard observing notebook:** mark obstructions and lighting, save shooting directions and compare like-for-like test exposures. Later suggest useful capture windows for that actual site. Treat brightness readings as relative measurements unless properly calibrated.
- **A season in one photograph:** recover a saved composition over months and present the user's real captures as a seasonal comparison or journal. This builds on multi-night projects and the personal atlas.

These are experiments to test after the underlying projects and geometry work. None requires inventing astronomical detail or claiming a phone can exceed its optical limits.

### A realistic route toward Google Play category leadership

Category leadership is an ambition, not a ranking outcome that feature work can guarantee. The initial goal should be a specific audience that repeatedly recommends StarCam: people using supported Android phones and a tripod who want a finished sky photograph with understandable guidance.

#### A. Win supported devices and a complete free outcome

Offer a real capture/process/save test before asking for money. Preserve a usable free workflow, clean photo export, recovery, compatibility guidance and access to existing captures. Keep ads and purchase interruptions out of acquisition.

Start with a deliberately supported device set and expand from measured results. Publish honest examples with camera/lens, equipment, integration and conditions; label a telescope-assisted result accordingly. Compare with the stock camera fairly, under stated conditions.

This is a quality strategy as well as marketing. Google documents that [core Android vitals affect Play visibility, including device-specific stability issues](https://developer.android.com/google/play/vitals). A passing short lab crawl does not establish that an hour-long real-sky session is reliable.

#### B. Build a photo-to-recipe-to-new-user loop

A successful user can optionally share their photograph, recorded procedure and device details. A new user follows that recipe through a compatibility check to their own capture. Good results become useful tutorials and credible product demonstrations.

Recruit a small set of phone-photography creators, astronomy clubs and outdoor venues for real field testing. Make device-specific guides from those results. This is a distribution hypothesis to measure; photo sharing is not itself a documented Play ranking factor.

Use a curated gallery before investing in accounts, public feeds, moderation or cloud RAW storage.

#### C. Match discovery to a concrete job

Test separate messages such as:

- Photograph the Milky Way with a supported phone and tripod.
- Finish existing night-sky photos on your phone.
- Build an Orion project over several clear nights.
- Make dependable star trails or review meteor candidates, once those workflows are ready.

Use representative real results as the main visual evidence. A candidate title is `StarCam: Astrophotography`; test recognition and intent rather than stuffing the listing with repeated keywords.

Google Play supports [custom listings for search keywords, countries, campaign URLs and other segments](https://support.google.com/googleplay/android-developer/answer/9867158?hl=en). Link each creator tutorial or campaign to a relevant listing. These are targeting tools, not guarantees of search position; actual keyword demand/competition was not measured in this research.

Localize based on traffic and support capacity. Use season/hemisphere-appropriate examples and verify dates/visibility before publishing event guides.

#### D. Test the store presentation with enough evidence

Use [store listing experiments](https://google.play/business/store-listing-experiments/) to compare a major screenshot/message at a time. For example, test a real finished result against an equally honest before/after treatment. Keep the in-app capability and store promise aligned.

Play provides acquisition and one-day-retention measures for these experiments. For an astronomy utility, also measure return over later clear-night opportunities: next-day use alone is a poor description of long-term value. Small samples should not be treated as a winning strategy.

Seasonal tutorials can create useful reasons to return. Play promotional-content featuring is a later possibility, but access for apps is eligibility-gated and featuring is discretionary. [Promotional content guidance](https://support.google.com/googleplay/android-developer/answer/12932541?hl=en).

#### E. Place the upgrade after demonstrated value

Keep one simple permanent Pro upgrade as the initial paid experiment. The earlier EUR 14.99 suggestion remains a hypothesis; test price and packaging after people can achieve a good result.

Charge for substantial workflows: multi-night accumulation, advanced finishing, larger projects, remote control and supported automation. A useful free project journal or trial can show the difference. Do not make retrieving existing photographs depend on an active subscription.

A recurring service can be considered later for genuine ongoing value/cost such as optional forecast data, backup or cloud processing. Avoid committing to that infrastructure before demand is clear.

#### F. Measure the funnel that matters

| Stage | Define and measure |
| --- | --- |
| Discovery | Store visitors to installs, segmented by listing/campaign/language |
| Device confidence | Eligible users completing a real compatibility test |
| First value | Attempted sessions that produce an accessible saved photo; count technical completion separately from user-rated image quality |
| Return | Users completing another useful session/project visit over 30/60/90 days |
| Sharing | Voluntary result/recipe exports and attributed new users |
| Revenue | Pro purchases, refunds, support workload and actual net proceeds |
| Reliability | Capture/save failures, crashes, ANRs and resource problems by device/lens/software version |

Baseline these before setting numerical targets. No category-specific conversion benchmark or market-size estimate was established here. Scale paid acquisition only after a repeatable path to a successful capture and purchase is visible; compare acquisition cost with actual proceeds and support cost.

Ask for an honest review after users have had meaningful experience, using the standard flow without pre-screening whether they are happy. [Google's review guidance](https://developer.android.com/guide/playcore/in-app-review). Keep support available regardless of rating and never exchange rewards for reviews.

### What to prioritize next

**Best combined bet:** free device confidence + free reusable recipes/share cards + paid multi-night projects, supported by the earlier editor and Autopilot.

This combination addresses four different jobs: getting users in, helping them succeed, giving them something worth sharing, and giving them a reason to return and pay. The personal sky atlas is a strong next retention/learning experiment. Importing existing captures can broaden the audience if Camera2 limitations constrain capture support.

Run small field prototypes before building the remote, telescope or club platforms. Defer a full social network, broad telescope-driver support, cloud RAW processing and a complete planetarium until their costs are justified by demonstrated use.

No application code or external listings were changed for this research.

For the current release checks, beta test sheet and 100,000-user income scenarios, see [launch and revenue plan.md](launch%20and%20revenue%20plan.md).


