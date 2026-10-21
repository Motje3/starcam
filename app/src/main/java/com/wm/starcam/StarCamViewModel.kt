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
import com.wm.starcam.sensors.MotionMonitor
import com.wm.starcam.sensors.ThermalMonitor
import com.wm.starcam.session.SessionRepository
import com.wm.starcam.session.SettingsStore
import com.wm.starcam.util.AppLog
import com.wm.starcam.util.DebugBundle
import com.wm.starcam.util.Fmt
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
    var engine: CaptureEngine? = null
        private set

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
            try {
                val ctrl = newController(cam)
                ctrl.open()
                controller = ctrl
                engine = CaptureEngine(app, ctrl, settingsStore, motion, thermal, repo)
                _ready.value = true
                _cameraLost.value = false
                pendingSurface?.let { attachSurface(it) }
            } catch (t: Throwable) {
                AppLog.e(TAG, "camera open failed", t)
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
        viewModelScope.launch(Dispatchers.Default) {
            // Serialized: a second tap waits for the first teardown/open to finish instead of
            // racing it (which could leak a CameraDevice + its thread forever).
            cameraMutex.withLock {
                if (!force && controller?.cam?.cameraId == id) return@withLock  // already there
                val app = getApplication<Application>()
                runCatching {
                    // Validate the target before tearing anything down: a bad id must not leave
                    // the app with no camera at all.
                    val cam = CamMeta.pickCamera(app, id)
                        ?: throw IllegalStateException("camera $id is not usable for RAW capture")
                    AppLog.i(TAG, "$what: opening camera $id")
                    settingsStore.update { it.copy(lensId = id) }
                    _ready.value = false
                    runCatching { controller?.close() }
                    controller = null
                    engine = null
                    _camInfo.value = cam
                    val ctrl = newController(cam)
                    ctrl.open()
                    controller = ctrl
                    engine = CaptureEngine(app, ctrl, settingsStore, motion, thermal, repo)
                    _ready.value = true
                    _cameraError.value = null
                    _cameraLost.value = false
                    pendingSurface?.let { attachSurface(it) }
                }.onFailure { t ->
                    AppLog.e(TAG, "${what.lowercase()} failed", t)
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
        // A second tap would otherwise wire a second notifier loop onto the same session.
        if (cameraBusy) return
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
    }

    /**
     * A surface that came back while the camera was busy was deliberately ignored, and recovery
     * may have left a RAW-only session behind; either way the viewfinder is rebuilt now.
     */
    private fun restorePreview() {
        pendingSurface?.let { if (it.isValid) attachSurface(it) }
    }

    // ------------------------------------------------------------------ camera test

    /** Runs [CameraTest] with [p]; ignored while a session or another test owns the camera. */
    fun runCameraTest(p: CameraTest.Params) {
        val ctrl = controller ?: return
        if (cameraBusy) return
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
        // code reading an Image's buffer. Closing the controller closes that ImageReader, which
        // frees the buffer under the native read. Let them return first (bounded wait).
        val ctrl = controller
        val running = listOfNotNull(engine?.activeJob, testJob?.takeIf { it.isActive })
        CoroutineScope(Dispatchers.Default).launch {
            withTimeoutOrNull(30_000L) { running.joinAll() }
            ctrl?.close()
        }
    }

    private companion object {
        const val TAG = "vm"
    }
}
