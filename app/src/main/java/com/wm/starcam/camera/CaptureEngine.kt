package com.wm.starcam.camera

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureResult
import android.net.Uri
import android.os.BatteryManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Size
import com.wm.starcam.engine.EngineResult
import com.wm.starcam.engine.FrameStats
import com.wm.starcam.engine.NativeEngine
import com.wm.starcam.engine.AnalyzeStats
import com.wm.starcam.sensors.MotionMonitor
import com.wm.starcam.sensors.ThermalMonitor
import com.wm.starcam.session.AppSettings
import com.wm.starcam.session.FocusMode
import com.wm.starcam.session.Mode
import com.wm.starcam.session.SINGLE_FRAME_JPG
import com.wm.starcam.session.SessionPaths
import com.wm.starcam.session.SessionRepository
import com.wm.starcam.session.SettingsStore
import com.wm.starcam.util.AppLog
import com.wm.starcam.util.Diagnostics
import com.wm.starcam.util.ImageRotation
import com.wm.starcam.util.MediaSaver
import com.wm.starcam.util.StackReplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed class CaptureState {
    data object Idle : CaptureState()
    data class WaitingStill(val delayRemaining: Int, val waitingForGyro: Boolean) : CaptureState()
    data object Probing : CaptureState()
    data class Focusing(val step: Int, val total: Int, val label: String) : CaptureState()
    data class CapturingLights(
        val current: Int, val total: Int, val exposureNs: Long, val iso: Int,
        val lastStats: FrameStats?, val rejectedSoFar: Int,
        val cappedByStorage: Boolean = false, val interFrameGapMs: Long = 0,
        // elapsedRealtime when this frame's slot began (gap included), so the UI can count down.
        val frameStartedAtMs: Long = 0L,
        /** The sky watch's warning (clouds, soft stars), see [SkyWatch]; null while all is well. */
        val skyNote: String? = null,
    ) : CaptureState()
    data class AwaitingDarkCover(val secondsLeft: Int) : CaptureState()
    data class CapturingDarks(val current: Int, val total: Int) : CaptureState()
    data class Stacking(val stage: String, val percent: Int) : CaptureState()
    data class Done(
        val result: EngineResult?,   // null => RAWs saved without on-device stacking
        val jpegFile: File?, val galleryUri: Uri?, val sessionDir: File, val framesCaptured: Int,
        val elapsedMs: Long = 0L,    // wall-clock length of the whole session
        /**
         * Finished, but not cleanly: the camera died mid-burst and only the frames before it
         * were stacked, or it needed repairs along the way. Said out loud, never hidden.
         */
        val warning: String? = null,
    ) : CaptureState()
    data class Error(
        val message: String,
        val sessionDir: File?,
        val framesCaptured: Int = 0,
    ) : CaptureState()
}

/**
 * What the session is doing *right now*, inside a phase that otherwise sits on a single
 * [CaptureState] for tens of seconds - a 30 s exposure, a native frame ingest, a JPEG encode.
 * The UI turns this into a clock that always moves, which is the whole difference between
 * "working" and "frozen" on a dark screen in a field.
 *
 * [startedAtMs] is `SystemClock.elapsedRealtime()`. [expectedMs] of 0 means "no idea how long":
 * show an indeterminate spinner counting up. A positive value drives a countdown; overrunning it
 * is normal (thermal pauses, slow HALs) and is reported rather than hidden.
 */
data class Busy(
    val label: String,
    val startedAtMs: Long,
    val expectedMs: Long = 0L,
)

/**
 * A stop the user asked for that the session has not reached yet. An exposure cannot be cut
 * short, so Finish and Cancel take effect once the current frame is in - up to half a minute in
 * which the buttons used to look as if the tap had been missed.
 */
enum class StopRequest { Finish, Cancel }

/**
 * The session state machine (PLAN.md §3.3): stillness gate → probe → star autofocus → lights →
 * darks → native stack → save. One instance per app; one session at a time.
 *
 * Every transition, every frame (with timings and the phone's condition) and every camera
 * failure and repair is logged under the "session" tag, and the session's share of the log is
 * copied into its own `meta/session.log`.
 */
