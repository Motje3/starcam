# GPT review

Reviewed on 21 October 2026. Revision at completion: `5ed0d77` (`Engine: reject degenerate alignments; make stacking bounds-safe`). Scope: capture logic, sensors, native processing, image quality, background execution, persistence, re-stacking and export. Visual design and frontend presentation were excluded. Screen code was inspected only where it owns processing jobs or files.

This is a source review with build/lint validation. Failure scenarios below follow from the code; they have not been reproduced on the phone during this review. No application source was changed.

Follow-up on 21 October 2026: the app has since advanced to `a79e15d` (0.3.1), which includes fixes. The findings below describe their original reviewed revision and should not all be assumed unresolved. A focused check of the newer release configuration, beta test sheet and earnings scenarios is in [launch and revenue plan.md](launch%20and%20revenue%20plan.md); the fixes have not all been re-tested in this review.

## Overall assessment

The app already has a credible foundation: manual RAW capture, a camera recovery ladder informed by real device failures, star-based focusing, geometric registration, robust stacking, frame weights, lens shading correction and a separate linear TIFF master. The existing alignment plausibility checks and tile memory budgeting are useful safeguards. I would keep this architecture and improve its contracts and calibration before adding more processing effects.

The largest improvements are correctness and recoverability: preserve calibrated faint signal, make re-stacking reproducible, keep camera buffers alive until native work finishes, and treat saving as a transaction. These matter more than adding another denoiser or sharpening mode.

Priority meanings: **P1** = address before relying on long or irreplaceable sessions; **P2** = important quality/reliability issue with a specific trigger; **P3** = smaller or conditional issue. No P0 issue was established.

## Findings in recommended order

### 1. P1 — Activity teardown can release a RAW buffer while native ingestion still reads it

Evidence: [StarCamViewModel.kt][vm], lines 405–418; [CaptureEngine.kt][capture], lines 164–174 and 618–620; [CameraController.kt][controller], lines 307–311.

The teardown code intends to wait for native work, but it gathers only jobs whose `isActive` is true. A cancelled coroutine can be inactive while its synchronous JNI call is still running. `activeJob` applies the same filter. The resulting empty list makes `joinAll()` return immediately, then closes the ImageReader whose direct buffer is being read in C++.

Trigger: finish the Activity, or dismiss its task when that destroys its ViewModel owner, during native frame ingestion. Possible result: invalid native memory access and a lost session. The 30-second timeout also must not authorize freeing a buffer still in use.

Fix: retain the job until it is **completed**, request abort/cancellation, and join it before releasing its camera/image resources. Longer term, a service-owned session controller should own capture and processing; the Activity ViewModel should observe it. Currently capture runs in `viewModelScope`, while the foreground service only holds a notification/wake lock, so finishing the Activity also cancels the session.

