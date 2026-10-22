package com.wm.starcam

import android.app.Application
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wm.starcam.camera.CamInfo
import com.wm.starcam.camera.CamMeta
import com.wm.starcam.camera.CameraController
import com.wm.starcam.camera.CameraTest
import com.wm.starcam.camera.CaptureEngine
import com.wm.starcam.camera.SurfaceGoneException
import com.wm.starcam.camera.CaptureState
import com.wm.starcam.engine.Restacker
import com.wm.starcam.engine.StackConfig
import com.wm.starcam.sensors.MotionMonitor
import com.wm.starcam.sensors.ThermalMonitor
import com.wm.starcam.session.Mode
import com.wm.starcam.session.SessionRepository
import com.wm.starcam.session.SessionSummary
import com.wm.starcam.session.SettingsStore
import com.wm.starcam.util.AppLog
import com.wm.starcam.util.DebugBundle
import com.wm.starcam.util.Fmt
import com.wm.starcam.util.MediaSaver
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Owns camera + engine lifecycles; one per process. */
class StarCamViewModel(app: Application) : AndroidViewModel(app) {

    val settingsStore = SettingsStore(app)
    val repo = SessionRepository(app)
    val motion = MotionMonitor(app)
    val thermal = ThermalMonitor(app)

    private val _cameraError = MutableStateFlow<String?>(null)
    val cameraError: StateFlow<String?> = _cameraError

    private val _camInfo = MutableStateFlow<CamInfo?>(null)
    val camInfo: StateFlow<CamInfo?> = _camInfo

    /** All RAW-capable back cameras (main first) for the lens picker; filled once at startup. */
    private val _lenses = MutableStateFlow<List<CamInfo>>(emptyList())
    val lenses: StateFlow<List<CamInfo>> = _lenses

    var controller: CameraController? = null
        private set

    /**
     * The engine for the open camera, replaced on every lens switch or reopen. Observable: the
     * capture screen read a plain field once, so after a cold start or a lens switch it kept
     * watching a stand-in (or the old engine) and a running session never showed its screens.
     */
    private val _engine = MutableStateFlow<CaptureEngine?>(null)
    val engineFlow: StateFlow<CaptureEngine?> = _engine
    var engine: CaptureEngine?
        get() = _engine.value
        private set(value) {
            _engine.value = value
        }

    /** A session that was cut off by a crash or kill, found at start; null once acknowledged. */
    private val _interrupted = MutableStateFlow<SessionSummary?>(null)
    val interrupted: StateFlow<SessionSummary?> = _interrupted

    fun dismissInterrupted() {
        _interrupted.value = null
    }

    init {
        // No session can be running in a fresh view model, so any "running" marker on disk is
        // from a process that died mid-session.
        viewModelScope.launch(Dispatchers.IO) {
            _interrupted.value = repo.collectInterrupted().firstOrNull()
            repo.hideFromGallery()
        }
    }

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    /** True between the OS taking the camera away and a successful reopen. */
    private val _cameraLost = MutableStateFlow(false)
    val cameraLost: StateFlow<Boolean> = _cameraLost

    /** The Diagnostics screen's camera test; see [CameraTest]. */
    private val _testStatus = MutableStateFlow(CameraTest.Status())
    val testStatus: StateFlow<CameraTest.Status> = _testStatus
    private var testJob: Job? = null
    @Volatile private var testCancel = false

    @Volatile private var pendingSurface: Surface? = null
    private var permissionGranted = false

    /**
     * Bumped by every session and camera test. Their wind-down stops the foreground service
     * only if nothing newer has started since — otherwise a session started within the old
     * one's last second would lose its service (and the camera, once the screen goes off).
     */
    @Volatile private var serviceGeneration = 0

    /** Serializes camera teardown/open: two quick lens taps must never race two controllers. */
    private val cameraMutex = Mutex()

    /** A session or a camera test owns the camera; nothing else may reconfigure it meanwhile. */
    private val cameraBusy: Boolean
        get() = engine?.isRunning == true || testJob?.isActive == true

    /** No screen of the app is visible (home, recents, another app on top, swiped away). */
    @Volatile private var uiHidden = false

