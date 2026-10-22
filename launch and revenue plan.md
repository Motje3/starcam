# StarCam launch and revenue plan

Prepared on 21 October 2026. Current source inspected: `a79e15d`, StarCam 0.3.1. This is a focused launch/configuration check and financial scenario model, not a full re-review of every fix or a prediction of sales. No app code, Play Console settings or external listings were changed.

## What 100,000 users could earn

The important distinction is between total users, monthly active users and paying customers. This model assumes **100,000 total unique users of a free app**, some of whom buy a permanent Pro upgrade. The conversion rate is measured across that entire group.

### Assumptions

| Input | Assumption |
| --- | --- |
| Users | 100,000 total unique users |
| Product | One-time Pro upgrade |
| Customer price | EUR 14.99 including the illustrative VAT below |
| Consumer VAT | 21% for the example; actual rates depend on customer location and transaction |
| Combined Play fees | 15% of the VAT-exclusive amount, assuming the applicable first-USD-1-million tier/program |
| Refund sensitivity | Optional 5% full refunds, assuming corresponding VAT/fee reversals |
| Excluded | Advertising, development, hosting, support, other operating costs, income/business taxes and other applicable charges |

Conversion and refund percentages are chosen scenarios, **not observed StarCam metrics or industry benchmarks**. Consumer VAT is not calculated from the developer's location alone.

Google's current fee schedule varies by region, install category and program. Its qualifying first-million tier can total 15% with Play Billing; this is not a universal rate for every sale or revenue level. Confirm the actual account/market treatment before using a business forecast. [Official fee schedule](https://support.google.com/googleplay/android-developer/answer/112622?hl=en). Google documents tax-inclusive pricing and transaction-specific VAT treatment. [Official tax guidance](https://support.google.com/googleplay/android-developer/answer/138000?hl=en).

### Scenarios

Rounded to the nearest euro.

| Overall Pro conversion | Paying users | Customer receipts including illustrative VAT | After illustrative VAT and Play fees | Also allowing 5% refunds |
| --- | ---: | ---: | ---: | ---: |
| 1% | 1,000 | EUR 14,990 | EUR 10,530 | EUR 10,004 |
| 3% | 3,000 | EUR 44,970 | EUR 31,590 | EUR 30,011 |
| 5% | 5,000 | EUR 74,950 | EUR 52,651 | EUR 50,018 |
| 10% | 10,000 | EUR 149,900 | EUR 105,302 | EUR 100,037 |
| 20% | 20,000 | EUR 299,800 | EUR 210,603 | EUR 200,073 |

Formula before refunds:

```text
proceeds = users × purchase rate × price ÷ (1 + VAT rate) × (1 − Play fee rate)
```

For this example, each retained Pro purchase contributes about **EUR 10.53** before expenses and income/business tax. A 5% full-refund allowance reduces the cohort average to about EUR 10.00 per initial purchase.

**These figures are proceeds, not profit.** At 5% conversion and the refund allowance, the cohort produces approximately EUR 50,018 before operating costs. If acquiring the 100,000 users costs EUR 30,000, about EUR 20,018 remains before development, support, hosting and tax. If acquisition costs EUR 100,000, the same cohort is already loss-making before those other expenses.

### Why installs alone are insufficient

- **100,000 total users:** apply an overall purchase rate, as above. Users who cannot use the camera or never finish a session still belong in that denominator.
- **100,000 monthly active users:** a lifetime unlock does not make those same people pay again every month. Monthly proceeds depend on new purchases and any separately valuable recurring products.
- **100,000 paying customers at EUR 14.99:** customer receipts would be EUR 1,499,000 before deductions. Do not apply the flat 15% model to all sales automatically: account-wide annual earnings and higher/regional fee tiers matter.
- **100,000 downloads:** may include reinstalls and inactive/ineligible users; downloads are not necessarily 100,000 distinct engaged potential buyers.

A one-time upgrade can be a good fit for a local camera tool, but it requires continuing acquisition or later products that provide additional value. Ads require a separate model based on active use, impressions and actual geographic ad rates; download count alone cannot estimate advertising income.

### Paid acquisition limits

With 5% refunds and the same assumptions, the maximum proceeds per acquired user are:

| Overall Pro conversion | Lifetime proceeds per acquired user, before all other costs |
| --- | ---: |
| 1% | EUR 0.10 |
| 3% | EUR 0.30 |
| 5% | EUR 0.50 |
| 10% | EUR 1.00 |
| 20% | EUR 2.00 |

At 5% conversion, paying EUR 0.50 per install consumes almost all modeled proceeds. The sustainable acquisition cost must be lower after accounting for support, development, refunds not covered by the assumption and profit. Organic tutorials, genuine recommendations and useful shared capture recipes are therefore worth testing early.

## Concrete launch gaps in the current code

These are observations at `a79e15d`. The earlier [gpt review.md](gpt%20review.md) describes an older revision; this check does not claim all of its findings remain unfixed.

### 1. Update the target SDK for an ordinary new submission

[app/build.gradle.kts](app/build.gradle.kts), lines 9 and 14, still sets compile/target SDK 35. Current Google guidance requires new mobile apps and updates to target **Android 16 / API 36 or higher from 31 August 2026**. Google mentions extensions to 1 November 2026, but no account-specific extension was verified here.