Android documents that the [ViewModel scope is cancelled before onCleared](https://developer.android.com/reference/kotlin/androidx/lifecycle/ViewModel). The buffer-lifetime risk is inferred from that contract and the code above.

### 2. P1 — Dark subtraction mixes the light and dark sensor baselines

Evidence: [CaptureEngine.kt][capture], lines 607–610 and 733–741; [engine.cpp][engine], lines 548–566 and 750–758; [bayer.cpp][bayer], lines 184–211.

Lights carry dynamic black levels. Dark capture omits them, native `addDark` ignores its metadata, and the master averages uncorrected RAW values. Subtraction then uses the **light's** black level to estimate the dark's excess. After normalization, the effective result is approximately `lightRaw - darkRaw`, retaining any difference between their black baselines.

Example with no dark current: light black = 64 and sky signal = 10 gives RAW 74; dark black = 80 gives RAW 80. The current subtraction clamps the result to zero instead of preserving the sky signal of 10. Different baselines can also leave positive offsets. Reusing a dark from another thermal state increases the concern.

Fix: subtract each dark's own measured black level before averaging; preserve its calibration metadata and actual exposure/ISO. Store a signed floating-point dark signal, or use an explicitly documented master baseline. Validate compatibility when reusing it. Android describes [black level as a per-frame value that can vary with capture settings](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#SENSOR_DYNAMIC_BLACK_LEVEL).

### 3. P2 — Negative calibrated samples are clipped before stacking

Evidence: [bayer.cpp][bayer], lines 31–35, 207–211 and 334–337.

Normalization and dark correction discard negative samples before the frames are combined. Read noise around a faint signal has both signs; keeping only the positive side creates a brightness bias that averaging cannot remove. Different white-balance gains can make that bias differ by channel.

For a symmetric pair of noise samples `[-10, +10]`, the correct mean is zero; early clipping changes the mean to +5. This matters most for faint backgrounds and dark-calibrated data.

Fix: keep signed floating-point calibrated values through demosaic, registration and stacking. Apply clipping deliberately when preparing display/output. This requires changing the RAW calibration contract, rather than simply removing one clamp while retaining unsigned intermediate storage.

### 4. P2 — Two-frame stacks currently behave as maximum composites

Evidence: [stack.cpp][stack], lines 454–459; [common.h][common], lines 195–199; [engine.cpp][engine], line 929.

For fewer than five valid samples, stacking calls `median_of`. That helper returns sorted element `n/2`, without averaging the middle pair. With two inputs, it returns their maximum independently for each color channel. With four, it returns the third-smallest value. Two surviving frames are allowed, and border pixels can have two/four samples even in a much larger stack.

Result: positive brightness/color bias and poorer noise reduction where coverage is low. Example: samples `[0.01, 0.03]` produce 0.03 instead of the midpoint 0.02.

Fix: define the small-sample combination explicitly. For an ordinary median, average the two central values for even counts; decide whether a weighted mean is preferable for two frames. Avoid blindly changing every robust-statistics caller of the shared helper. Test both whole-image small stacks and low-coverage borders.

### 5. P1 — Re-stacking changes its source data and forgets original frame decisions

Evidence: [Restacker.kt][restacker], lines 56–83; [engine.cpp][engine], lines 443 and 467–469; [CaptureEngine.kt][capture], lines 642–652.

There are two related problems:

- Re-stacking feeds existing cache files into ordinary `addFrame`, which writes sequential cache filenames again. If `frame_0001.sraw` is truncated and skipped, a valid `frame_0002.sraw` can be rewritten into `frame_0001.sraw`, while the original 0002 remains. A second re-stack can count that exposure twice. An interrupted rewrite can also damage a previously valid source.
- It does not restore original motion rejection decisions. It imports every cached light, sets gyro RMS to zero, and never calls `rejectFrame`. A bumped frame whose star metrics still pass can silently re-enter the stack.

Fix: add a read-only native cache-import operation with stable exposure IDs. Restore original frame selection from a durable manifest; make any override explicit. Write each processing result into a separate revision directory. Re-stacking the same session with the same recipe should preserve source hashes, frame identities and selection.

### 6. P2 — Session metadata is vulnerable to truncation and history loss

Evidence: [SessionRepository.kt][repository], lines 219 and 298–304.

Every frame truncates and rewrites the full `session.json`. Process death, a failed write or full storage can leave it unreadable. The next append treats unreadable JSON as a new empty manifest, losing earlier header/frame/issue records. This weakens recovery even when RAW files remain intact.

Fix: use `AtomicFile` or a temporary file with durable atomic replacement and a previous valid snapshot. An append-only frame journal plus periodic snapshots would also avoid rewriting growing history on each frame. Distinguish “manifest absent” from “manifest corrupted”; propagate persistence failures rather than just logging them.

### 7. P1 — Output cleanup and completion happen before saving is fully verified

Evidence: [engine.cpp][engine], lines 1003–1005 and 1140–1157; [CaptureEngine.kt][capture], lines 796 and 814–827; [MediaSaver.kt][media], lines 32–35 and 61–78.

Native finalization can delete the light cache before JPEG encoding/gallery insertion. It also treats TIFF/report-write failure as nonfatal. Kotlin then reports `Done` and gives the success vibration even if `saveFinal` failed. The JPEG is overwritten directly, and gallery insertion has no pending/rollback transaction. A null gallery output stream can still result in a returned URI.

Trigger: allocation failure or storage/I/O failure during saving. A successful stack can be presented as a successfully saved photo when the JPEG is missing or incomplete; the TIFF may also have failed and source cache may already be gone. `final_rgb.raw` can remain recoverable, so this is not automatically total image loss.

Fix: return separate stack, TIFF, session-JPEG and gallery outcomes. Write outputs to temporary files and replace atomically; use MediaStore pending publication and clean up failed inserts. Delay cache deletion until required artifacts are verified. Persist export status and support retrying export from retained RGB/master data.

### 8. P2 — TRAILS/METEOR acquisition spends substantial time between exposures

Evidence: [CaptureEngine.kt][capture], lines 412–415, 555, 572, 618, 624 and 654.

The next exposure waits for synchronous native ingestion, optional DNG writing and other per-frame work. Removing the explicit TRAILS cooldown does not remove these gaps. The source itself notes native ingestion can take several seconds. That creates discontinuous trails and misses meteors during processing. Duration is converted to a frame count using exposure time alone, so actual wall-clock duration includes unaccounted overhead.

Fix: separate acquisition from analysis/storage with a **bounded** queue and explicit buffer ownership/backpressure. Preserve the tested camera-session rebuild workaround on affected hardware. Use a monotonic end deadline for duration modes and record actual exposure duty cycle. This is a major engineering improvement, not a reason to remove the existing device workaround.

### 9. P2 — Star focus scores can describe a moving lens

Evidence: [FocusSweep.kt][focus], lines 103–129; [CameraController.kt][controller], lines 538 and 803–806.

Each focus point immediately captures and scores a frame with the newly requested distance. Lens movement is logged but does not invalidate the measurement. On a device that needs settling frames, the sweep can assign a poor score to a good target or choose a misleading minimum.

Fix: verify reported focus distance and stationary lens state where available; discard settling measurements and use a bounded fallback for missing metadata. Check the selected/interpolated focus with a final validation exposure. Compare a consistent set of isolated stars rather than changing the population at every point. Android documents [multi-frame lens settling](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#LENS_STATE).

### 10. P2 — Exposure selection needs feedback from the actual captured result

Evidence: [AutoExposure.kt][exposure], lines 35–52; [CaptureEngine.kt][capture], lines 402–405, 599–600, 735–736 and 805; [CameraController.kt][controller], lines 799–815.

The engine records requested exposure/ISO even though actual result metadata is available. This affects probe extrapolation, calibration matching and reported integration time. Camera2 permits quantization of the [actual exposure duration](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#SENSOR_EXPOSURE_TIME).

Separately, auto exposure always chooses the maximum permitted exposure and adjusts only ISO. If ISO reaches its lower limit, it cannot shorten exposure to meet its background target. A saturated probe is also unsuitable for linear extrapolation.

Fix: persist actual exposure/ISO from `TotalCaptureResult`, use the actual probe settings, and validate the chosen exposure with a second measurement. Include clipped-pixel/star-core fraction and reduce exposure when the ISO limit prevents the background target. Keep the optimization bounded so setup time stays predictable.

### 11. P1 — Some allocation errors become empty-image dereferences

Evidence: [bayer.cpp][bayer], lines 152–157 and 269–275; [stack.cpp][stack], lines 326–343 and 378–384.

RAW-region copying and demosaicing catch allocation failures, clear their outputs and return without a success indicator. The tile code proceeds and dereferences `regionRGB`. Its outer C++ exception handler cannot catch invalid memory access. This matters on a phone processing large RAW frames, even though the tile sample budget already exists.

Fix: give every allocating stage an explicit result/error contract and stop the tile on failure. Budget the whole pipeline, including retained buffers, rather than sample tiles alone. One full float RGB image costs about **144 MB at 12 MP / 600 MB at 50 MP** before extra images, dark buffers, previews and tile work. Release capture-only buffers before finalization and choose concurrency from measured memory headroom. Full-resolution foreground compositing is already guarded in settings; retain that safeguard.

### 12. P2 — The gyro rejection window includes failed attempts and camera repairs

Evidence: [CaptureEngine.kt][capture], lines 569, 589, 645 and 893–923.

One motion window spans the entire retry/recovery sequence. A bump in an unsuccessful attempt, or while repairing the camera, can reject the later stationary successful exposure. It also includes request setup and waiting after exposure, rather than only the captured image's integration interval.

Fix: associate motion samples with each actual attempt and successful exposure timestamps. Keep sample count/availability so “no measurements” cannot be mistaken for demonstrated stillness. Test a bumped failed attempt followed by a stable successful retry.

### 13. P2 — Meteor recovery adds single-frame noise over a rectangle

Evidence: [engine.cpp][engine], lines 312–374; compare [stack.cpp][stack], lines 339–341.

Meteor composition max-blends the entire padded bounding rectangle, rather than only the detected streak. This can restore unrelated noisy pixels and brighten ordinary stars in that rectangle. The meteor crop also omits lens shading correction used in the underlying stack.

Fix: blend significant streak residuals through a narrow feathered mask and use the same calibration/color/shading path as the stack. Verify pixels outside the streak mask remain unchanged. Preserve candidate crops separately for inspection before trusting automatic classification.

## Other worthwhile corrections

| Area | Evidence | Improvement |
| --- | --- | --- |
| Green white-balance mapping | [CamMeta.kt][meta], lines 234–253 | `greenEven` is selected by the red row, which swaps the greens for GBRG/BGGR when they differ. Select by image-row parity and test all four CFAs. [Android defines even/odd by row](https://developer.android.com/reference/android/hardware/camera2/params/RggbChannelVector). |
| Camera ownership race | [StarCamViewModel.kt][vm], lines 192–238 and 266–271 | Busy is checked before scheduling lens changes, but not inside the camera mutex; session start uses a different path. Serialize ownership reservation, open/configure, capture and tests together. |
| RAW/luma origin conversion | [common.h][common], line 119; [stack.cpp][stack], line 205 | Doubling translation omits the superpixel center. Correct mapping is `tRaw = 2*tLuma + c - A*c`, `c=(0.5,0.5)`. Small for short sessions; about 0.366 RAW pixels at 30° rotation. |
| Chroma denoising | [post.cpp][post], lines 481–565 | The default path replaces chroma from a heavily smoothed low-resolution field without star protection. Measure small colored-star hue/halo changes; protect cores/edges or denoise a noise-weighted chroma residual. |
| Finish-now behavior | [CaptureEngine.kt][capture], lines 208–212, 690–692 and 713–714 | The flag stops lights but is ignored when deciding to start darks and during the dark loop. It can still wait for lens cover or shoot remaining darks. Carry the flag through optional stages. |
| Re-stack lifetime and file ownership | [SessionDetailScreen.kt][detail], lines 212–244; [SessionRepository.kt][repository], lines 53–91 | Screen cancellation does not stop synchronous native work, and cache pruning does not lease active re-stacks. Use one durable job/lock per session and exclude leased inputs from pruning. |

## Highest-value engine improvements and features

1. **Reproducible, recoverable processing.** Persist stable frame IDs, original rejection reasons, complete StackConfig, calibration identity, engine version and output revisions. Support “recover interrupted session,” “retry export” and returning to a background re-stack. This is more valuable than another capture mode.
2. **Coverage/weight maps and reliable-overlap cropping.** Show/store how many accepted samples support each output pixel. Use it to crop noisy/empty edges and avoid treating two-sample borders like the central stack. Include effective weighted integration and rejection reasons in the report.
3. **Calibration matched to conditions.** Extend the existing dark library with actual capture metadata and available thermal information. Use temperature cautiously: battery/device temperature is a proxy, not a direct sensor measurement. Correct black-baseline handling first.
4. **Background/transparency normalization before rejection.** Clouds and changing sky brightness can make corresponding samples inconsistent even after geometry is correct. Estimate robust background offsets/scales from suitable regions before combining, with limits that protect real astronomical structure.
5. **Color-aware quality measurements.** Keep PSF/FWHM, but add eccentricity, clipped-star fraction, spatial alignment residuals and star-color preservation. Diagnose gradients and image edges separately from the central sky.
6. **Measure before adopting more complex registration.** Wide-field distortion can make one similarity transform insufficient over long sessions. Plot residuals by image position and session duration; introduce a distortion-aware model only when those measurements justify it.

For maintainability, the largest refactor should be around **ownership and explicit outcomes**: one session owner; immutable inputs; typed calibration/frame metadata at the boundary; and stage results that distinguish rejection, missing data, allocation failure and export failure. Keep the existing diagnostics, settings snapshots and partial-session camera recovery.

## Validation and targeted tests

Checks performed:

- `assembleDebug` completed successfully, including the native ARM64 build tasks. No APK was installed.
- `lintDebug` completed analysis and failed with **1 error and 15 warnings**. The blocking report is `ProduceStateDoesNotAssignValue` at `ui/SessionsScreen.kt:141`, outside this review's frontend scope. This lint report was not treated as proof of an engine defect. The initial offline attempt lacked a cached dependency; a normal dependency-resolving run produced the final lint result.
- The built-in C++ self-test was inspected, **not executed**. It exercises useful synthetic alignment/stacking properties, but its stars are color-neutral and its postprocessing check is a smoke test. It does not establish real-device color/focus/calibration quality.
- No device capture, endurance run, allocation-failure injection or quantitative real-RAW comparison was performed. Existing source changes were preserved; this review adds only this document.

Add these regression checks alongside the fixes:

| Check | Required assertion |
| --- | --- |
| Cancel during JNI ingestion | Reader/image memory remains valid until the native call and job complete. |
| Different dark/light black baselines | The same known sky signal is recovered despite different baseline values. |
| Faint signed signal | Combining symmetric noise does not create a positive floor or color offset. |
| Two/four-sample stacks | Even-count combination matches the chosen mathematical definition, including borders. |
| Re-stack twice with a truncated middle frame | Inputs stay unchanged; exposure IDs remain unique; original rejected frames stay rejected. |
| Process death/full disk during manifest/export writes | Previous valid state survives; failed export is recorded and retryable. |
| Focus/exposure metadata simulation | Moving-lens scores are excluded; actual result settings drive calibration and reporting. |
| Failed bumped attempt followed by stable retry | Only motion during the successful exposure determines its rejection. |
| Small colored stars and meteor crop | Hue/flux remain within a stated tolerance; pixels outside the meteor mask are unchanged. |
| Inject allocation failure | Processing returns a useful error without dereferencing cleared images. |
| TRAILS/METEOR benchmark | Record exposure duty cycle and actual wall-clock stop time. |

Start with buffer lifetime, calibration, immutable re-stacking and transactional persistence/export. Then improve acquisition duty cycle and validate focus/exposure feedback. Keep additional processing effects behind measured image-quality comparisons.

## Second pass: additional high and medium findings

Reviewed on 21 October 2026 against the same revision, `5ed0d77`. This pass examined camera callbacks and resource handoff, remaining capture control paths, cache retention, diagnostics, platform/build compatibility, foreground processing and master export. It also inspected the developer scripts. Findings 14–25 below are new; extensions to earlier findings are identified separately. P1 means high priority; P2 means medium priority.

### 14. P1 — Low-storage capture exceeds its own budget and counts unsaved frames

Evidence: [CaptureEngine.kt][capture], lines 969–985, 617–629 and 652–653; [engine.cpp][engine], lines 469–473; [SessionRepository.kt][repository], lines 221–235.

The storage cap forces at least four frames even when fewer are affordable. For example, with roughly 100 MB free and 50 MB per exposure including its DNG, the 60% budget affords one frame, but the cap still schedules four, requiring about 200 MB. The budget is calculated once and does not cover every extra reshoot or changing free space.

More seriously, the capture loop does not check `FrameStats.saved`. It increments `captured` and gives progress feedback even when the native cache write failed. Failed DNG writes do not prevent the count increasing either, and the manifest omits the cache-save outcome. In RAW-only mode the session can report completion with fewer durable exposures than claimed.

Fix: refuse acquisition when there is insufficient space for a useful result; reserve dark/output headroom and recheck during long runs. Track acquired, durably cached and archived frames separately. Stop or finish with verified inputs when a required write fails, and report optional DNG failures explicitly. Test both a low initial budget and storage filling during capture.

### 15. P1 — Cache pruning can delete a session's only image data

Evidence: [SettingsStore.kt][settings], lines 41, 51 and 153–156; [CaptureEngine.kt][capture], lines 624–628 and 762–768; [SessionRepository.kt][repository], lines 53–54 and 75–91.

DNG saving and on-device stacking can both be disabled independently. That combination still reports a completed RAW-only session, but produces neither DNGs nor final images: its only exposures are `.sraw` files in `cache/`. Starting later sessions prunes those files solely by session age, without checking whether another image representation exists. Starting the sixth session can therefore erase the first session's only exposures. Failed or cancelled sessions without DNGs have the same underlying retention problem.

This is distinct from the earlier concern about pruning files used by an active re-stack: it can lose an otherwise inactive session's entire image data.

Fix: require a permanent deliverable for RAW-only capture, such as DNGs, or promote/protect the source RAW files from disposable-cache pruning. Pruning should require verified alternative artifacts or an explicit retention choice for the only copy. Test six RAW-only sessions with DNG saving off and assert the oldest source remains recoverable.

### 16. P1 — Camera recovery accesses Images after invalidating them

Evidence: [CameraController.kt][controller], lines 297–314 and 702–708.

`closeSessionAndReader` closes the ImageReader, then drains its queued Images. Draining reads each Image's timestamp outside the exception guard. Closing a reader invalidates previously acquired Images, so a pending RAW can turn normal session teardown into `IllegalStateException`. In the device-loss callback this exception can escape the camera handler thread and crash the process.

Fix: serialize retirement of a reader generation; stop accepting its callbacks, snapshot/drain queued metadata and release owned Images before closing the reader. Also snapshot Image properties before publishing it to a consumer: `onImageAvailable` currently queues the Image at line 648, then reads its properties at line 657 after releasing the queue lock.

**Extension to finding 1:** runtime device loss also calls `releaseHandles` immediately. It can close a reader while a RAW already handed to native ingestion is still in use, even without Activity teardown. Delivered Images need consumer leases so reader destruction waits for those consumers.

Android explicitly documents [invalid Image access and undefined buffer reads after ImageReader.close](https://developer.android.com/reference/android/media/ImageReader#close()). Test queued late RAWs during recovery and camera loss during ingestion.

### 17. P2 — The built native engine lacks 16 KB page compatibility

Evidence: [app/build.gradle.kts](app/build.gradle.kts), line 53; [CMakeLists.txt](app/src/main/cpp/CMakeLists.txt), lines 25–31; existing ARM64 build artifact inspected with `llvm-readelf -l`.

The project pins NDK 27.0 without the relevant linker configuration. The inspected `libstarcam.so` has **0x1000 (4 KB)** alignment on all four LOAD segments. The other inspected packaged libraries, `libc++_shared.so` and `libandroidx.graphics.path.so`, have 0x4000 alignment.

The engine does not meet the documented ELF alignment requirement for 16 KB Android devices. Native loading can fail where compatibility mode does not cover it. This is a verified binary property; this review did not establish the page size of the user's phone or run a 16 KB device test.

Fix: use NDK r28+ or the documented r27 linker options `-Wl,-z,max-page-size=16384` and `-Wl,-z,common-page-size=16384`. Check every library in the final APK, APK ZIP alignment, and loading on a device/emulator whose `getconf PAGE_SIZE` is 16384. [Official Android guidance](https://developer.android.com/guide/practices/page-sizes).

### 18. P2 — A disconnected camera can be restored as the current open device

Evidence: [CameraController.kt][controller], lines 236–264 and 281–304; [StarCamViewModel.kt][vm], lines 118–121 and 237–241.

`onOpened` publishes the device and resumes the suspended caller. A queued disconnect/error can run before that caller resumes, clearing the device and reporting camera loss. The continuation then unconditionally assigns the same, now closed device back to `device`. Its caller can also mark the camera ready and clear the loss indication.

Fix: publish ownership once, bind callbacks to an opening generation, and validate that the device is still current and usable before returning success. Reproduce with a controlled dispatcher: deliver opened, then disconnected, then allow the open continuation to resume. Final state must remain lost, with no closed handle advertised as ready.

### 19. P2 — Failed or cancelled camera setup can leak resources

Evidence: [CameraController.kt][controller], lines 92, 243 and 375–383; [StarCamViewModel.kt][vm], lines 115–126 and 223–246.

Controllers start a HandlerThread immediately, but a local controller whose `open()` fails is not closed by the ViewModel's failure paths. Repeated failed opens can accumulate threads.

There is also a cancellation window when callbacks resume a coroutine with a new device/session. If cancellation happens after `resume(resource)` but before the dispatcher delivers that resource, the assignment that would take ownership may never run. The existing branch that closes a session when the continuation is already inactive handles late callbacks, but not this delivery window.

Fix: close provisional controllers unless ownership transfers successfully; give resource-returning continuations cancellation disposal and clean up provisional readers on setup failure. Kotlin documents [this resource-return race and cleanup mechanisms](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/suspend-cancellable-coroutine.html#returning-resources-from-a-suspended-coroutine). Test cancellation just after opened/configured callbacks and repeated failed opens; resource/thread counts should return to baseline.

### 20. P2 — Cancel can leave the session waiting for stillness for two minutes

Evidence: [CaptureEngine.kt][capture], lines 182–199, 323–334 and 999.

Cancel sets a Boolean and attempts to abort an existing native session. The stillness gate, which runs before that native session exists, waits only for `motion.isStill` or a 120-second timeout. It does not observe the cancellation flag while suspended. If the phone continues moving, cancel leaves the foreground session waiting until stillness or timeout.

Fix: use a cancellation/stop signal that participates in suspended waits, rather than checking a Boolean only after they return. Apply the same principle to optional-stage gates, while preserving the safe native/resource teardown required by finding 1. Test cancel with a continuously false stillness flow and require prompt completion and service cleanup.

### 21. P2 improvement — Thermal control does not handle escalation or stacking load

Evidence: [ThermalMonitor.kt][thermal], lines 18–38; [CaptureEngine.kt][capture], lines 555–556 and 770–788; [common.h][common], `parallel_for`.

All statuses at or above SEVERE receive the same response: two extra seconds between non-TRAILS frames. TRAILS bypasses that response entirely, and final native processing does not reduce its concurrency or pause based on thermal status. Critical/emergency conditions therefore do not produce a distinct workload response.

For sustained capture this can worsen throttling and increase the chance of interrupted work. The actual thermal behavior of this phone was not measured here.

Improvement: use staged workload reduction, pause new acquisition at an appropriate high status, and reduce or defer native processing until recovery. Add hysteresis to avoid oscillation and preserve the completed exposures before ending a session. Android recommends [reducing sustained workload in response to thermal state](https://developer.android.com/games/optimize/adpf/thermal). Test injected status changes during capture and final processing.

### 22. P2 — The linear TIFF master clips recoverable highlight values

Evidence: [bayer.cpp][bayer], lines 26–35; [stack.cpp][stack], lines 538–543; [tiffout.cpp][tiff], lines 132–139; [engine.cpp][engine], lines 1003–1004.

The floating-point pipeline permits values above 1 after white balance and color conversion, but master export clamps each channel to 1. For example, an unsaturated normalized RAW value of 0.7 with a white-balance gain of 2 becomes 1.4, then clips to 65535 in the TIFF. Later editing of the advertised linear master cannot recover that highlight or its original color ratios.

Fix: export a floating-point linear master, or use a documented common scale that preserves channel ratios and records normalization. Count/report clipped samples. Add an explicit linear color-space description/profile for interoperability. Test unsaturated colored highlights that exceed 1 only after white balance; their ratios should survive export.

### 23. P2 — Sky-gradient removal also fits the landscape foreground

Evidence: [engine.cpp][engine], lines 1022–1061; [post.cpp][post], lines 193–247 and 277–291.

Foreground composition computes a sky/ground mask but does not pass it into postprocessing. The gradient stage excludes stars, then fits the darkest 80% of cells across the entire scene and applies that polynomial everywhere. Dark terrain can therefore be treated as sky-background evidence, creating an artificial sky gradient and changing the ground even when both regions originally have flat brightness. Gradient removal and foreground composition can be enabled together.

Fix: retain a valid sky/background mask for gradient fitting and suitable color/stretch statistics. Exclude terrain from the sky model and protect it from sky-background subtraction. A useful regression is a flat upper sky and flat, darker lower landscape: enabling gradient removal should not create a slope in the sky away from the horizon.

### 24. P2 — The camera stress test can report PASS after skipping or failing its workload

Evidence: [CameraTest.kt][camera-test], lines 153–154, 188–219; [device-test.ps1](tools/device-test.ps1), verdict collection.

When workload simulation is requested, native initialization failure only logs a warning and skips ingestion/DNG work. With a valid handle, the test ignores the returned ingestion failure data and the Boolean from `DngWriter.write`. It then counts the frame as successful and can emit PASS. Consequently, a passing result may validate capture callbacks while failing to validate the native/storage workload that motivated the test.

Fix: distinguish capture success, ingest success and DNG success in the result. If the requested workload cannot run, report an incomplete/failed workload verdict; retain a separate capture-only verdict if useful. Test engine-init failure and DNG/cache-write failure and ensure the command-line verdict preserves that distinction.

### 25. P2 — Diagnostic logs and the writer queue have no runtime budget

Evidence: [AppLog.kt][app-log], lines 72, 94, 203–218 and 242–250; [Diagnostics.kt][diagnostics], lines 195, 231–236 and 254–261.

The documented log byte/file limits are enforced only at startup. During one process lifetime, app logs and mirrored logcat rotate into unlimited new parts without pruning; session logs also have no size cap. The writer queue is unbounded. A long-running process or verbose repeated fault can therefore consume storage needed for exposures, while a stalled/slow writer can accumulate memory.

Fix: enforce rolling file/byte limits during rotation, cap session logs, preserve a free-space reserve, and bound queued log memory with dropped-line accounting. Keep logging nonblocking for camera callbacks. Test sustained logging and a blocked sink; disk and memory use must remain bounded. Exhaustion was not reproduced; the absence of runtime limits was confirmed directly.

### Additional evidence for earlier findings

- **Finding 11, processing error contracts:** [post.cpp][post], lines 642–644, catches processing exceptions and returns `void`; [engine.cpp][engine], lines 1061–1068, can then export and report success. A failure midway through postprocessing needs an explicit failed-stage result or a deliberate, labelled fallback, rather than a partially processed image presented as complete.
- **Finding 13, rejected meteor inputs:** [engine.cpp][engine], lines 305–307, checks alignment and cache availability but not `fr.rejected`. Meteor recovery can reintroduce pixels from a motion/quality-rejected exposure. Honor frame rejection or define an explicit, separately recorded candidate override.

### Second-pass validation

- Confirmed that the source revision remains `5ed0d77`; application code and developer scripts were not modified.
- Inspected the existing native build with `llvm-readelf`; the engine's four LOAD segments report 4 KB alignment.
- Parsed all five PowerShell developer scripts with PowerShell's AST parser: **zero syntax errors**. No setup, installation or device-control script was executed. No additional script defect met the high/medium threshold.
- Cross-checked ImageReader lifetime, coroutine resource delivery and Android page-size requirements against primary documentation.
- No fresh build/lint run was needed because source/build configuration was unchanged. The first-pass results remain recorded above. No new device reproduction, native self-test or fault-injection run was performed.

[vm]: app/src/main/java/com/wm/starcam/StarCamViewModel.kt
[capture]: app/src/main/java/com/wm/starcam/camera/CaptureEngine.kt
[controller]: app/src/main/java/com/wm/starcam/camera/CameraController.kt
[focus]: app/src/main/java/com/wm/starcam/camera/FocusSweep.kt
[exposure]: app/src/main/java/com/wm/starcam/camera/AutoExposure.kt
[meta]: app/src/main/java/com/wm/starcam/camera/CamMeta.kt
[restacker]: app/src/main/java/com/wm/starcam/engine/Restacker.kt
[repository]: app/src/main/java/com/wm/starcam/session/SessionRepository.kt
[media]: app/src/main/java/com/wm/starcam/util/MediaSaver.kt
[detail]: app/src/main/java/com/wm/starcam/ui/SessionDetailScreen.kt
[engine]: app/src/main/cpp/engine.cpp
[bayer]: app/src/main/cpp/bayer.cpp
[stack]: app/src/main/cpp/stack.cpp
[common]: app/src/main/cpp/common.h
[post]: app/src/main/cpp/post.cpp
[settings]: app/src/main/java/com/wm/starcam/session/SettingsStore.kt
[thermal]: app/src/main/java/com/wm/starcam/sensors/ThermalMonitor.kt
[tiff]: app/src/main/cpp/tiffout.cpp
[camera-test]: app/src/main/java/com/wm/starcam/camera/CameraTest.kt
[app-log]: app/src/main/java/com/wm/starcam/util/AppLog.kt
[diagnostics]: app/src/main/java/com/wm/starcam/util/Diagnostics.kt