    /**
     * The app left the screen: the camera is let go unless a session or test is using it. It used
     * to stay open in the background until the system revoked it, with the camera-in-use
     * indicator lit for an app nobody was looking at. [onUiShown] reopens it.
     */
    fun onUiHidden() {
        uiHidden = true
        if (cameraBusy || controller == null) return
        viewModelScope.launch(Dispatchers.Default) { releaseCameraIfIdle("app in the background") }
    }

    fun onUiShown() {
        if (!uiHidden) return
        uiHidden = false
        if (permissionGranted && controller == null && !cameraBusy) {
            val id = _camInfo.value?.cameraId ?: return
            openCamera(id, force = true, what = "Resume")
        }
    }

    private suspend fun releaseCameraIfIdle(why: String) = cameraMutex.withLock {
        if (!uiHidden || cameraBusy) return@withLock
        val ctrl = controller ?: return@withLock
        AppLog.i(TAG, "$why — releasing the camera")
        _ready.value = false
        controller = null
        runCatching { ctrl.close() }
    }

    fun onPermissionGranted() {
        if (permissionGranted) return
        permissionGranted = true
        AppLog.i(TAG, "camera permission granted — starting sensors and camera")
        motion.start()
        thermal.start()
        viewModelScope.launch(Dispatchers.Default) {
            val app = getApplication<Application>()
            // Honor a persisted lens choice; fall back to the main sensor when it fails
            // (OS update, invalid id) so the app never boots into a dead camera.
            val wantedLens = settingsStore.settings.value.lensId
            val cam = (if (wantedLens.isNotEmpty()) CamMeta.pickCamera(app, wantedLens) else null)
                ?: CamMeta.pickMainCamera(app)
            if (cam == null) {
                AppLog.e(TAG, "no RAW-capable back camera found")
                _cameraError.value =
                    "This device does not expose a RAW-capable back camera to apps — StarCam cannot run."
                return@launch
            }
            _camInfo.value = cam
            _lenses.value = CamMeta.listBackCameras(app)
            AppLog.i(TAG, "using camera ${cam.cameraId}; lenses: " +
                _lenses.value.joinToString { "${it.cameraId}=${it.focalLengthMm}mm" })
            if (!cam.hasManualSensor) {
                AppLog.e(TAG, "MANUAL_SENSOR not exposed — captures may ignore manual exposure!")
            }
            val ctrl = newController(cam)
            try {
                ctrl.open()
                controller = ctrl
                engine = CaptureEngine(app, ctrl, settingsStore, motion, thermal, repo)
                _ready.value = true
                _cameraLost.value = false
                pendingSurface?.let { attachSurface(it) }
            } catch (t: Throwable) {
                AppLog.e(TAG, "camera open failed", t)
                // Its camera thread would otherwise outlive it; every retry left one more behind.
                if (controller !== ctrl) ctrl.close()
                _cameraError.value = "Camera open failed: ${t.message}"
            }
        }
    }

    private fun newController(cam: CamInfo): CameraController =
        CameraController(getApplication(), cam).apply {
            onDeviceLost = { reason -> onCameraLost(reason) }
            onDeviceReopened = {
                // A session's own recovery brought the camera back: drop the "lost" card.
                AppLog.i(TAG, "camera reopened by recovery")
                _ready.value = true
                _cameraLost.value = false
                _cameraError.value = null
            }
        }