class CaptureEngine(
    private val context: Context,
    private val controller: CameraController,
    private val settingsStore: SettingsStore,
    private val motion: MotionMonitor,
    private val thermal: ThermalMonitor,
    private val repo: SessionRepository,
) {
    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state

    private val _decision = MutableStateFlow<AutoExposure.Decision?>(null)
    val decision: StateFlow<AutoExposure.Decision?> = _decision

    /** Clockwise turn that makes the running session's photo upright; the live stack uses it. */
    private val _rotation = MutableStateFlow(0)
    val rotation: StateFlow<Int> = _rotation

    private val _focus = MutableStateFlow<FocusSweep.Result?>(null)
    val focus: StateFlow<FocusSweep.Result?> = _focus

    /** Live sub-phase clock; see [Busy]. Null when nothing is running. */
    private val _busy = MutableStateFlow<Busy?>(null)
    val busy: StateFlow<Busy?> = _busy

    /** elapsedRealtime when this session started, 0 when none is running. Drives the HUD clock. */
    private val _sessionStartedAtMs = MutableStateFlow(0L)
    val sessionStartedAtMs: StateFlow<Long> = _sessionStartedAtMs

    /**
     * Where this session's calibration came from — reused darks, a fresh set, a shading map.
     * Surfaced in the HUD because silently reusing old calibration is exactly the kind of thing
     * that should never be invisible.
     */
    private val _calibration = MutableStateFlow<String?>(null)
    val calibration: StateFlow<String?> = _calibration

    /** Incremented after every accepted frame so the UI refreshes the live-stack preview. */
    private val _previewTick = MutableStateFlow(0)
    val previewTick: StateFlow<Int> = _previewTick

    /** See [StopRequest]. Null when nothing is pending or no session is running. */
    private val _stopRequest = MutableStateFlow<StopRequest?>(null)
    val stopRequest: StateFlow<StopRequest?> = _stopRequest

    private var job: Job? = null

    /**
     * Guards [engineHandle] across its whole lifetime. Any call that hands the handle to native
     * code must hold this, otherwise runSession's finally can destroySession (delete) the pointer
     * between a reader's load and its JNI dereference — a use-after-free.
     *
     * A ReentrantLock rather than a monitor because the UI-thread callers must be able to give up:
     * fillPreview can hold this while native getPreview waits on the engine mutex that addFrame
     * owns for a whole frame ingest (seconds), and blocking the main thread that long is an ANR.
     */
    private val handleLock = ReentrantLock()
    @Volatile private var engineHandle: Long = 0
    @Volatile private var cancelRequested = false
    @Volatile private var finishEarlyRequested = false
    private var darkGate: CompletableDeferred<Boolean>? = null

    /** Camera repairs run this session (recovery levels 1-3). */
    private var recoveryCount = 0

    /** A session folder created for nothing (stopped before its first frame), removed at the end. */
    private var discardIfEmpty: File? = null

    /** Exposures that failed this session but were retried (with or without a repair). */
    private var failedAttempts = 0


    val isRunning: Boolean get() = job?.isActive == true

    /**
     * The session's job until it has fully ended, so app teardown can wait for it to leave
     * native code. Not "active": a cancelled job stops being active while its native call runs on.
     */
    val activeJob: Job? get() = job?.takeIf { !it.isCompleted }

    /** Starts a session and returns its Job, so the owner can react when it ends. */
    fun start(scope: CoroutineScope): Job? {
        if (isRunning) return job
        cancelRequested = false
        finishEarlyRequested = false
        _stopRequest.value = null
        return scope.launch(Dispatchers.Default) { runSession() }.also { job = it }
    }

    /**
     * Hard cancel: abort everything, keep whatever is already on disk, return the UI to Idle.
     * Also the "dismiss" action for the Done/Error cards, which are shown after runSession has
     * already returned — without the state reset the overlay would never go away.
     */
    fun cancel() {
        AppLog.i(TAG, "cancel requested (state: ${describe(_state.value)})")
        cancelRequested = true
        if (isRunning) _stopRequest.value = StopRequest.Cancel
        darkGate?.complete(false)
        // abortSession only flags the session; the handle stays valid until destroySession, which
        // runs under handleLock in runSession's finally. Never wait long for the lock here — this
        // runs on the main thread. If a preview fill is holding it, the cancelRequested flag alone
        // still stops the capture loop between frames.
        if (handleLock.tryLock(50, TimeUnit.MILLISECONDS)) {
            try {
                if (engineHandle != 0L) NativeEngine.abortSession(engineHandle)
            } finally {
                handleLock.unlock()
            }
        } else {
            AppLog.d(TAG, "cancel: engine busy, relying on the cancel flag")
        }
        if (!isRunning) setState(CaptureState.Idle)
    }

    /** Dismiss a terminal card (Done/Error) without touching a running session. */
    fun dismissResult() {
        if (!isRunning) setState(CaptureState.Idle)
    }

    /**
     * Carries [previous]'s finished result (Done or Error) over to this engine, which replaces it
     * when the camera is reopened. Without it the result screen vanished the moment the app,
     * back from a screen-off session, reopened the camera underneath it.
     */
    fun adoptResult(previous: CaptureEngine) {
        if (isRunning) return
        val st = previous.state.value
        if (st is CaptureState.Done || st is CaptureState.Error) {
            _decision.value = previous.decision.value
            setState(st)
        }
    }

    /** Stop capturing and stack what we already have. */
    fun finishEarly() {
        AppLog.i(TAG, "finish-early requested")
        finishEarlyRequested = true
        if (isRunning && _stopRequest.value == null) _stopRequest.value = StopRequest.Finish
        darkGate?.complete(false)
    }

    /** UI: user confirmed the lens is covered for dark frames. */
    fun confirmDarksReady() {
        AppLog.i(TAG, "user confirmed the lens is covered")
        darkGate?.complete(true)
    }

    fun skipDarks() {
        AppLog.i(TAG, "user skipped dark frames")
        darkGate?.complete(false)
    }

    /**
     * Live-stack preview for the UI. Safe to call from any thread: the lock keeps the native
     * session alive for the duration of the call, so it cannot be destroyed underneath us.
     */
    fun fillPreview(bitmap: Bitmap): Boolean {
        // Best-effort: if the lock is busy, skip this tick rather than queueing up preview calls.
        if (!handleLock.tryLock()) return false
        return try {
            val h = engineHandle
            h != 0L && NativeEngine.getPreview(h, bitmap)
        } finally {
            handleLock.unlock()
        }
    }

    /** Writes the stack replay's frames, one at a time and never on the capture path. */
    private val replayWriter = Executors.newSingleThreadExecutor { r -> Thread(r, "replay").apply { isDaemon = true } }

    /**
     * Keeps the live stack as it is after [stacked] frames, for the session's "watch it build up"
     * video ([StackReplay]). Best effort: a busy engine or a full disk only costs a replay frame.
     */
    private fun keepReplayFrame(session: File, stacked: Int) {
        val portrait = _rotation.value == 90 || _rotation.value == 270
        replayWriter.execute {
            val bmp = runCatching {
                Bitmap.createBitmap(if (portrait) 720 else 960, if (portrait) 960 else 720, Bitmap.Config.ARGB_8888)
            }.getOrNull() ?: return@execute
            try {
                // After the screen has taken its own picture of this frame (it gives up on a busy
                // engine sooner), then a few tries of its own.
                Thread.sleep(800)
                var filled = false
                for (attempt in 0 until 5) {
                    filled = fillPreview(bmp)
                    if (filled || engineHandle == 0L) break
                    Thread.sleep(150)
                }
                if (filled) StackReplay.saveFrame(session, stacked, bmp)
            } finally {
                bmp.recycle()
            }
        }
    }

    /** Publishes what the session is about to spend time on. See [Busy]. */
    private fun beginBusy(label: String, expectedMs: Long = 0L) {
        _busy.value = Busy(label, SystemClock.elapsedRealtime(), expectedMs)
    }

    /**
     * Runs [block] with the busy clock showing [label]. Deliberately does NOT clear the label
     * afterwards: every long step sets its own, and blanking in between would flicker the
     * indicator once per frame for a whole session. runSession's finally clears it for good.
     */
    private suspend fun <T> step(label: String, expectedMs: Long = 0L, block: suspend () -> T): T {
        beginBusy(label, expectedMs)
        return block()
    }

    /** Native stage names -> what the user should read on the stacking card. */
    private fun stageLabel(stage: String): String = when (stage) {
        "prepare" -> "preparing the stack"
        "align" -> "aligning frames"
        "stack" -> "stacking frames"
        "foreground" -> "compositing the foreground"
        "post" -> "post-processing"
        "write" -> "writing the master files"
        else -> stage
    }

    /** Every state change goes through here, so the log is a complete timeline of the session. */
    private fun setState(next: CaptureState) {
        val prev = _state.value
        _state.value = next
        val repetitive = prev::class == next::class && when (next) {
            is CaptureState.Stacking -> (prev as CaptureState.Stacking).stage == next.stage
            is CaptureState.AwaitingDarkCover -> true
            is CaptureState.WaitingStill -> (prev as CaptureState.WaitingStill).waitingForGyro == next.waitingForGyro
            else -> false
        }
        if (!repetitive) AppLog.i(TAG, "state -> ${describe(next)}")
    }

    private fun describe(st: CaptureState): String = when (st) {
        CaptureState.Idle -> "idle"
        is CaptureState.WaitingStill ->
            if (st.waitingForGyro) "waiting for tripod stillness" else "start delay (${st.delayRemaining}s)"
        CaptureState.Probing -> "probing the sky"
        is CaptureState.Focusing -> "star focus ${st.step}/${st.total} (${st.label})"
        is CaptureState.CapturingLights ->
            "light ${st.current}/${st.total}" + if (st.lastStats != null) " captured" else " exposing"
        is CaptureState.AwaitingDarkCover -> "waiting for the lens to be covered"
        is CaptureState.CapturingDarks -> "dark ${st.current}/${st.total}"
        is CaptureState.Stacking -> "stacking: ${st.stage}"
        is CaptureState.Done -> "DONE: ${st.framesCaptured} frames, " +
            (st.result?.let { "stacked ${it.framesUsed}/${it.framesTotal}" } ?: "RAWs only") +
            (st.warning?.let { " — WARNING: $it" } ?: "")
        is CaptureState.Error -> "ERROR: ${st.message} (${st.framesCaptured} frames on disk)"
    }

    private suspend fun runSession() {
        val s = settingsStore.settings.value
        val cam = controller.cam
        var paths: SessionPaths? = null
        val sessionStart = SystemClock.elapsedRealtime()
        _sessionStartedAtMs.value = sessionStart
        recoveryCount = 0
        failedAttempts = 0
        var captured = 0
        val logMark = AppLog.markSessionStart()
        AppLog.i(TAG, "==== session start: ${s.mode}${if (s.mode == Mode.ASTRO) " ${s.quality}" else ""} ====")
        AppLog.i(TAG, "settings: $s")
        AppLog.i(TAG, "camera ${cam.cameraId}: raw ${cam.rawSize(s.fullResRaw)} (full ${cam.fullRawSize}) · " +
            "exposure ${cam.exposureRangeNs.lower / 1000}µs..${"%.1f".format(cam.maxExposureSeconds)}s · " +
            "ISO ${cam.isoRange.lower}..${cam.isoRange.upper} · cfa ${cam.cfa} · white ${cam.whiteLevel} · " +
            "black ${cam.blackLevelRaster.joinToString(",")} · ${cam.focalLengthMm}mm f/${cam.apertureF} · " +
            "NPF ${"%.1f".format(cam.trailLimitSeconds())}s · manual sensor ${cam.hasManualSensor}")
        AppLog.i(TAG, "condition: ${Diagnostics.snapshot(context)}")
        try {
            // ---- Stillness gate ----
            // One clock for the whole delay: re-arming it per second would restart the ring.
            // Before the first light frame there is nothing to stack, so "Finish now" (from the
            // notification) means the same as Cancel.
            if (s.startDelaySec > 0) beginBusy("start delay", s.startDelaySec * 1000L)
            for (sec in s.startDelaySec downTo 1) {
                setState(CaptureState.WaitingStill(sec, waitingForGyro = false))
                delay(1000)
                if (stopRequested()) return finishCancelled()
            }
            if (motion.hasGyro) {
                setState(CaptureState.WaitingStill(0, waitingForGyro = true))
                motion.stillThreshold = s.gyroStillThreshold
                motion.bumpThreshold = s.gyroBumpThreshold
                // Counting down to the timeout turns the worst case (a phone that never settles)
                // from a silent two-minute hang into "it starts anyway in 40s".
                beginBusy("waiting for tripod stillness", STILL_TIMEOUT_MS)
                val t0 = SystemClock.elapsedRealtime()
                // Polled, not awaited: Cancel is pressed with the phone in hand - never still - and
                // used to sit unanswered for the full two minutes.
                val still = withTimeoutOrNull(STILL_TIMEOUT_MS) {
                    while (!motion.isStill.value && !stopRequested()) delay(100)
                }
                if (stopRequested()) return finishCancelled()
                if (still == null) AppLog.w(TAG, "stillness wait timed out; proceeding anyway")
                else AppLog.i(TAG, "tripod still after ${SystemClock.elapsedRealtime() - t0}ms")
            }

            // Reconfiguring the capture session for the RAW size takes a visible moment.
            val rawSize = cam.rawSize(s.fullResRaw)
            step("preparing the sensor") { prepareSensor(rawSize, s) }

            // ---- Probe frame: exposure decision + white balance lock ----
            setState(CaptureState.Probing)
            val probeExp = minOf(1_000_000_000L, cam.exposureRangeNs.upper)
            val probeIso = 3200.coerceIn(cam.isoRange.lower, cam.isoRange.upper)
            val focusForProbe =
                if (s.focusMode == FocusMode.MANUAL) s.manualFocusDiopters else 0f
            val probeShot = try {
                // The vignetting map is read from this frame only: it is a property of the lens,
                // so one per session is all the stack needs.
                captureFrame(
                    "probe", probeExp, probeIso, focusForProbe, null, rawSize, s,
                    busyLabel = "probe exposure", expectedMs = probeExp / 1_000_000L + 2_000L,
                    wantShadingMap = s.lensShading,
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (stopRequested()) return finishCancelled()
                setState(CaptureState.Error(
                    "The camera could not take the test frame: ${describeFailure(t)}.", null))
                return
            }
            val wbLock: WbLock?
            val ccm: FloatArray
            val probeStats: AnalyzeStats
            // What the sensor actually did, which a HAL may round: the exposure decision scales
            // from the probe, so it scales from the real settings.
            val probeActualExp = probeShot.result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                ?.takeIf { it > 0L } ?: probeExp
            val probeActualIso = probeShot.result.get(CaptureResult.SENSOR_SENSITIVITY)
                ?.takeIf { it > 0 } ?: probeIso
            // Vignetting is a property of the lens, not of the frame, so one map per session is
            // all we need — and the probe frame is the natural place to read it.
            var shading: ShadingMapData? = null
            try {
                val gains = probeShot.result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                val transform = probeShot.result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
                wbLock = if (gains != null) WbLock(gains, transform) else null
                ccm = CamMeta.ccmToFloatArray(transform)
                if (s.lensShading) shading = CamMeta.shadingMap(cam, probeShot.result)
                val plane = probeShot.image.planes[0]
                beginBusy("measuring the sky")
                probeStats = AnalyzeStats.fromJson(
                    NativeEngine.analyzeFrame(
                        plane.buffer, probeShot.image.width, probeShot.image.height,
                        plane.rowStride, analyzeMetaJson(cam, wbLock)
                    )
                )
            } finally {
                probeShot.close()
            }
            AppLog.i(TAG, "probe: sky %.2f%% of white, %d stars, fwhm %.2f px, noise %.4f · wb %s".format(
                probeStats.bgFrac * 100, probeStats.starCount, probeStats.medianFwhm, probeStats.noiseSigma,
                wbLock?.gains?.let { "R%.2f G%.2f/%.2f B%.2f".format(it.red, it.greenEven, it.greenOdd, it.blue) }
                    ?: "none (AWB gave no gains)"))
            if (stopRequested()) return finishCancelled()

            // ---- Exposure decision ----
            val decision = if (s.manualExposure || s.mode == Mode.PRO) {
                AutoExposure.Decision(
                    s.exposureNs.coerceIn(cam.exposureRangeNs.lower, cam.exposureRangeNs.upper),
                    s.iso.coerceIn(cam.isoRange.lower, cam.isoRange.upper),
                    s.effectiveFrames(),
                    "manual: ${s.frameCount}× ISO ${s.iso}",
                )
            } else {
                // Auto: derive the per-frame cap from this lens (NPF) instead of a fixed guess.
                val cap = if (s.autoTrailLimit) cam.trailLimitSeconds() else s.trailCapSec.toDouble()
                AutoExposure.decide(
                    probeStats, probeActualExp, probeActualIso, cam,
                    baseFrames = s.effectiveFrames(), trailCapSec = cap,
                )
            }
            _decision.value = decision
            AppLog.i(TAG, "exposure decision: ${decision.reasoning}")

            // Long modes ignore the frame preset: frames = duration / exposure.
            val requestedFrames = when (s.mode) {
                Mode.TRAILS -> ((s.trailsMinutes * 60.0) / (decision.exposureNs / 1e9)).toInt()
                    .coerceIn(10, 900)
                Mode.METEOR -> ((s.meteorMinutes * 60.0) / (decision.exposureNs / 1e9)).toInt()
                    .coerceIn(10, 900)
                else -> decision.frames
            }
            // Every light frame writes a full-res raw cache file (and optionally a DNG). A 2-hour
            // meteor session would otherwise try to write tens of GB and fill the phone.
            val totalFrames = capFramesToDisk(requestedFrames, rawSize, s.saveDngs)
            if (totalFrames < MIN_FRAMES_TO_STACK) {
                val needMb = (bytesPerFrame(rawSize, s.saveDngs) * MIN_FRAMES_TO_STACK * 100L / 60L +
                    outputReserve(rawSize)) / (1L shl 20)
                AppLog.e(TAG, "not enough storage for a session: ${freeBytes() / (1L shl 20)} MB free")
                setState(CaptureState.Error(
                    "The phone is almost out of storage (${freeBytes() / (1L shl 20)} MB free; a session " +
                        "needs about $needMb MB). Free up some space and try again.", null))
                return
            }

            // ---- Focus ----
            var focusDistance = 0f
            when (s.focusMode) {
                FocusMode.MANUAL -> focusDistance = s.manualFocusDiopters
                FocusMode.INFINITY -> focusDistance = 0f
                FocusMode.AUTO_STAR -> {
                    // The probe frame already measured the sky. In a bright scene (daylight,
                    // indoors, heavy twilight) there are no stars to focus on and the ~40s sweep
                    // is pure waste — skip it instantly instead of letting it fail slowly.
                    val skyTooBright = probeStats.bgFrac > 0.15f
                    // A tripod-mounted lens doesn't move between back-to-back sessions; reuse a
                    // recent good focus instead of spending ~30s re-sweeping. 90-minute window:
                    // beyond that, temperature drift makes the old position untrustworthy - and
                    // within it too, when the phone has warmed or cooled since (out of a pocket
                    // into the cold): the lens's infinity position moves with temperature.
                    val nowTemp = Diagnostics.batteryTempC(context)
                    val saved = if (s.reuseLastFocus) settingsStore.lastFocus(cam.cameraId) else null
                    val tempShift = if (saved?.tempC != null && nowTemp != null)
                        kotlin.math.abs(nowTemp - saved.tempC) else 0f
                    val last = saved?.takeIf {
                        it.ageMs < 90 * 60_000L && tempShift <= FOCUS_REUSE_MAX_TEMP_SHIFT_C
                    }
                    if (saved != null && last == null && saved.ageMs < 90 * 60_000L) {
                        AppLog.i(TAG, "not reusing focus: the phone's temperature moved %.1f°C since it was found"
                            .format(tempShift))
                    }
                    if (skyTooBright) {
                        focusDistance = 0f
                        _focus.value = FocusSweep.Result(
                            0f, 0f, probeStats.starCount, false,
                            "sky too bright for star focus (bg %.0f%%) — using infinity"
                                .format(probeStats.bgFrac * 100),
                        )
                    } else if (last != null) {
                        focusDistance = last.diopters
                        _focus.value = FocusSweep.Result(
                            last.diopters, 0f, 0, true,
                            "reusing focus %.3f dpt from %dm ago".format(
                                last.diopters, last.ageMs / 60_000L),
                        )
                    } else {
                        val res = FocusSweep.run(
                            controller, wbLock, isCancelled = { stopRequested() }
                        ) { stepIdx, total, label ->
                            setState(CaptureState.Focusing(stepIdx, total, label))
                            // Each sweep point is a 2 s exposure plus a native measure. Without a
                            // clock of its own the card sits still ~4 s a point, ten points over.
                            beginBusy("focus $label", FocusSweep.STEP_DURATION_MS)
                        }
                        _focus.value = res
                        focusDistance = res.diopters
                        if (res.ok) settingsStore.saveLastFocus(res.diopters, cam.cameraId, nowTemp)
                    }
                    AppLog.i(TAG, "focus: ${_focus.value?.message}")
                }
            }
            if (stopRequested()) return finishCancelled()

            // ---- Session + native engine ----
            // Which way up the photo is: the phone's attitude now, settled on the tripod before
            // the first frame. The frames stay in sensor orientation; the outputs are turned.
            val sensorOrientation =
                cam.characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            // Aimed within ~9 degrees of the zenith the attitude says nothing: the way the phone
            // was held before it was tilted up is the best guess, and the processing is told it
            // is one (it then looks for a landscape along every edge).
            val deviceNow = motion.deviceOrientationDeg()
            val deviceDeg = deviceNow ?: motion.lastOrientationDeg()
            val rotation = ImageRotation.forBackCamera(sensorOrientation, deviceDeg)
            _rotation.value = rotation
            AppLog.i(TAG, "orientation: sensor $sensorOrientation, phone " +
                (deviceNow?.toString() ?: "near flat (before: ${deviceDeg ?: "unknown"})") +
                " -> photo turned $rotation")
            val stackCfg = s.stackConfig().copy(rotation = rotation, rotationGuessed = deviceNow == null)
            paths = repo.newSession(s.mode)
            // Removed in finally. One still present at the next app start means this session was
            // cut off (crash, kill) - the app then says so instead of showing a plain viewfinder.
            repo.markRunning(paths)
            AppLog.attachSessionLog(File(paths.meta, "session.log"), logMark)
            AppLog.i(TAG, "session folder: ${paths.dir.absolutePath}")
            repo.writeHeader(paths, JSONObject().apply {
                put("mode", s.mode.name)
                if (s.mode == Mode.ASTRO) put("quality", s.quality.name)
                // What this session was stacked with, so a later re-stack starts from the same
                // options (an INSANE foreground blend, a changed hot-pixel setting) and not defaults.
                put("stackConfig", JSONObject(stackCfg.toJson()))
                put("startedAt", System.currentTimeMillis())
                put("rawWidth", rawSize.width)
                put("rawHeight", rawSize.height)
                put("exposureNs", decision.exposureNs)
                put("iso", decision.iso)
                put("plannedFrames", totalFrames)
                put("focusDiopters", focusDistance.toDouble())
                put("focusMode", s.focusMode.name)
                put("reasoning", decision.reasoning)
                put("probe", JSONObject().apply {
                    put("bgFrac", probeStats.bgFrac.toDouble())
                    put("starCount", probeStats.starCount)
                    put("fwhm", probeStats.medianFwhm.toDouble())
                })
                put("device", android.os.Build.MODEL)
                put("appVersion", appVersion())
            })
            repo.writeShadingMap(paths, shading)

            // Dark library: a recent master dark at exactly this exposure and ISO means the
            // "cover the lens" prompt can be skipped entirely. Installed as the session's own
            // master dark, which is where the engine already looks.
            val reusedDarkAgeDays = if (s.reuseDarks && s.stackOnDevice && s.darksEnabled)
                repo.installLibraryDark(
                    paths, cam.cameraId, rawSize, decision.exposureNs, decision.iso,
                    maxAgeDays = s.darkLibraryDays,
                    tempC = Diagnostics.batteryTempC(context),
                )
            else null
            _calibration.value = buildString {
                append(
                    when {
                        reusedDarkAgeDays != null && reusedDarkAgeDays <= 0 -> "darks: reused (today)"
                        reusedDarkAgeDays != null -> "darks: reused (${reusedDarkAgeDays}d old)"
                        else -> "darks: this session"
                    }
                )
                append(" · vignetting: ")
                append(shading?.let { "map %.2fx".format(it.maxGain) } ?: "no map")
            }
            AppLog.i(TAG, "calibration: ${_calibration.value}")

            val config = JSONObject().apply {
                put("sessionDir", paths.dir.absolutePath)
                put("mode", s.mode.ordinal)
                put("width", rawSize.width)
                put("height", rawSize.height)
                put("cfa", cam.cfa)
                put("whiteLevel", cam.whiteLevel)
                put("blackLevel", JSONArray(cam.blackLevelRaster.map { it.toDouble() }))
                // The engine used to delete the frames at the end of the stack, before the JPEG
                // was even written; now they go only once the photo is safely saved (below).
                put("keepCache", true)
                put("livePreview", s.livePreview)
                put("rotation", rotation)
            }.toString()
            handleLock.withLock { engineHandle = NativeEngine.createSession(config) }
            if (engineHandle == 0L) {
                setState(CaptureState.Error("native engine init failed", paths.dir))
                return
            }

            // ---- Lights ----
            val wbRaster = CamMeta.rasterWbGains(wbLock?.gains, cam.cfa)
            val storageCapped = totalFrames < requestedFrames
            // TRAILS wants back-to-back frames: every pause is a visible dash in every star arc.
            val isTrails = s.mode == Mode.TRAILS
            var planned = totalFrames
            var reshoots = 0
            var rejected = 0
            // Clouds and soft stars said while it can still be helped (ASTRO, PRO).
            val skyWatch = SkyWatch()
            val watchSky = s.mode == Mode.ASTRO || s.mode == Mode.PRO
            var darksCaptured = 0
            // Set when the camera could not be brought back mid-burst: the frames already on disk
            // are still stacked rather than thrown away with the session.
            var cameraFailure: String? = null
            // Why the burst ended before its planned count, when the app ended it (not the user).
            var stoppedEarly: String? = null
            // Sky background of the kept light frames, to judge the dark frames against.
            val lightBg = ArrayList<Float>()
            var dngFailures = 0
            val perFrameBytes = bytesPerFrame(rawSize, s.saveDngs)
            val outputReserveBytes = outputReserve(rawSize)
            val burstStart = SystemClock.elapsedRealtime()
            val durationMs = when (s.mode) {
                Mode.TRAILS -> s.trailsMinutes * 60_000L
                Mode.METEOR -> s.meteorMinutes * 60_000L
                else -> 0L
            }
            var i = 0
            while (i < planned) {
                if (cancelRequested || finishEarlyRequested) break
                // Battery floor: finishing with a stacked image beats dying mid-session. Darks and
                // stacking still need headroom, so bail out of the burst at 12% - unless the
                // phone is on a power bank, where the floor only cut every session to 4 frames.
                if (batteryPct() <= 12 && captured >= 4 && !Diagnostics.isPlugged(context)) {
                    AppLog.e(TAG, "battery at ${batteryPct()}% — finishing early with $captured frames")
                    buzz(longArrayOf(0, 60, 120, 60))
                    stoppedEarly = "The battery got low, so the session finished early with $captured frames."
                    finishEarlyRequested = true
                    break
                }
                // Thermal ceiling: at CRITICAL the system is about to throttle hard or shut the
                // app down. Stacking what is already shot beats losing it.
                if (thermal.isCritical && captured >= MIN_FRAMES_TO_STACK) {
                    AppLog.e(TAG, "thermal status ${thermal.label()} — finishing early with $captured frames")
                    buzz(longArrayOf(0, 60, 120, 60))
                    stoppedEarly = "The phone got too hot, so the session finished early with $captured frames."
                    finishEarlyRequested = true
                    break
                }
                val cooldown = if (isTrails) 0f
                else s.cooldownSec + if (thermal.needsCooldown) 2f else 0f
                val frameStart = SystemClock.elapsedRealtime()
                setState(CaptureState.CapturingLights(
                    i + 1, planned, decision.exposureNs, decision.iso, null, rejected,
                    cappedByStorage = storageCapped, interFrameGapMs = (cooldown * 1000).toLong(),
                    frameStartedAtMs = frameStart,
                    skyNote = skyWatch.note,
                ))
                if (i > 0 && cooldown > 0f) {
                    step("cooling the sensor", (cooldown * 1000).toLong()) {
                        delay((cooldown * 1000).toLong())
                    }
                }

                // Storage is checked again before every frame: a long session can outlast the
                // space it started with, and the photo itself still needs room at the end.
                if (captured >= MIN_FRAMES_TO_STACK && freeBytes() < perFrameBytes + outputReserveBytes) {
                    AppLog.e(TAG, "storage nearly full (${freeBytes() / (1L shl 20)} MB free) — " +
                        "finishing early with $captured frames")
                    buzz(longArrayOf(0, 60, 120, 60))
                    stoppedEarly = "The phone is almost out of storage, so the session finished early " +
                        "with $captured frames."
                    finishEarlyRequested = true
                    break
                }

                val exposureMs = decision.exposureNs / 1_000_000L
                val shot = try {
                    captureFrame(
                        "light ${i + 1}/$planned", decision.exposureNs, decision.iso,
                        focusDistance, wbLock, rawSize, s,
                        busyLabel = "exposing frame ${i + 1}/$planned", expectedMs = exposureMs,
                        wantShadingMap = s.lensShading && s.debugShadingEveryFrame,
                        // Per attempt: a knock during a failed attempt or a camera repair says
                        // nothing about the retry that succeeds afterwards.
                        onAttempt = { motion.startWindow() },
                    )
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    motion.endWindow()
                    if (cancelRequested) break
                    cameraFailure = describeFailure(t)
                    AppLog.e(TAG, "camera could not be recovered at light ${i + 1}; " +
                        "$captured good frame(s) on disk — ${controller.dumpState()}", t)
                    repo.writeIssue(paths, "camera failed at light ${i + 1}: $cameraFailure")
                    buzz(longArrayOf(0, 200, 100, 200))
                    break
                }
                val motionWindow = motion.endWindow()
                val peak = motionWindow.peak
                val rms = motionWindow.rms
                val bumped = motionWindow.bumped
                if (motion.hasGyro && motionWindow.samples == 0) {
                    AppLog.w(TAG, "light ${i + 1}: no gyro readings during the exposure — bumps cannot be detected")
                }

                val stats: FrameStats
                var dngName: String? = null
                var ingestMs = 0L
                var dngMs = 0L
                try {
                    val plane = shot.image.planes[0]
                    val metaJson = JSONObject().apply {
                        put("index", i)
                        put("exposureNs", decision.exposureNs)
                        put("iso", decision.iso)
                        put("timestampNs", shot.image.timestamp)
                        put("wbGains", JSONArray(wbRaster.map { it.toDouble() }))
                        put("ccm", JSONArray(ccm.map { it.toDouble() }))
                        put("gyroRms", rms.toDouble())
                        // Dynamic black level: drifts as the sensor warms; engine prefers it over
                        // the static characteristics value when present.
                        shot.result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.let { dyn ->
                            if (dyn.size >= 4) {
                                put("blackLevel", JSONArray(dyn.take(4).map { it.toDouble() }))
                            }
                        }
                    }.toString()
                    // Demosaic + star detect + align + accumulate. Several seconds a frame on
                    // a 12.6 MP sensor - the single longest stretch of dead air in the old UI.
                    beginBusy("aligning & stacking frame ${i + 1}")
                    val t0 = SystemClock.elapsedRealtime()
                    stats = FrameStats.fromJson(
                        NativeEngine.addFrame(
                            engineHandle, plane.buffer, shot.image.width, shot.image.height,
                            plane.rowStride, metaJson
                        )
                    )
                    ingestMs = SystemClock.elapsedRealtime() - t0
                    if (s.saveDngs) {
                        beginBusy("writing DNG ${i + 1}")
                        val t1 = SystemClock.elapsedRealtime()
                        val f = File(paths.lights, "frame_%04d.dng".format(i))
                        if (DngWriter.write(cam, shot, f, rotation)) dngName = f.name
                        else dngFailures++
                        dngMs = SystemClock.elapsedRealtime() - t1
                    }
                } finally {
                    shot.close()
                }
                // A frame counts once it is on disk where it will be used: stacking reads every
                // frame back from its cache file, a RAW-only session keeps the DNG (or the cache).
                // Counting frames that never reached storage made a full disk look like success.
                val durable = if (s.stackOnDevice) stats.saved else stats.saved || dngName != null
                if (!durable) {
                    AppLog.e(TAG, "light ${i + 1}/$planned could not be saved (storage full?) — " +
                        "finishing with $captured frames")
                    repo.writeIssue(paths, "light ${i + 1} could not be saved (storage full?)")
                    buzz(longArrayOf(0, 60, 120, 60))
                    stoppedEarly = "The phone ran out of storage, so the session finished early " +
                        "with $captured frames."
                    finishEarlyRequested = true
                    break
                }
                AppLog.i(TAG, ("light ${i + 1}/$planned ok: exposure+readout ${shot.elapsedMs}ms · ingest ${ingestMs}ms · " +
                    "stars %d fwhm %.2f bg %.2f%% dx %.1f dy %.1f rot %.3f° %s · dng %s · gyro peak %.4f%s · %s").format(
                    stats.starCount, stats.medianFwhm, stats.bgFrac * 100, stats.dx, stats.dy, stats.rotDeg,
                    if (stats.aligned) "aligned (${stats.inliers} inliers)" else "NOT aligned",
                    if (dngName != null) "${dngMs}ms" else if (s.saveDngs) "FAILED" else "off",
                    peak, if (bumped) " BUMPED" else "",
                    Diagnostics.snapshot(context)))

                if (bumped && !isTrails) {
                    // TRAILS keeps bumped frames: a lighten composite is inherently outlier-tolerant,
                    // and rejecting would punch an exposure-length gap into every star arc.
                    NativeEngine.rejectFrame(engineHandle, i, "gyro bump (peak %.3f)".format(peak))
                    rejected++
                    if (s.autoReshoot && reshoots < totalFrames / 4) {
                        planned++
                        reshoots++
                    }
                } else {
                    lightBg.add(stats.bgFrac)
                    if (watchSky) {
                        val before = skyWatch.note
                        val note = skyWatch.add(stats.starCount, stats.medianFwhm)
                        if (note != before) AppLog.i(TAG, "sky watch: ${note ?: "clear again"}")
                    }
                }
                repo.appendFrame(paths, stats, decision.exposureNs, decision.iso, peak, bumped, dngName)
                captured++
                buzz()
                _previewTick.value = _previewTick.value + 1
                if (s.livePreview && !(bumped && !isTrails)) keepReplayFrame(paths.dir, captured - rejected)
                setState(CaptureState.CapturingLights(
                    i + 1, planned, decision.exposureNs, decision.iso, stats, rejected,
                    cappedByStorage = storageCapped, interFrameGapMs = (cooldown * 1000).toLong(),
                    frameStartedAtMs = frameStart,   // same slot: the countdown must not restart
                    skyNote = skyWatch.note,
                ))
                i++
                // Duration modes promise wall-clock minutes, but each frame also spends time on
                // ingest and saving that the exposure-based count left out, so a "20 minute" trail
                // ran well past 20. Once the real frame period is known, the plan shrinks to what
                // still fits (never below the duration modes' floor).
                if (durationMs > 0L && i >= 2) {
                    val now = SystemClock.elapsedRealtime()
                    val periodMs = (now - burstStart) / i
                    val fits = i + ((burstStart + durationMs - now) / periodMs.coerceAtLeast(1L)).toInt()
                    val capped = maxOf(fits, minOf(planned, DURATION_MIN_FRAMES), i)
                    if (capped < planned) {
                        AppLog.i(TAG, "duration ${durationMs / 60_000L} min at ${periodMs}ms a frame: " +
                            "$capped frames fit, not $planned")
                        planned = capped
                    }
                }
            }
            // Hard cancel always returns to Idle — frames already on disk stay there, and the
            // separate "Finish & stack now" button is the path for stack-what-we-have.
            if (cancelRequested) return finishCancelled()
            // "Finish now" landed before a single frame did: nothing to stack, nothing to keep.
            if (captured == 0 && cameraFailure == null) {
                discardIfEmpty = paths.dir
                return finishCancelled()
            }

            // The camera gave up mid-burst. Whatever was captured before it is real data: stack it
            // and say what happened, rather than ending the night with an error and nothing else.
            var warning: String? = stoppedEarly
            if (cameraFailure != null) {
                val enough = if (s.stackOnDevice) captured >= MIN_FRAMES_TO_STACK else captured >= 1
                if (!enough) {
                    setState(CaptureState.Error(
                        "The camera failed on frame ${captured + 1}: $cameraFailure." +
                            if (captured > 0) " Only $captured frame was saved — too few to stack."
                            else " No frames were captured.",
                        paths.dir, captured,
                    ))
                    return
                }
                warning = "The camera failed on frame ${captured + 1} ($cameraFailure). " +
                    (if (s.stackOnDevice) "Stacked" else "Kept") + " the $captured frames shot before it."
            } else if (failedAttempts > 0) {
                warning = addSentence(warning, "The camera failed $failedAttempts time(s) during this " +
                    "session but recovered" +
                    (if (recoveryCount > 0) " after $recoveryCount repair(s)" else "") +
                    " — details are in the log.")
            }
            if (dngFailures > 0) {
                warning = addSentence(warning, "$dngFailures of the DNG files could not be written.")
                repo.writeIssue(paths, "$dngFailures DNG file(s) could not be written")
            }
            // "Finish & stack now" during the first exposure leaves one frame, and the stacker
            // needs two: the frame is kept and that is said plainly, not as a stacking error.
            if (s.stackOnDevice && captured < MIN_FRAMES_TO_STACK) {
                AppLog.w(TAG, "only $captured frame(s) — too few to stack")
                setState(CaptureState.Done(
                    null, null, null, paths.dir, captured,
                    elapsedMs = SystemClock.elapsedRealtime() - sessionStart,
                    warning = addSentence(warning, "Only $captured frame was taken, and stacking needs " +
                        "at least $MIN_FRAMES_TO_STACK."),
                ))
                return
            }

            // ---- Darks ----
            // Not after "Finish & stack now" (the user wants the photo now; the prompt used to
            // appear anyway), not when the app itself ended the burst for battery or heat, and not
            // for fewer frames than can be stacked at all.
            val wantDarks = s.darksEnabled && s.stackOnDevice && s.darkCount > 0 &&
                (s.mode == Mode.ASTRO || s.mode == Mode.PRO) && !stopRequested() &&
                reusedDarkAgeDays == null && cameraFailure == null &&
                captured >= MIN_FRAMES_TO_STACK
            var darksLit = 0
            if (wantDarks) {
                buzz(longArrayOf(0, 60, 150, 60))  // attention: the app needs the lens covered
                // The gate is a user prompt, not work: drop the last step label so it does not
                // keep ticking (and eventually cry "slower than usual") while we wait on a tap.
                _busy.value = null
                val gate = CompletableDeferred<Boolean>()
                darkGate = gate
                val doDarks = run {
                    var confirmed: Boolean? = null
                    for (sec in 60 downTo 1) {
                        setState(CaptureState.AwaitingDarkCover(sec))
                        val r = withTimeoutOrNull(1000) { gate.await() }
                        if (r != null) { confirmed = r; break }
                        if (gate.isCompleted) { confirmed = gate.await(); break }
                    }
                    confirmed ?: false
                }
                darkGate = null
                AppLog.i(TAG, if (doDarks) "shooting ${s.darkCount} dark frames" else "dark frames skipped")
                if (doDarks && !stopRequested()) {
                    // The first dark used to start the instant "Lens covered" was tapped, while a
                    // palm or cloth was still moving into place.
                    setState(CaptureState.CapturingDarks(1, s.darkCount))
                    step("keep the lens covered", DARK_SETTLE_MS) { delay(DARK_SETTLE_MS) }
                    // A dark must be dark. One shot with the lens not quite covered is subtracted
                    // from every light frame - eating the sky - and was filed for two weeks of
                    // later sessions. Judged against tonight's sky: real darks sit far below it.
                    val skyBg = if (lightBg.isEmpty()) 0f else lightBg.sorted()[lightBg.size / 2]
                    val litAbove = maxOf(DARK_LIT_MIN_FRAC, DARK_LIT_SKY_RATIO * skyBg)
                    for (d in 0 until s.darkCount) {
                        if (stopRequested()) break
                        setState(CaptureState.CapturingDarks(d + 1, s.darkCount))
                        val shot = try {
                            captureFrame(
                                "dark ${d + 1}/${s.darkCount}", decision.exposureNs, decision.iso,
                                focusDistance, wbLock, rawSize, s,
                                busyLabel = "dark exposure ${d + 1}/${s.darkCount}",
                                expectedMs = decision.exposureNs / 1_000_000L,
                            )
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            // Darks are a refinement: losing them costs some hot-pixel cleanup,
                            // never the stack itself.
                            AppLog.e(TAG, "dark ${d + 1} failed; stacking with $darksCaptured dark(s)", t)
                            repo.writeIssue(paths, "dark ${d + 1} failed: ${describeFailure(t)}")
                            break
                        }
                        var lit = false
                        try {
                            val plane = shot.image.planes[0]
                            beginBusy("checking dark frame ${d + 1}")
                            val check = AnalyzeStats.fromJson(NativeEngine.analyzeFrame(
                                plane.buffer, shot.image.width, shot.image.height, plane.rowStride,
                                analyzeMetaJson(cam, wbLock),
                            ))
                            lit = check.bgFrac > litAbove
                            if (lit) {
                                darksLit++
                                AppLog.w(TAG, ("dark ${d + 1}/${s.darkCount} is lit: background %.2f%% " +
                                    "of white, limit %.2f%% (sky %.2f%%), %d star-like spots — not used")
                                    .format(check.bgFrac * 100, litAbove * 100, skyBg * 100, check.starCount))
                                repo.writeIssue(paths, "dark ${d + 1} not used: the lens was not fully " +
                                    "covered (background %.2f%% of white)".format(check.bgFrac * 100))
                            } else {
                                val metaJson = JSONObject().apply {
                                    put("index", d)
                                    put("exposureNs", decision.exposureNs)
                                    put("iso", decision.iso)
                                    put("timestampNs", shot.image.timestamp)
                                    put("wbGains", JSONArray(wbRaster.map { it.toDouble() }))
                                    put("ccm", JSONArray(ccm.map { it.toDouble() }))
                                    put("gyroRms", 0.0)
                                    // The dark's own black level, as for a light: the master
                                    // dark is subtracted relative to it, not the light's.
                                    shot.result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.let { dyn ->
                                        if (dyn.size >= 4) {
                                            put("blackLevel", JSONArray(dyn.take(4).map { it.toDouble() }))
                                        }
                                    }
                                }.toString()
                                beginBusy("ingesting dark frame ${d + 1}")
                                NativeEngine.addDarkFrame(
                                    engineHandle, plane.buffer, shot.image.width, shot.image.height,
                                    plane.rowStride, metaJson
                                )
                                // Counted here, not at the top of the loop: only frames that
                                // actually reached the accumulator belong in the master dark.
                                darksCaptured++
                                if (s.saveDngs) {
                                    DngWriter.write(cam, shot, File(paths.darks, "dark_%04d.dng".format(d)), rotation)
                                }
                            }
                        } finally {
                            shot.close()
                        }
                        if (!lit) AppLog.i(TAG, "dark ${d + 1}/${s.darkCount} ok (${shot.elapsedMs}ms)")
                    }
                    // One dark adds its own noise to every frame; that is worse than none.
                    if (darksCaptured in 1 until MIN_DARKS) {
                        AppLog.w(TAG, "only $darksCaptured usable dark frame(s) — stacking without darks")
                        NativeEngine.discardDarks(engineHandle)
                        darksCaptured = 0
                    }
                    if (darksLit > 0) {
                        warning = addSentence(warning,
                            "$darksLit of ${s.darkCount} dark frames were not dark (the lens was not fully " +
                                "covered), so " + if (darksCaptured > 0) "only the dark ones were used."
                            else "the photo was stacked without dark frames.")
                    }
                }
            }

            // ---- Stack or finish with RAWs ----
            if (!s.stackOnDevice) {
                setState(CaptureState.Done(
                    null, null, null, paths.dir, captured,
                    elapsedMs = SystemClock.elapsedRealtime() - sessionStart,
                    warning = warning,
                ))
                return
            }
            setState(CaptureState.Stacking("prepare", 0))
            beginBusy("preparing the stack")
            val optionsJson = stackCfg.toJson()
            var lastStage = ""
            val stackT0 = SystemClock.elapsedRealtime()
            AppLog.i(TAG, "stacking $captured frames (+$darksCaptured darks): $optionsJson")
            val resultJson = NativeEngine.finalizeStack(
                engineHandle, optionsJson,
                object : NativeEngine.ProgressCallback {
                    override fun onProgress(stage: String, percent: Int) {
                        setState(CaptureState.Stacking(stage, percent))
                        // Restart the clock on every stage change, so a stage that stalls shows
                        // its own elapsed time instead of hiding inside the session total.
                        if (stage != lastStage) {
                            lastStage = stage
                            beginBusy(stageLabel(stage))
                        }
                    }
                })
            AppLog.i(TAG, "stack finished in ${SystemClock.elapsedRealtime() - stackT0}ms: $resultJson")
            val result = EngineResult.fromJson(resultJson)
            if (!result.ok) {
                setState(if (cancelRequested) CaptureState.Idle
                else CaptureState.Error("stacking failed: ${result.error}", paths.dir, captured))
                return
            }
            repo.writeResult(paths, JSONObject(resultJson))
            if (result.tiffPath.isEmpty()) {
                // Not fatal - the photo does not depend on it - but never silent.
                AppLog.e(TAG, "the linear master TIFF could not be written")
                repo.writeIssue(paths, "the linear master TIFF could not be written (storage full?)")
            }
            // The engine writes cache/master_dark.sraw during finalize; file it so the next
            // session at these settings can skip shooting darks altogether. Only a set in which
            // every dark was dark: one lit frame says the covering was not trustworthy.
            if (darksCaptured >= MIN_DARKS && darksLit == 0) {
                repo.saveLibraryDark(
                    paths, cam.cameraId, rawSize, decision.exposureNs, decision.iso,
                    tempC = Diagnostics.batteryTempC(context),
                )
            }

            // ---- Save final image ----
            val totalExposure = result.framesUsed * (decision.exposureNs / 1e9)
            val recipe = "StarCam ${s.mode.name}: ${result.framesUsed}x" +
                com.wm.starcam.util.Fmt.exposure(decision.exposureNs) +
                " ISO ${decision.iso}, winsorized sigma-clip stack" +
                if (result.foreground.isNotEmpty()) ", foreground ${result.foreground}" else ""
            // The bar hit 100% here but a 12.6 MP JPEG encode + gallery insert still had to
            // run, which read as a freeze on the finished-looking card.
            setState(CaptureState.Stacking("saving", 99))
            beginBusy("saving JPEG to the gallery")
            val saved = withContext(Dispatchers.IO) {
                MediaSaver.saveFinal(
                    context, File(result.finalRgbPath), result.width, result.height,
                    paths.finalJpg, totalExposure, decision.iso,
                    focalMm = cam.focalLengthMm, fNumber = cam.apertureF, recipe = recipe,
                )
            }
            if (result.singlePath.isNotEmpty()) {
                MediaSaver.saveComparisonFrame(File(result.singlePath), result.singleWidth,
                    result.singleHeight, File(paths.out, SINGLE_FRAME_JPG))
            }
            if (saved.jpegWritten) {
                AppLog.i(TAG, "saved ${paths.finalJpg.name} (gallery: ${saved.galleryUri ?: "FAILED"})")
                // Only now, with the photo on disk, may the frames go (when the user keeps no
                // working files): a failed save leaves them for a re-stack.
                if (!s.keepRawCache) repo.deleteCacheFrames(paths.dir)
                buzz(longArrayOf(0, 40, 100, 40, 100, 80))  // session complete
            } else {
                AppLog.e(TAG, "the final JPEG could not be written — keeping the frames for a re-stack")
                repo.writeIssue(paths, "the final JPEG could not be written (storage full?)")
                buzz(longArrayOf(0, 200, 100, 200))  // not the success pattern
            }
            setState(CaptureState.Done(
                result, paths.finalJpg, saved.galleryUri, paths.dir, captured,
                elapsedMs = SystemClock.elapsedRealtime() - sessionStart,
                warning = warning,
            ))
        } catch (t: Throwable) {
            AppLog.e(TAG, "session failed — ${controller.dumpState()}", t)
            setState(CaptureState.Error(t.message ?: "unknown failure (${t.javaClass.simpleName})", paths?.dir, captured))
        } finally {
            _busy.value = null
            _sessionStartedAtMs.value = 0L
            _calibration.value = null
            _stopRequest.value = null
            // Under handleLock so an in-flight fillPreview/cancel cannot be dereferencing the
            // session while we delete it.
            handleLock.withLock {
                if (engineHandle != 0L) {
                    NativeEngine.destroySession(engineHandle)
                    engineHandle = 0
                }
            }
            controller.endExclusive()
            runCatching { controller.startPreview(boostExposure = false) }
            AppLog.i(TAG, "==== session end after ${(SystemClock.elapsedRealtime() - sessionStart) / 1000}s: " +
                "${describe(_state.value)} · $captured light frame(s) · $failedAttempts failed attempt(s), " +
                "$recoveryCount camera repair(s) ====")
            AppLog.detachSessionLog()
            paths?.let { repo.clearRunning(it) }
            discardIfEmpty?.let { repo.deleteIfEmpty(it) }
            discardIfEmpty = null
        }
    }

    /** Joins two user-facing sentences, either of which may be absent. */
    private fun addSentence(text: String?, sentence: String): String =
        if (text.isNullOrEmpty()) sentence else "$text $sentence"

    /**
     * Gets the camera ready for the burst: the RAW stream at [rawSize], the viewfinder's repeating
     * request stopped. A camera that was lost before the session started gets one reopen.
     */
    private suspend fun prepareSensor(rawSize: Size, s: AppSettings) {
        controller.explicitFrameDuration = s.debugFrameDuration
        controller.freshSessionAfterLong = s.freshSessionAfterLong
        controller.verbose = s.verboseCameraLog
        controller.stopPreview()
        controller.beginExclusive("session")
        try {
            controller.prepareCapture(rawSize, withPreview = s.debugPreviewInSession)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            AppLog.e(TAG, "preparing the sensor failed — reopening the camera", t)
            controller.recover(3, rawSize)
        }
    }

    /**
     * One RAW frame, bringing the camera back if it has to.
     *
     * Why this exists: in the field a light frame failed with Camera2 "reason 0", the immediate
     * retry failed identically, and the session ended with nothing to show for it. Retrying into
     * the same broken state is pointless, so before each retry the repair escalates — abort and
     * drain, then a fresh capture session, then a full camera reopen (see
     * [CameraController.recover]) — and every step is logged so the next failure explains itself.
     * With recovery switched off (Debug) it falls back to the old single immediate retry.
     */
    private suspend fun captureFrame(
        label: String,
        exposureNs: Long,
        iso: Int,
        focus: Float,
        wb: WbLock?,
        rawSize: Size,
        s: AppSettings,
        busyLabel: String,
        expectedMs: Long,
        wantShadingMap: Boolean = false,
        onAttempt: () -> Unit = {},
    ): CapturedRaw {
        val repairs = if (s.cameraRecovery) intArrayOf(1, 2, 3) else intArrayOf(0)
        var attempt = 0
        while (true) {
            try {
                val shot = step(if (attempt == 0) busyLabel else "$busyLabel (retry $attempt)", expectedMs) {
                    onAttempt()
                    controller.captureRaw(exposureNs, iso, focus, wb, label, wantShadingMap)
                }
                if (attempt > 0) AppLog.w(TAG, "$label succeeded on retry $attempt — the camera recovered")
                return shot
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e(TAG, "$label: attempt ${attempt + 1} failed — ${t.message}")
                // Cancel and "Finish & stack now" must not wait out three more repairs and
                // exposures — minutes, when each failure only arrives at the end of a 22 s frame.
                if (attempt >= repairs.size || stopRequested()) throw t
                val level = repairs[attempt]
                attempt++
                failedAttempts++
                if (level > 0) {
                    recoveryCount++
                    step("repairing the camera ($attempt/${repairs.size})") {
                        try {
                            controller.recover(level, rawSize)
                        } catch (r: Throwable) {
                            if (r is CancellationException) throw r
                            AppLog.e(TAG, "camera repair level $level failed", r)
                        }
                    }
                }
                if (stopRequested()) throw t
            }
        }
    }

    private fun stopRequested() = cancelRequested || finishEarlyRequested

    /** A camera exception in words a person can act on. */
    private fun describeFailure(t: Throwable): String = when (t) {
        is CaptureFailedException ->
            if (t.reason == CaptureFailure.REASON_FLUSHED) "the camera cancelled the exposure"
            else "the camera reported an error (Camera2 failure reason ${t.reason})"
        is CaptureTimeoutException -> "the camera stopped responding — ${t.message}"
        is CameraLostException -> "the camera was taken away — ${t.message}"
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun finishCancelled() {
        AppLog.i(TAG, "session cancelled")
        setState(CaptureState.Idle)
    }

    private fun appVersion(): String = runCatching {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        "${p.versionName} (${p.longVersionCode})"
    }.getOrDefault("?")

    /** Short buzz so progress is felt without looking at the screen (gloves, pocket). */
    private fun buzz(pattern: LongArray = longArrayOf(0, 30)) {
        if (!settingsStore.settings.value.haptics) return
        runCatching {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    /** Battery percentage, or 100 when unknown (never end a session on a bad reading). */
    private fun batteryPct(): Int = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(100).let { if (it <= 0) 100 else it }

    /** Bytes one light frame takes: its .sraw (w*h*2 + header) plus, when enabled, a DNG. */
    private fun bytesPerFrame(rawSize: android.util.Size, saveDngs: Boolean): Long =
        rawSize.width.toLong() * rawSize.height.toLong() * 2L * (if (saveDngs) 2L else 1L) + 4096L

    /**
     * Room the end of a session needs: master dark, linear TIFF, final RGB and JPEG, about 12
     * bytes per RAW pixel (150 MB at 12.6 MP).
     */
    private fun outputReserve(rawSize: android.util.Size): Long =
        rawSize.width.toLong() * rawSize.height.toLong() * 12L

    private fun freeBytes(): Long = runCatching { repo.sessionsRoot().usableSpace }.getOrDefault(Long.MAX_VALUE)

    /**
     * Limits the planned frame count to what the storage can actually hold, leaving headroom
     * (60% of the free space at most). No longer rounded up to 4: four frames the disk cannot
     * hold fail just the same, only later.
     */
    private fun capFramesToDisk(requested: Int, rawSize: android.util.Size, saveDngs: Boolean): Int {
        val perFrame = bytesPerFrame(rawSize, saveDngs)
        val usable = runCatching { repo.sessionsRoot().usableSpace }.getOrDefault(0L)
        if (usable <= 0L || perFrame <= 0L) return requested
        val budget = (usable * 60L) / 100L  // never consume more than 60% of free space
        val affordable = (budget / perFrame).toInt().coerceAtLeast(0)
        if (affordable < requested) {
            AppLog.e(
                TAG,
                "storage limit: ${requested} frames would need " +
                    "${requested * perFrame / (1L shl 20)} MB but only " +
                    "${usable / (1L shl 20)} MB is free — capturing $affordable frames instead"
            )
            return affordable
        }
        return requested
    }

    private fun analyzeMetaJson(cam: CamInfo, wb: WbLock?): String = JSONObject().apply {
        put("cfa", cam.cfa)
        put("whiteLevel", cam.whiteLevel)
        put("blackLevel", JSONArray(cam.blackLevelRaster.map { it.toDouble() }))
        put("wbGains", JSONArray(CamMeta.rasterWbGains(wb?.gains, cam.cfa).map { it.toDouble() }))
    }.toString()

    companion object {
        private const val TAG = "session"

        /** How long the gyro gate waits for stillness before starting the session anyway. */
        const val STILL_TIMEOUT_MS = 120_000L

        /** The native stacker refuses fewer than this many usable frames. */
        private const val MIN_FRAMES_TO_STACK = 2

        /** TRAILS and METEOR plan at least this many frames, whatever the duration (see below). */
        private const val DURATION_MIN_FRAMES = 10

        /** Fewer usable darks than this are dropped: a single dark adds its own noise to every frame. */
        private const val MIN_DARKS = 2

        /** Pause between "Lens covered" and the first dark, for the hand or cap to settle. */
        private const val DARK_SETTLE_MS = 3_000L

        /**
         * A dark frame whose background is above this share of the night's sky background, or
         * above [DARK_LIT_MIN_FRAC] of white, saw light. Real dark current at 30 s sits far below
         * either; a lens not covered, or covered with gaps, does not.
         */
        private const val DARK_LIT_SKY_RATIO = 0.35f
        private const val DARK_LIT_MIN_FRAC = 0.005f

        /** A reused focus is trusted only while the phone stays within this of its temperature then. */
        private const val FOCUS_REUSE_MAX_TEMP_SHIFT_C = 5f
    }
}