Prepare the SDK/toolchain update and test relevant behavior changes, rather than merely changing the number. [Current target API requirements](https://developer.android.com/google/play/requirements/target-sdk).

### 2. Verify native 16 KB compatibility in the release artifact

[app/build.gradle.kts](app/build.gradle.kts), line 53, still pins NDK 27.0, and [CMakeLists.txt](app/src/main/cpp/CMakeLists.txt), lines 25–31, still lacks the documented page-alignment linker configuration. This reconfirms the source-configuration gap from the previous review; it is not a newly reproduced runtime crash.

Upgrade/configure the native build and inspect every packaged library plus APK ZIP alignment. Run the actual release on a 16 KB environment. Use [current Android guidance](https://developer.android.com/guide/practices/page-sizes) for the applicable requirements; older articles may carry different enforcement dates.

### 3. Align Play device filtering with the app's required hardware

[AndroidManifest.xml](app/src/main/AndroidManifest.xml), lines 16–17, declares RAW and manual sensor capabilities optional, while the current camera workflow cannot run without a RAW-capable back camera and depends on manual controls.

If these remain essential, declare/filter them accordingly and maintain a tested phone/lens set. If a future import/edit-only fallback works without them, make that fallback a real usable path. Capability flags alone still do not prove a particular lens works. [Android feature filtering](https://developer.android.com/guide/topics/manifest/uses-feature-element).

### 4. Prepare a production release and payment path

The repository's [build script](tools/build.ps1) builds/debug-signature-checks a debug APK. The module does not configure a release signing setup. An external setup may exist, but it was not established from these files.

Prepare a signed release AAB, upload-key handling, Play App Signing and an install/update test that preserves sessions and settings. Avoid generating or replacing signing keys casually. [Official signing guidance](https://developer.android.com/studio/publish/app-signing).

No in-app billing/entitlement implementation was found in the current module. This is required work **if** choosing a freemium Pro unlock. An upfront paid app does not need an in-app billing client merely to charge for downloading it. Test purchase, cancellation, pending purchases, restoration, refunds and offline entitlement behavior for the chosen model.

### 5. Validate the fixes on real devices

The project has built-in synthetic/self-test and manual camera tooling, but no conventional automated test source sets were found in this check. The highest-value next engineering work is a targeted regression pack plus a small field beta, including the release artifact.

Test across the selected OnePlus, Pixel, Samsung and other supported device/lens combinations before expanding the compatibility promise. Specific device choices should follow access and intended audience, rather than an unverified claim to support every Android camera.

## A practical beta test sheet

Record app version, phone/model, OS/build, physical lens, RAW resolution, requested/actual exposure and ISO, mode, conditions and outcome. Do not collect exact location or personal photos by default.

| Test | Required evidence |
| --- | --- |
| First use by a beginner | Tester reaches an accessible saved photo using only the app's instructions |
| Normal supported night session | Finished result and requested source files are valid; integration and frame counts agree |
| Background/lock/unlock | Capture and orderly finishing behave as promised; no invalid buffer access |
| Interruption or camera loss | Useful frames survive; recovery or failure is understandable |
| Cancel/finish during each stage | Work stops/finishes as intended; resources remain valid until processing ends |
| Low storage and failed writes | No false success; original data survives; useful recovery instructions |
| Re-stack and export retry | Source files stay unchanged; no duplicate exposures; repeatable output |
| Long capture and thermal escalation | Memory/storage remain bounded and thermal policy responds |
| Update installation | Previous sessions/settings remain usable after installing a signed update |
| Pro purchase lifecycle, when implemented | Entitlement grants/restores correctly and existing photos remain accessible |

Measure technical success separately from aesthetic quality. An accessible file is necessary, but does not prove stars are sharp or color is good.

For personal Play developer accounts created after 13 November 2023, Google currently requires at least **12 continuously opted-in testers for 14 days** before applying for production access. This is conditional on account type/date and does not replace the field tests above. [Official testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en).

## Growth work that is more useful than another feature list

### First: prove the first-photograph experience

Recruit a small varied cohort from the supported devices. Watch where people fail: setup, focus, waiting, saving, understanding the result or deciding whether Pro helps. Fix the most common real failures before promoting more widely.

A suggested initial recruitment target such as 20–30 people is a practical choice, not statistical proof or a market benchmark. Cloudy weather and rare target opportunities mean participants need clear tasks and enough time.

### Second: prepare one clear paid offer

Describe the free outcome and the specific Pro improvement. For example: a complete basic sky photograph is free; advanced finishing and cumulative multi-night projects are paid when those features are ready.

Test willingness to pay with actual example results and working workflows. Treat interview answers as weaker evidence than genuine purchases. Compare prices only with sufficient traffic and account eligibility; keep the feature bundle and promises consistent. Google provides [one-time-product price experiments](https://support.google.com/googleplay/android-developer/answer/13343030?hl=en-GB) for eligible active products.

### Third: create a proof-based store kit

Prepare three campaigns to test:

| Audience/job | Evidence to show |
| --- | --- |
| Beginner with a tripod | The same supported phone from setup to its actual finished photo |
| Phone enthusiast | Verified lens/settings, source exposure and measured result |
| Returning hobbyist | An existing project improving across real capture sessions |

Use actual photos with stated equipment/conditions. Prepare a screenshot storyboard and a short demonstration for each audience; publish only capabilities that work in the released version. Use campaign-specific links and relevant [custom store listings](https://support.google.com/googleplay/android-developer/answer/9867158?hl=en), then measure through successful saving and purchase.

### Fourth: review measured economics before buying scale

Track:

1. Store visit to install.
2. Install to eligible/working device.
3. Eligible session attempt to accessible saved image.
4. User-rated useful result and subsequent session/project return.
5. Actual Pro purchase, refund and support cost.
6. Attributed acquisition cost versus realized proceeds.

Google documents that [core Android vitals affect Play visibility](https://developer.android.com/google/play/vitals). Reliability is part of the growth work. There is no verified shortcut or feature count that guarantees the number-one position.

The strongest next deliverable is a tested release/beta package and evidence from real users. Use the existing [future ideas.md](future%20ideas.md) for the longer-term roadmap; avoid adding expensive features before the current capture-to-purchase path is understood.