    /** Called by the SurfaceView callback whenever a preview surface (re)appears. */
    fun attachSurface(surface: Surface) {
        if (!surface.isValid) return
        pendingSurface = surface
        // Coming back to a camera the system took while the app was in the background (common
        // after a session that ended with the screen off): reopen it instead of showing "Camera
        // problem" over the result. openCamera re-attaches this surface when it is done.
        if (_cameraLost.value && !cameraBusy && controller != null) {
            AppLog.i(TAG, "preview surface back while the camera is lost — reopening it")
            reopenCamera()
            return
        }
        val ctrl = controller ?: return
        // Reconfiguring for a new preview surface also rebuilds the RAW ImageReader. Doing that
        // mid-session would pull the reader out from under an in-flight exposure, so a screen
        // that wakes during a capture is ignored here; startSession re-attaches when it ends.
        if (cameraBusy) {
            AppLog.i(TAG, "preview surface returned mid-session; reconfigure deferred until it ends")
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val s = settingsStore.settings.value
                ctrl.configureSession(surface, ctrl.cam.rawSize(s.fullResRaw))
                if (!cameraBusy) ctrl.startPreview(boostExposure = false)
                _cameraError.value = null
            } catch (t: Throwable) {
                if (t is SurfaceGoneException || !surface.isValid) {
                    // Screen slept or the app was backgrounded mid-setup. surfaceCreated will fire
                    // again when we come back — not a failure worth alarming the user about.
                    AppLog.d(TAG, "preview surface went away during setup; will retry when it returns")
                } else {
                    AppLog.e(TAG, "session configure failed", t)
                    _cameraError.value = "Camera session failed: ${t.message}"
                }
            }
        }
    }

    /** Called when the SurfaceView tears its surface down (screen off, app backgrounded). */
    fun detachSurface() {
        pendingSurface = null
        AppLog.i(TAG, "preview surface destroyed${if (cameraBusy) " (while the camera is busy)" else ""}")
        runCatching { controller?.onPreviewSurfaceDestroyed() }
    }

    fun setFramingBoost(enabled: Boolean) {
        val ctrl = controller ?: return
        viewModelScope.launch(Dispatchers.Default) {
            if (!cameraBusy) ctrl.startPreview(boostExposure = enabled)
        }
    }

    /**
     * Switches to another back camera (lens picker). Ignored while a session is running: the
     * engine owns the controller for the whole session. The preview surface is kept and
     * re-attached so the viewfinder returns as soon as the new camera is up.
     */
    fun switchLens(id: String) {
        if (cameraBusy) return
        openCamera(id, force = false, what = "Lens switch")
    }

    /**
     * Reopens the current lens after the OS took the camera away. Forced, because the dead
     * controller still reports the same camera id and the plain lens-switch path would see that
     * as "already on this lens" and do nothing.
     */
    fun reopenCamera() {
        val id = _camInfo.value?.cameraId ?: settingsStore.settings.value.lensId
        if (id.isEmpty()) return
        // Tearing the controller down under a running session or test would cancel it (losing
        // "stack what we have") or leave it waiting on a dead camera thread. A session repairs
        // the camera itself; the button is hidden meanwhile and this is only the backstop.
        if (cameraBusy) {
            AppLog.w(TAG, "reopen ignored: a session or camera test owns the camera")
            return
        }
        AppLog.i(TAG, "user asked to reopen the camera")
        openCamera(id, force = true, what = "Reopen")
    }

    private fun openCamera(id: String, force: Boolean, what: String) {
        // The shutter goes off now, not once the coroutine below runs: a session started in
        // between would have had its camera closed under it.
        val wasReady = _ready.value
        _ready.value = false
        viewModelScope.launch(Dispatchers.Default) {
            // Serialized: a second tap waits for the first teardown/open to finish instead of
            // racing it (which could leak a CameraDevice + its thread forever).
            cameraMutex.withLock {
                if (cameraBusy) {
                    AppLog.w(TAG, "${what.lowercase()} skipped: a session or camera test owns the camera")
                    _ready.value = wasReady
                    return@withLock
                }
                if (!force && controller?.cam?.cameraId == id) {  // already there
                    _ready.value = wasReady
                    return@withLock
                }
                val app = getApplication<Application>()
                var tornDown = false
                var fresh: CameraController? = null
                runCatching {
                    // Validate the target before tearing anything down: a bad id must not leave
                    // the app with no camera at all.
                    val cam = CamMeta.pickCamera(app, id)
                        ?: throw IllegalStateException("camera $id is not usable for RAW capture")
                    AppLog.i(TAG, "$what: opening camera $id")
                    settingsStore.update { it.copy(lensId = id) }
                    tornDown = true
                    runCatching { controller?.close() }
                    controller = null
                    _camInfo.value = cam
                    val ctrl = newController(cam)
                    fresh = ctrl
                    ctrl.open()
                    controller = ctrl
                    // Swapped only now, and carrying over a result on screen (a session that
                    // finished while the system had the camera): it used to vanish as the camera
                    // came back underneath it. Until then the shutter is off (not ready).
                    val previous = engine
                    engine = CaptureEngine(app, ctrl, settingsStore, motion, thermal, repo).also { e ->
                        previous?.let { e.adoptResult(it) }
                    }
                    _ready.value = true
                    _cameraError.value = null
                    _cameraLost.value = false
                    pendingSurface?.let { attachSurface(it) }
                }.onFailure { t ->
                    AppLog.e(TAG, "${what.lowercase()} failed", t)
                    // A controller that never became the current one is closed with its thread.
                    fresh?.let { if (controller !== it) runCatching { it.close() } }
                    // Nothing was torn down (a bad id): the old camera is still there.
                    if (!tornDown) _ready.value = wasReady
                    _cameraError.value = "$what failed: ${t.message}"
                }
            }
        }
    }

    /**
     * The camera was revoked while we held it. Nothing this object points at is usable any more,
     * so stop claiming to be ready and offer the way back instead of letting every later call
     * fail with an opaque Camera2 message.
     *
     * A running session is deliberately left alone: its own recovery tries to reopen the camera,
     * and if that fails it stacks what it has and reports an Error with its session directory.
     */
    private fun onCameraLost(reason: String) {
        AppLog.e(TAG, "camera lost: $reason (session running: ${engine?.isRunning == true})")
        _ready.value = false
        _cameraLost.value = true
        _cameraError.value = reason
    }

    fun startSession() {
        val e = engine ?: return
        // While the camera is (re)opening, the engine is still the previous one, whose camera
        // is closed.
        if (!_ready.value) {
            AppLog.w(TAG, "start ignored: the camera is not ready")
            return
        }
        // A second tap would otherwise wire a second notifier loop onto the same session.
        if (cameraBusy) return
        // Two full-resolution stacks at once can run the phone out of memory; the re-stack is
        // a minute at most and the screen says so.
        if (_restack.value?.running == true) return
        val app = getApplication<Application>()
        val job = e.start(viewModelScope) ?: return
        val generation = ++serviceGeneration
        // A session must outlive the app being on screen: Android revokes camera access to
        // background apps, and the CPU may suspend between frames with the screen off.
        SessionBeacon.onFinish = { e.finishEarly() }
        SessionBeacon.onCancel = { e.cancel() }
        CaptureService.start(app)
        viewModelScope.launch {
            // 1 Hz is as fast as a notification can usefully change, and slow enough that it
            // costs nothing next to a 30 s exposure.
            while (job.isActive) {
                SessionBeacon.update(beaconStatus(e))
                delay(1000)
            }
            job.join()
            windDown(generation)
        }
    }

    /** Ends what a session or test started, unless a newer one has taken over meanwhile. */
    private fun windDown(generation: Int) {
        if (generation != serviceGeneration || cameraBusy) {
            AppLog.d(TAG, "wind-down skipped: a newer session or test is running")
            return
        }
        SessionBeacon.clearActions()
        CaptureService.stop(getApplication())
        restorePreview()
        // Finished with nobody looking: let the camera go now rather than when the app returns.
        if (uiHidden) viewModelScope.launch(Dispatchers.Default) { releaseCameraIfIdle("session over in the background") }
    }

    /**
     * A surface that came back while the camera was busy was deliberately ignored, and recovery
     * may have left a RAW-only session behind; either way the viewfinder is rebuilt now.
     */
    private fun restorePreview() {
        pendingSurface?.let { if (it.isValid) attachSurface(it) }
    }

    // ------------------------------------------------------------------ re-stack

    /** The one re-stack that may run at a time (null: none this launch). */
    data class Restack(
        val dir: File,
        val stage: String = "starting",
        val percent: Int = 0,
        val finished: Boolean = false,
        val error: String? = null,
        /** Finished, but the gallery copy could not be made. */
        val galleryFailed: Boolean = false,
    ) {
        val running: Boolean get() = !finished
    }

    private val _restack = MutableStateFlow<Restack?>(null)
    val restack: StateFlow<Restack?> = _restack

    /** Forgets a finished re-stack of [dir] once its screen is left, so it is reported once. */
    fun clearRestack(dir: File) {
        val r = _restack.value
        if (r != null && r.finished && r.dir == dir) _restack.compareAndSet(r, null)
    }

    /**
     * Re-stacks [dir] from its kept frames with [adjust] applied to the options it was shot with,
     * saves the new photo (JPEG + gallery) and records the result in session.json. Runs here and
     * not in the screen: leaving the screen used to throw the finished stack away and let a second
     * re-stack start on the same files. Refused while a re-stack, a session or a camera test runs.
     */
    fun startRestack(dir: File, adjust: (StackConfig) -> StackConfig, recipe: String): Boolean {
        if (_restack.value?.running == true || cameraBusy) return false
        _restack.value = Restack(dir)
        viewModelScope.launch(Dispatchers.Default) {
            val outcome = runCatching {
                val header = repo.readSessionJson(dir)?.optJSONObject("header")
                val mode = runCatching { Mode.valueOf(header?.optString("mode") ?: "") }
                    .getOrDefault(Mode.ASTRO)
                // Start from what the session was stacked with; sessions from before that was
                // recorded fall back to today's settings, without a foreground blend.
                val base = header?.optJSONObject("stackConfig")?.let { StackConfig.fromJson(it) }
                    ?: settingsStore.settings.value.stackConfig().copy(foregroundComposite = false)
                // The live stack left out the frames the gyro saw bumped (TRAILS keeps them).
                val bumped = HashSet<Int>()
                if (mode != Mode.TRAILS) {
                    repo.readSessionJson(dir)?.optJSONArray("frames")?.let { frames ->
                        for (k in 0 until frames.length()) {
                            val f = frames.optJSONObject(k) ?: continue
                            if (f.optBoolean("bumped")) bumped += f.optInt("index", -1)
                        }
                    }
                }
                val cfg = adjust(base)
                AppLog.i(TAG, "re-stack ${dir.name}: ${repo.cacheFrames(dir).size} frames, " +
                    "${bumped.size} bumped left out, options ${cfg.toJson()}")
                val result = Restacker.restack(dir, repo.cacheFrames(dir), cfg, mode.ordinal, bumped) {
                    stage, percent -> _restack.value = _restack.value?.copy(stage = stage, percent = percent)
                }
                if (!result.ok) error(result.error.ifEmpty { "re-stack failed" })
                _restack.value = _restack.value?.copy(stage = "saving", percent = 99)
                withContext(Dispatchers.IO) {
                    val out = File(dir, "out")
                    val jpg = out.listFiles { f -> f.extension == "jpg" }?.firstOrNull()
                        ?: File(out, "starcam_${dir.name}.jpg")
                    val hdr = repo.cacheFrames(dir).firstOrNull()?.let { Restacker.readHeader(it) }
                    val saved = MediaSaver.saveFinal(
                        context = getApplication(),
                        rgbFile = File(result.finalRgbPath),
                        width = result.width,
                        height = result.height,
                        jpegOut = jpg,
                        totalExposureSec = result.framesUsed * ((hdr?.exposureNs ?: 0L) / 1e9),
                        iso = hdr?.iso ?: 0,
                        focalMm = 0f,
                        fNumber = 0f,
                        recipe = recipe,
                    )
                    if (!saved.jpegWritten) error("the photo could not be written - the phone may be out of storage")
                    // With the options it was made with: the screen shows them, and they are
                    // where the next re-stack starts.
                    repo.writeResult(dir, JSONObject(result.json).put("stackConfig", JSONObject(cfg.toJson())))
                    saved.galleryUri == null
                }
            }
            _restack.value = outcome.fold(
                onSuccess = { galleryFailed ->
                    Restack(dir, stage = "done", percent = 100, finished = true, galleryFailed = galleryFailed)
                },
                onFailure = { t ->
                    AppLog.e(TAG, "re-stack of ${dir.name} failed", t)
                    Restack(dir, finished = true, error = t.message ?: "re-stack failed")
                },
            )
        }
        return true
    }

    // ------------------------------------------------------------------ camera test

    /** Runs [CameraTest] with [p]; ignored while a session or another test owns the camera. */
    fun runCameraTest(p: CameraTest.Params) {
        val ctrl = controller ?: return
        if (cameraBusy || !_ready.value) return
        val app = getApplication<Application>()
        testCancel = false
        val generation = ++serviceGeneration
        _testStatus.value = CameraTest.Status(running = true, params = p, current = "starting")
        // Same foreground service as a real session, so a test can also be run with the screen
        // off — one of the field conditions worth reproducing.
        SessionBeacon.onFinish = { testCancel = true }
        SessionBeacon.onCancel = { testCancel = true }
        CaptureService.start(app)
        val job = viewModelScope.launch(Dispatchers.Default) {
            CameraTest.run(app, ctrl, settingsStore.settings.value, p, { testCancel }) { f ->
                val next = f(_testStatus.value)
                _testStatus.value = next
                SessionBeacon.update(SessionBeacon.Status(
                    "StarCam — camera test", next.current.ifEmpty { next.summary ?: "" },
                    next.results.count { it.ok }, p.frames + 1))
            }
        }
        testJob = job
        // A sibling, not a child: the service and viewfinder may only be released once the test
        // job has fully ended, and a child joining its own parent would never return.
        viewModelScope.launch {
            job.join()
            windDown(generation)
        }
    }

    fun stopCameraTest() {
        AppLog.i(TAG, "camera test stop requested")
        testCancel = true
    }

    /**
     * For `adb shell am start ... --ez camtest true`: runs a camera test as soon as the camera is
     * ready, so tests can be driven from a PC without touching the phone.
     */
    fun runCameraTestWhenReady(p: CameraTest.Params) {
        viewModelScope.launch {
            ready.first { it }
            delay(1500)  // let the viewfinder session settle first
            AppLog.i(TAG, "remote-triggered camera test: $p")
            runCameraTest(p)
        }
    }

    /** For `adb shell am start ... --ez session true`: a real session, as the shutter starts it. */
    fun startSessionWhenReady() {
        viewModelScope.launch {
            ready.first { it }
            delay(1500)  // let the viewfinder session settle first
            AppLog.i(TAG, "remote-triggered session")
            startSession()
        }
    }

    // ------------------------------------------------------------------ diagnostics

    /** Builds the shareable debug zip (logs + recent session metadata + probe + settings). */
    suspend fun buildDebugBundle(): File = withContext(Dispatchers.IO) {
        DebugBundle.build(getApplication(), repo.sessionsRoot(), settingsStore.settings.value.toString())
    }

    /** One line of session state for the foreground-service notification. */
    private fun beaconStatus(e: CaptureEngine): SessionBeacon.Status {
        val detail = e.busy.value?.label ?: ""
        return when (val st = e.state.value) {
            is CaptureState.WaitingStill ->
                SessionBeacon.Status("StarCam — holding still", detail.ifEmpty { "waiting" })
            is CaptureState.Probing ->
                SessionBeacon.Status("StarCam — reading the sky", detail)
            is CaptureState.Focusing -> SessionBeacon.Status(
                "StarCam — star autofocus", detail, st.step, st.total)
            is CaptureState.CapturingLights -> SessionBeacon.Status(
                "StarCam — frame ${st.current}/${st.total}",
                if (detail.isEmpty()) Fmt.exposure(st.exposureNs) + " · ISO ${st.iso}"
                else "$detail · ISO ${st.iso}",
                st.current, st.total,
            )
            is CaptureState.AwaitingDarkCover ->
                SessionBeacon.Status("StarCam — cover the lens", "dark frames in ${st.secondsLeft}s")
            is CaptureState.CapturingDarks -> SessionBeacon.Status(
                "StarCam — dark ${st.current}/${st.total}", detail, st.current, st.total)
            is CaptureState.Stacking -> SessionBeacon.Status(
                "StarCam — stacking", detail.ifEmpty { st.stage }, st.percent, 100)
            is CaptureState.Done -> SessionBeacon.Status("StarCam — done", "saving")
            is CaptureState.Error -> SessionBeacon.Status("StarCam — failed", st.message)
            CaptureState.Idle -> SessionBeacon.Status("StarCam", "starting")
        }
    }

    override fun onCleared() {
        AppLog.i(TAG, "view model cleared — releasing the camera")
        motion.stop()
        thermal.stop()
        SessionBeacon.clearActions()
        CaptureService.stop(getApplication())
        // viewModelScope is cancelled by now, but a session or test can still be inside native
        // code reading an Image's buffer: a cancelled job is no longer "active" while it runs, so
        // wait for "completed" (bounded). Even past the wait the buffer is safe: the controller
        // closes a reader only once its last image has been released.
        val ctrl = controller
        val running = listOfNotNull(engine?.activeJob, testJob?.takeIf { !it.isCompleted })
        CoroutineScope(Dispatchers.Default).launch {
            withTimeoutOrNull(30_000L) { running.joinAll() }
            ctrl?.close()
        }
    }

    private companion object {
        const val TAG = "vm"
    }
}
