package com.wm.starcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import com.wm.starcam.util.AppLog
import com.wm.starcam.util.Fmt
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

/** The preview surface died before we could use it — expected, not an error worth showing. */
class SurfaceGoneException : IllegalStateException("preview surface was released")

/**
 * The OS took the camera away, or never gave it. Distinct from a generic failure because the
 * remedy is different: nothing here is retryable frame-by-frame, the device has to be reopened.
 */
class CameraLostException(reason: String) : IllegalStateException(reason)

/**
 * Camera2 reported a capture as failed ([CaptureFailure]). [reason] is REASON_ERROR (0: the
 * camera device could not produce the frame) or REASON_FLUSHED (1: aborted by us or a reconfigure).
 */
class CaptureFailedException(
    val reason: Int,
    val frameNumber: Long,
    val imageCaptured: Boolean,
    val afterMs: Long,
) : IllegalStateException(
    "camera reported capture failure ${CameraController.failureName(reason)} " +
        "(frame $frameNumber, ${afterMs}ms after the request, imageCaptured=$imageCaptured)"
)

/** The camera accepted a request and then went silent. */
class CaptureTimeoutException(message: String) : IllegalStateException(message)

/** White balance lock captured from the probe frame's AWB and reused for the whole session. */
data class WbLock(
    val gains: RggbChannelVector,
    val transform: ColorSpaceTransform?,
)

/**
 * One completed RAW capture: the sensor image + its metadata. Release it with [close], not by
 * closing [image] directly: the controller keeps the image's reader open until it comes back.
 */
class CapturedRaw(
    val image: Image,
    val result: TotalCaptureResult,
    /** Our request number, for matching log lines. */
    val seq: Int = 0,
    /** Request submitted -> image in hand. */
    val elapsedMs: Long = 0L,
    private val onRelease: () -> Unit = {},
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
        if (released.compareAndSet(false, true)) {
            runCatching { image.close() }
            onRelease()
        }
    }

    /** The RAW plane as the engine reads it. */
    fun pixels(): RawPixels {
        val plane = image.planes[0]
        return RawPixels(plane.buffer, image.width, image.height, plane.rowStride)
    }
}

/**
 * A RAW16 Bayer frame for the native engine: a capture's own plane ([CapturedRaw.pixels]), or a
 * simulated sky in its place ([SkySim]). [rowStride] is in bytes.
 */
class RawPixels(val buffer: java.nio.ByteBuffer, val width: Int, val height: Int, val rowStride: Int)

/**
 * Thin Camera2 wrapper: owns the camera thread, device, session, RAW ImageReader. All suspend
 * functions may throw; CaptureEngine translates failures into user-visible state.
 *
 * Everything that happens to the camera is logged under the "cam" tag — every request with its
 * settings, every callback with its timing, every image, every state change. A capture that fails
 * in the field has to be explainable from the log alone, because nobody has a laptop out there.
 */
class CameraController(private val context: Context, val cam: CamInfo) {

    private val thread = HandlerThread("StarCamCamera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { r -> handler.post(r) }
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    @Volatile private var device: CameraDevice? = null
    @Volatile private var deviceClosed: CompletableDeferred<Unit>? = null

    /**
     * Set by [close]. Once the camera thread has quit, Camera2 callbacks are dropped silently,
     * so an open or a session build started afterwards would wait forever — they refuse instead.
     */
    @Volatile private var closed = false
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var rawReader: ImageReader? = null

    /** The last preview surface the UI handed us. May have died since; check isValid. */
    @Volatile private var previewSurface: Surface? = null

    /** Whether the live session outputs to [previewSurface] (false = RAW-only session). */
    @Volatile var sessionHasPreview = false
        private set

    /**
     * An image as it arrived, with the properties read at that moment: once published, a consumer
     * may close it at any time, and reading a closed Image throws.
     */
    private class Arrived(val image: Image, val reader: ImageReader, val ts: Long, val w: Int, val h: Int)

    // Images arrive here from the reader; captureRaw matches them to results by timestamp.
    private val pendingImages = ArrayDeque<Arrived>()
    @Volatile
    private var imageWaiter: CompletableDeferred<Unit>? = null

    /**
     * Images handed to callers and not yet released, per reader. The native ingest reads an
     * Image's buffer directly, and closing its ImageReader frees that buffer under the read - a
     * camera taken away mid-ingest, or the app torn down, could crash the process. A reader with
     * images out is retired instead, and closed when the last of them comes back.
     */
    private val leases = HashMap<ImageReader, Int>()
    private val retiredReaders = HashSet<ImageReader>()

    var currentRawSize: Size? = null
        private set

    /** Set SENSOR_FRAME_DURATION to the exposure explicitly (Debug setting; HALs should not need it). */
    @Volatile var explicitFrameDuration = false

    /**
     * Rebuild the capture session after every long exposure, before anything else is sent to it.
     *
     * The October 2026 field failure, reproduced on the bench and explained by the driver's own
     * log: on the OnePlus 15 (Qualcomm CamX, logical multi-camera), the first request that follows
     * a long exposure in the same capture session never completes. CamX logs
     * "MCTFE node has 3 unsignaled fences ... Feature RealTimeMCX is waiting for 2 external
     * output(s)" and only fails the request when a PCR timeout recovery fires, after which every
     * request on the session fails at once. That is why the first 22.5 s light always worked and
     * the second never did, and why a 1 s probe straight after a long frame hung too. A fresh
     * session resets that stage: 3 of 3 long frames in a row came back on time with this on,
     * 1 of 3 without it. A rebuild costs ~0.1-0.2 s. Debug switch, so it can still be A/B tested.
     */
    @Volatile var freshSessionAfterLong = true

    /** The live session has completed a long exposure; see [freshSessionAfterLong]. */
    @Volatile private var sessionTainted = false

    /** Log the per-frame callbacks that are normally only noise (Debug setting). */
    @Volatile var verbose = true

    private var sessionCounter = 0
    @Volatile private var sessionId = 0
    private var captureCounter = 0
    @Volatile private var lastEvent = "none"
    @Volatile private var openedAtMs = 0L

    /**
     * Serializes everything that builds or tears down the capture session. The viewfinder's
     * surfaceCreated and surfaceChanged arrive back to back and each asks for a session; without
     * this the two builds race, and the loser's "configure failed" surfaced as an error card.
     */
    private val configMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Who owns the camera right now ("session", "camera test"), or null. While owned, the
     * viewfinder may not rebuild the session or start its repeating request — either would land
     * in the middle of a burst of long exposures.
     */
    @Volatile private var exclusiveOwner: String? = null

    fun beginExclusive(owner: String) {
        exclusiveOwner = owner
        AppLog.d(TAG, "camera now owned by $owner")
    }

    fun endExclusive() {
        AppLog.d(TAG, "camera released by ${exclusiveOwner ?: "nobody"}")
        exclusiveOwner = null
    }

    /**
     * Whether the HAL will report a lens shading map at all. Checked once: an unsupported key set
     * on a request is not a no-op, it can make the capture itself fail.
     */
    private val shadingMapAvailable: Boolean by lazy {
        runCatching {
            cam.characteristics
                .get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
                ?.contains(CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON) == true
        }.getOrDefault(false)
    }

    private val maxFrameDurationNs: Long? by lazy {
        runCatching {
            cam.characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
        }.getOrNull()
    }

    /**
     * Fired when the camera is lost *after* it was opened — another app grabbed it, the system
     * revoked it, the device errored. Everything this class holds is already released by then;
     * the owner's job is to tell the user and offer to reopen.
     */
    @Volatile
    var onDeviceLost: ((String) -> Unit)? = null

    /** Fired after [recover] reopened the device by itself, so the owner can clear its "lost" UI. */
    @Volatile
    var onDeviceReopened: (() -> Unit)? = null

    /**
     * Logs when this camera becomes busy/free and when camera access priorities shift (another
     * app came to the foreground, ours went to the background). Those are the events that
     * precede a revoked camera, so they belong in the timeline.
     */
    private val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (cameraId == cam.cameraId) AppLog.d(TAG, "availability: camera $cameraId is free")
        }
        override fun onCameraUnavailable(cameraId: String) {
            if (cameraId == cam.cameraId) AppLog.d(TAG, "availability: camera $cameraId is in use")
        }
        override fun onCameraAccessPrioritiesChanged() {
            AppLog.i(TAG, "availability: camera access priorities changed (an app changed foreground state)")
        }
    }
    @Volatile private var availabilityRegistered = false

    @SuppressLint("MissingPermission")  // permission is checked by MainActivity before use
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun open() {
        if (closed) throw CameraLostException("the camera controller was closed")
        if (device != null) return
        val t0 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "opening camera ${cam.cameraId} (${cam.fullRawSize}, ${cam.focalLengthMm}mm f/${cam.apertureF})")
        val closedSignal = CompletableDeferred<Unit>()
        val opened = try {
            withTimeout(OPEN_TIMEOUT_MS) {
                suspendCancellableCoroutine<CameraDevice> { cont ->
                    manager.openCamera(cam.cameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(d: CameraDevice) {
                            AppLog.i(TAG, "camera ${d.id} opened in ${SystemClock.elapsedRealtime() - t0}ms")
                            if (cont.isActive && !closed) {
                                // Published before resuming: a disconnect queued right behind
                                // onOpened must find this as the current device, or lost() would
                                // dismiss it as a stale one and nobody would hear of it.
                                device = d
                                // Cancelled before the caller takes it: closed, not leaked.
                                cont.resume(d) {
                                    if (device === d) device = null
                                    runCatching { d.close() }
                                }
                            } else {
                                runCatching { d.close() }
                            }
                        }
                        override fun onClosed(d: CameraDevice) {
                            AppLog.d(TAG, "camera ${d.id} closed")
                            closedSignal.complete(Unit)
                        }
                        override fun onDisconnected(d: CameraDevice) =
                            lost(d, cont, "Another app or the system took the camera (disconnected)")
                        override fun onError(d: CameraDevice, error: Int) =
                            lost(d, cont, "Camera device error: ${deviceErrorName(error)}")
                    }, handler)
                }
            }
        } catch (t: TimeoutCancellationException) {
            AppLog.e(TAG, "camera ${cam.cameraId} did not open within ${OPEN_TIMEOUT_MS}ms")
            throw CameraLostException("the camera did not open within ${OPEN_TIMEOUT_MS / 1000}s")
        }
        // A disconnect handled between onOpened and here has already dropped the device and told
        // the owner. Taking it back as the open camera would advertise a closed handle as ready.
        if (device !== opened) {
            runCatching { opened.close() }
            AppLog.e(TAG, "camera ${cam.cameraId} was taken away while it was opening")
            throw CameraLostException("the camera was taken away while it was opening")
        }
        deviceClosed = closedSignal
        openedAtMs = SystemClock.elapsedRealtime()
        if (!availabilityRegistered) {
            runCatching { manager.registerAvailabilityCallback(executor, availability) }
                .onSuccess { availabilityRegistered = true }
        }
    }

    /**
     * Camera loss, before or after a successful open.
     *
     * These callbacks keep firing for the whole life of the device, not just while opening. The
     * post-open case used to fall through silently: the device was closed but this object kept
     * pointing at it, so every later call threw from deep inside Camera2 with a meaningless
     * message and nothing ever reopened it. Now the handles are dropped and the owner is told.
     */
    private fun lost(
        d: CameraDevice,
        cont: CancellableContinuation<CameraDevice>,
        reason: String,
    ) {
        runCatching { d.close() }
        if (cont.isActive) {
            AppLog.e(TAG, "camera open failed: $reason")
            cont.resumeWithException(CameraLostException(reason))
            return
        }
        // A device that recovery already replaced, reporting its own death late, is old news.
        if (d !== device) {
            AppLog.w(TAG, "old camera device reported '$reason' after being replaced — ignored")
            return
        }
        AppLog.e(TAG, "camera lost ${(SystemClock.elapsedRealtime() - openedAtMs) / 1000}s after open: $reason — ${dumpState()}")
        releaseHandles()
        onDeviceLost?.invoke(reason)
    }

    /** Drops every Camera2 handle without touching the worker thread, which stays reusable. */
    private fun releaseHandles() {
        closeSessionAndReader()
        device = null
    }

    private fun closeSessionAndReader() {
        runCatching { session?.close() }
        session = null
        sessionHasPreview = false
        // Queued images first: closing the reader invalidates them, and the drain used to read
        // their timestamps afterwards - an exception that, from a device-loss callback on the
        // camera thread, took the whole app down.
        drainPendingImages("session teardown")
        rawReader?.let { retireReader(it) }
        rawReader = null
        currentRawSize = null
    }

    /** Closes [reader] now, or as soon as the last image handed out from it is released. */
    private fun retireReader(reader: ImageReader) {
        val inUse = synchronized(leases) {
            val n = leases[reader] ?: 0
            if (n > 0) retiredReaders += reader
            n
        }
        if (inUse > 0) {
            AppLog.w(TAG, "RAW reader retired with $inUse image(s) still being read — closing it when they are released")
        } else {
            runCatching { reader.close() }
        }
    }

    private fun lease(reader: ImageReader) = synchronized(leases) {
        leases[reader] = (leases[reader] ?: 0) + 1
    }

    private fun release(reader: ImageReader) {
        val closeNow = synchronized(leases) {
            val n = (leases[reader] ?: 1) - 1
            if (n <= 0) leases.remove(reader) else leases[reader] = n
            n <= 0 && retiredReaders.remove(reader)
        }
        if (closeNow) {
            runCatching { reader.close() }
            AppLog.d(TAG, "retired RAW reader closed: its last image was released")
        }
    }

    /**
     * (Re)creates the capture session for the viewfinder: [preview] plus a RAW reader of
     * [rawSize]. Called whenever the UI's preview surface appears.
     */
    suspend fun configureSession(preview: Surface, rawSize: Size) = configMutex.withLock {
        // The SurfaceView can be torn down (screen sleeps, app backgrounded) between the callback
        // firing and this running; Camera2 throws "Surface was abandoned" if we hand it a dead one.
        if (!preview.isValid) throw SurfaceGoneException()
        exclusiveOwner?.let {
            AppLog.i(TAG, "viewfinder reconfigure skipped: the camera is owned by $it")
            return@withLock
        }
        // Identity alone cannot tell a stale output: SurfaceView hands out the same Surface
        // object across destroy/recreate. onPreviewSurfaceDestroyed forgets it for that reason.
        // A tainted session would hang the viewfinder's first request like any other.
        val fits = session != null && rawSize == currentRawSize && sessionHasPreview &&
            preview === previewSurface && !needsFreshSession()
        previewSurface = preview
        if (!fits) buildSession(preview, rawSize, "viewfinder")
    }

    /**
     * Makes sure a session exists for capturing RAW at [rawSize]. With [withPreview] the
     * viewfinder output stays in the session (the classic setup); without it, or when the preview
     * surface is gone (screen off), the session is RAW-only.
     */
    suspend fun prepareCapture(rawSize: Size, withPreview: Boolean) =
        configMutex.withLock { prepareCaptureLocked(rawSize, withPreview) }

    private suspend fun prepareCaptureLocked(rawSize: Size, withPreview: Boolean) {
        val surf = previewSurface?.takeIf { withPreview && it.isValid }
        val wantPreview = surf != null
        if (session != null && rawSize == currentRawSize && sessionHasPreview == wantPreview &&
            (!wantPreview || surf === previewSurface) && !needsFreshSession()
        ) {
            AppLog.d(TAG, "prepareCapture: session already fits (raw $rawSize, preview=$wantPreview)")
            return
        }
        buildSession(surf, rawSize, if (wantPreview) "capture" else "capture, raw-only")
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun buildSession(preview: Surface?, rawSize: Size, why: String) {
        if (closed) throw CameraLostException("the camera controller was closed")
        val dev = device ?: throw CameraLostException("camera not open")
        closeSessionAndReader()
        val id = ++sessionCounter
        val t0 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "session #$id ($why): RAW ${rawSize.width}x${rawSize.height}" +
            if (preview != null) " + preview" else " only")
        val reader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, MAX_IMAGES)
        reader.setOnImageAvailableListener({ r -> onImageAvailable(r) }, handler)
        rawReader = reader
        currentRawSize = rawSize

        val outputs = buildList {
            if (preview != null) add(OutputConfiguration(preview))
            add(OutputConfiguration(reader.surface))
        }
        session = try {
            withTimeout(CONFIGURE_TIMEOUT_MS) {
                suspendCancellableCoroutine<CameraCaptureSession> { cont ->
                    val config = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR, outputs, executor,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                AppLog.i(TAG, "session #$id configured in ${SystemClock.elapsedRealtime() - t0}ms")
                                // Cancelled before the caller takes it: closed, not leaked.
                                if (cont.isActive) cont.resume(s) { runCatching { s.close() } }
                                else runCatching { s.close() }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                AppLog.e(TAG, "session #$id configure FAILED")
                                if (cont.isActive) cont.resumeWithException(IllegalStateException("session configure failed"))
                            }
                            override fun onReady(s: CameraCaptureSession) {
                                if (verbose) AppLog.d(TAG, "session #$id ready (idle)")
                            }
                            override fun onActive(s: CameraCaptureSession) {
                                if (verbose) AppLog.d(TAG, "session #$id active")
                            }
                            override fun onClosed(s: CameraCaptureSession) {
                                AppLog.d(TAG, "session #$id closed")
                            }
                        })
                    dev.createCaptureSession(config)
                }
            }
        } catch (t: TimeoutCancellationException) {
            AppLog.e(TAG, "session #$id: no configure callback within ${CONFIGURE_TIMEOUT_MS}ms")
            throw CameraLostException("the camera session did not configure within ${CONFIGURE_TIMEOUT_MS / 1000}s")
        }
        sessionId = id
        sessionHasPreview = preview != null
        sessionTainted = false
    }

    private fun needsFreshSession() = sessionTainted && freshSessionAfterLong

    /**
     * Framing preview. [boostExposure] uses manual long-ish exposure + high ISO so stars/framing
     * are visible; otherwise plain auto with +2 EV bias.
     */
    fun startPreview(boostExposure: Boolean) {
        exclusiveOwner?.let {
            AppLog.d(TAG, "preview not started: the camera is owned by $it")
            return
        }
        if (needsFreshSession()) {
            // Its first request would hang (see freshSessionAfterLong); the viewfinder comes back
            // when configureSession rebuilds the session.
            AppLog.d(TAG, "preview not started: session #$sessionId needs a rebuild after a long exposure")
            return
        }
        val dev = device ?: return
        val ses = session ?: return
        val surf = previewSurface?.takeIf { sessionHasPreview && it.isValid } ?: return
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surf)
            if (boostExposure && cam.hasManualSensor) {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                // The preview frame rate is 1/exposure, so this is a direct brightness-vs-smoothness
                // trade: 500 ms would be brighter but pins the viewfinder at 2 fps, which just reads
                // as "the app is broken". 125 ms keeps ~8 fps while still being ~3 stops brighter
                // than the auto preview, which is plenty to frame a horizon and see bright stars.
                set(CaptureRequest.SENSOR_EXPOSURE_TIME,
                    BOOST_EXPOSURE_NS.coerceIn(cam.exposureRangeNs.lower, cam.exposureRangeNs.upper))
                set(CaptureRequest.SENSOR_SENSITIVITY, cam.isoRange.upper.coerceAtMost(12800))
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            } else {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeBoostSteps())
            }
        }
        runCatching { ses.setRepeatingRequest(req.build(), null, handler) }
            .onSuccess { AppLog.d(TAG, "preview started on session #$sessionId (boost=$boostExposure)") }
            .onFailure { AppLog.e(TAG, "preview start failed", it) }
    }

    private fun aeBoostSteps(): Int {
        val range = cam.characteristics.get(
            android.hardware.camera2.CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = cam.characteristics.get(
            android.hardware.camera2.CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        if (range == null || step == null) return 0
        val steps = (2.0 / step.toDouble()).toInt()
        return steps.coerceIn(range.lower, range.upper)
    }

    fun stopPreview() {
        val ses = session ?: return
        runCatching { ses.stopRepeating() }
            .onSuccess { AppLog.d(TAG, "preview stopped on session #$sessionId") }
            .onFailure { AppLog.w(TAG, "stopRepeating failed", it) }
    }

    /**
     * The UI's preview surface was destroyed (screen off, app backgrounded, screen change).
     * Forgotten, not just stopped: when it comes back it is the same Surface object with a new
     * buffer queue behind it, and only a rebuilt session can draw into that.
     */
    fun onPreviewSurfaceDestroyed() {
        AppLog.i(TAG, "preview surface destroyed" +
            if (sessionHasPreview) " — session #$sessionId still lists it as an output" else "")
        if (exclusiveOwner == null) stopPreview()
        previewSurface = null
    }

    /**
     * One manual RAW still. Returns image + metadata; caller closes the image.
     * [wb] null => AWB AUTO (probe frame; its result carries the gains to lock later).
     * [wantShadingMap] asks the HAL for its lens shading map (only the probe frame needs it).
     */
    suspend fun captureRaw(
        exposureNs: Long,
        iso: Int,
        focusDistance: Float,
        wb: WbLock?,
        label: String = "capture",
        wantShadingMap: Boolean = false,
    ): CapturedRaw {
        if (needsFreshSession()) {
            // Never send a request into a session that has just finished a long exposure: on this
            // phone it would hang for a minute and take the session down with it.
            configMutex.withLock {
                if (needsFreshSession()) {
                    val size = currentRawSize ?: cam.rawSize(false)
                    val keepPreview = sessionHasPreview
                    val t0 = SystemClock.elapsedRealtime()
                    closeSessionAndReader()
                    prepareCaptureLocked(size, keepPreview)
                    AppLog.i(TAG, "fresh session #$sessionId after the long exposure, " +
                        "${SystemClock.elapsedRealtime() - t0}ms")
                }
            }
        }
        // Typed, so a session that dies because the camera was taken away says so instead of
        // reporting a generic capture failure.
        val dev = device ?: throw CameraLostException("The camera is no longer open")
        val ses = session ?: throw CameraLostException("The capture session was closed")
        val reader = rawReader ?: throw CameraLostException("The RAW reader was closed")

        val seq = ++captureCounter
        // Anything still sitting in the queue belongs to an earlier, finished or failed capture.
        // Holding it would eat into the reader's 3-buffer budget for no reason.
        drainPendingImages("before #$seq")

        val exp = exposureNs.coerceIn(cam.exposureRangeNs.lower, cam.exposureRangeNs.upper)
        val sens = iso.coerceIn(cam.isoRange.lower, cam.isoRange.upper)
        val focus = focusDistance.coerceAtLeast(0f)
        val lsc = wantShadingMap && shadingMapAvailable
        val frameDuration = if (explicitFrameDuration) {
            maxFrameDurationNs?.let { exp.coerceAtMost(it) } ?: exp
        } else null

        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            set(CaptureRequest.SENSOR_SENSITIVITY, sens)
            frameDuration?.let { set(CaptureRequest.SENSOR_FRAME_DURATION, it) }
            set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            runCatching {
                set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)
            }
            // RAW_SENSOR is never shading-corrected by the device, so the map is the only way to
            // know this lens's vignetting. It is a property of the lens, so one frame's worth is
            // all a session needs: asking for it on every long exposure is pure HAL work.
            if (lsc) {
                set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            }
            if (wb != null) {
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                set(CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                set(CaptureRequest.COLOR_CORRECTION_GAINS, wb.gains)
                wb.transform?.let { set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it) }
            } else {
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }
        }

        val t0 = SystemClock.elapsedRealtime()
        fun since() = SystemClock.elapsedRealtime() - t0
        AppLog.i(TAG, "#$seq $label: submit ${Fmt.exposure(exp)} ISO $sens focus %.3f wb=%s fd=%s lsc=%s -> RAW %dx%d on session #%d (%s)".format(
            focus, if (wb == null) "auto" else "locked",
            frameDuration?.let { Fmt.exposure(it) } ?: "template",
            if (lsc) "on" else "off", reader.width, reader.height, sessionId,
            if (sessionHasPreview) "preview output ${if (previewSurface?.isValid == true) "alive" else "DEAD"}" else "raw-only",
        ))

        val resultDeferred = CompletableDeferred<TotalCaptureResult>()
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(
                s: CameraCaptureSession, r: CaptureRequest, timestamp: Long, frameNumber: Long
            ) {
                lastEvent = "#$seq started"
                AppLog.d(TAG, "#$seq exposure started +${since()}ms (frame $frameNumber)")
            }
            override fun onCaptureCompleted(
                s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult
            ) {
                lastEvent = "#$seq completed"
                AppLog.i(TAG, "#$seq result +${since()}ms: ${describeResult(result, exp, sens)}")
                resultDeferred.complete(result)
            }
            override fun onCaptureFailed(
                s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure
            ) {
                lastEvent = "#$seq FAILED ${failureName(failure.reason)}"
                AppLog.e(TAG, "#$seq FAILED +${since()}ms: reason=${failureName(failure.reason)} " +
                    "frame=${failure.frameNumber} seqId=${failure.sequenceId} " +
                    "imageCaptured=${failure.wasImageCaptured()} — ${dumpState()}")
                resultDeferred.completeExceptionally(CaptureFailedException(
                    failure.reason, failure.frameNumber, failure.wasImageCaptured(), since()))
            }
            override fun onCaptureBufferLost(
                s: CameraCaptureSession, r: CaptureRequest, target: Surface, frameNumber: Long
            ) {
                lastEvent = "#$seq buffer lost"
                val which = if (target === reader.surface) "RAW" else "other"
                AppLog.e(TAG, "#$seq BUFFER LOST +${since()}ms for the $which output (frame $frameNumber)")
            }
            override fun onCaptureSequenceAborted(s: CameraCaptureSession, sequenceId: Int) {
                AppLog.w(TAG, "#$seq sequence $sequenceId aborted +${since()}ms")
            }
        }
        val seqId = try {
            ses.capture(req.build(), callback, handler)
        } catch (t: Throwable) {
            AppLog.e(TAG, "#$seq capture() threw — ${dumpState()}", t)
            throw t
        }
        if (verbose) AppLog.d(TAG, "#$seq queued as sequence $seqId")
        // From here on, whatever happens to this request, the session has seen a long exposure.
        if (exp >= TAINT_EXPOSURE_NS) sessionTainted = true

        // Generous: after a long frame some sensors finish their current free-running exposure
        // before latching a new request, which can add up to one exposure of latency.
        val exposureMs = exp / 1_000_000L
        val timeoutMs = exposureMs * 2 + 15_000L
        val result = try {
            withTimeout(timeoutMs) { resultDeferred.await() }
        } catch (t: TimeoutCancellationException) {
            AppLog.e(TAG, "#$seq no result after ${timeoutMs}ms (last event: $lastEvent) — ${dumpState()}")
            throw CaptureTimeoutException("the camera did not return the frame within ${timeoutMs / 1000}s")
        }
        val wantedTs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: -1L

        // The image usually lands right after the result; wait for a matching timestamp.
        val tResult = since()
        val arrived = awaitImage(wantedTs, seq)
        val total = since()
        AppLog.i(TAG, "#$seq $label done in ${total}ms (image +${total - tResult}ms after result)")
        // Leased by awaitImage: its reader stays open until the caller releases the image.
        return CapturedRaw(arrived.image, result, seq, total) { release(arrived.reader) }
    }

    private fun onImageAvailable(r: ImageReader) {
        val img = try {
            r.acquireNextImage()
        } catch (t: Throwable) {
            // Thrown when we already hold maxImages — the signature of a starved reader.
            AppLog.e(TAG, "acquireNextImage failed (holding ${pendingImages.size} pending) — ${t.message}")
            null
        } ?: return
        // Read before publishing: once queued, a consumer may claim and close it at once.
        val arrived = runCatching { Arrived(img, r, img.timestamp, img.width, img.height) }
            .getOrElse {
                AppLog.e(TAG, "image arrived already invalid — ${it.message}")
                runCatching { img.close() }
                return
            }
        val dropped = ArrayList<Long>()
        val pending: Int
        synchronized(pendingImages) {
            pendingImages.addLast(arrived)
            // Reader has maxImages=3; drop oldest if a consumer is lagging badly.
            while (pendingImages.size > 2) {
                val old = pendingImages.removeFirst()
                dropped += old.ts
                runCatching { old.image.close() }
            }
            pending = pendingImages.size
        }
        AppLog.d(TAG, "image ts=${arrived.ts} ${arrived.w}x${arrived.h} queued (pending $pending)" +
            if (dropped.isNotEmpty()) " — DROPPED unclaimed $dropped" else "")
        imageWaiter?.complete(Unit)
    }

    private suspend fun awaitImage(wantedTs: Long, seq: Int): Arrived {
        val deadline = System.currentTimeMillis() + 10_000L
        while (true) {
            // Arm the waiter BEFORE scanning so an image arriving mid-scan still wakes us.
            val waiter = CompletableDeferred<Unit>()
            imageWaiter = waiter
            var found: Arrived? = null
            val stale = ArrayList<Long>()
            synchronized(pendingImages) {
                val it = pendingImages.iterator()
                while (it.hasNext()) {
                    val img = it.next()
                    if (wantedTs == -1L || img.ts == wantedTs) {
                        it.remove()
                        // Leased under the same lock the teardown drains under, so a reader
                        // retired in between always knows this image is still out.
                        lease(img.reader)
                        found = img
                        break
                    }
                    if (img.ts < wantedTs) {
                        // stale image from an earlier/aborted capture
                        it.remove()
                        stale += img.ts
                        runCatching { img.image.close() }
                    }
                }
            }
            if (stale.isNotEmpty()) AppLog.w(TAG, "#$seq discarded stale images $stale (wanted ts=$wantedTs)")
            if (found != null) {
                imageWaiter = null
                return found!!
            }
            if (System.currentTimeMillis() > deadline) {
                imageWaiter = null
                AppLog.e(TAG, "#$seq image never arrived (wanted ts=$wantedTs) — ${dumpState()}")
                throw CaptureTimeoutException("timed out waiting for RAW image (ts=$wantedTs)")
            }
            withTimeoutOrNull(500L) { waiter.await() }
            imageWaiter = null
        }
    }

    private fun drainPendingImages(why: String) {
        val closed = ArrayList<Long>()
        synchronized(pendingImages) {
            pendingImages.forEach { closed += it.ts; runCatching { it.image.close() } }
            pendingImages.clear()
        }
        if (closed.isNotEmpty()) AppLog.w(TAG, "closed ${closed.size} unclaimed image(s) ($why): $closed")
    }

    /**
     * Tries to get the camera back into a state where captures succeed, after one failed.
     * Each level is more drastic than the last:
     *  1. abort anything in flight and drop stale images — fixes a confused request queue;
     *  2. rebuild the capture session and RAW reader — fixes a dead or starved stream;
     *  3. close and reopen the camera device — fixes a HAL stuck in a bad state.
     *
     * Rebuilt sessions are RAW-only: nothing looks at the viewfinder mid-burst, and a preview
     * output whose surface died with the screen is one more thing that can go wrong. The owner
     * re-attaches the viewfinder when the session ends.
     */
    suspend fun recover(level: Int, rawSize: Size) = configMutex.withLock {
        if (closed) throw CameraLostException("the camera controller was closed")
        AppLog.w(TAG, "recovery level $level starting — ${dumpState()}")
        val t0 = SystemClock.elapsedRealtime()
        when (level) {
            1 -> {
                session?.let { s ->
                    runCatching { s.abortCaptures() }
                        .onFailure { AppLog.w(TAG, "abortCaptures failed", it) }
                }
                drainPendingImages("recovery")
                delay(750)
            }
            2 -> {
                closeSessionAndReader()
                delay(300)
                prepareCaptureLocked(rawSize, withPreview = false)
            }
            else -> {
                closeSessionAndReader()
                val old = device
                val oldClosed = deviceClosed
                device = null
                runCatching { old?.close() }
                // Reopening before the old client is fully gone can be refused as "in use".
                withTimeoutOrNull(3_000L) { oldClosed?.await() }
                    ?: AppLog.w(TAG, "old camera device did not report closed within 3s")
                delay(300)
                try {
                    open()
                    prepareCaptureLocked(rawSize, withPreview = false)
                } catch (t: Throwable) {
                    // The owner still believes the camera is fine; it is not, and nothing else
                    // will tell it. Report the loss before giving up.
                    if (t !is kotlinx.coroutines.CancellationException) {
                        AppLog.e(TAG, "reopen failed: ${t.message}")
                        onDeviceLost?.invoke("Reopening the camera failed: ${t.message}")
                    }
                    throw t
                }
                // A loss reported during the rebuild must not be overwritten by "reopened".
                if (device != null && session != null) onDeviceReopened?.invoke()
            }
        }
        AppLog.i(TAG, "recovery level $level finished in ${SystemClock.elapsedRealtime() - t0}ms — ${dumpState()}")
    }

    /** One line of everything worth knowing about the camera's state, for failure logs. */
    fun dumpState(): String {
        val pending = synchronized(pendingImages) { pendingImages.map { it.ts } }
        return "state[device=${device?.id ?: "none"} session=${if (session != null) "#$sessionId" else "none"} " +
            "preview=${when {
                !sessionHasPreview -> "not in session"
                previewSurface?.isValid == true -> "alive"
                else -> "DEAD"
            }} raw=${currentRawSize ?: "none"} pending=$pending lastEvent=$lastEvent]"
    }

    /** Whether a live viewfinder surface is available to put in a capture session. */
    fun hasLivePreview(): Boolean = previewSurface?.isValid == true

    fun close() {
        AppLog.i(TAG, "closing camera controller (${cam.cameraId})")
        closed = true
        if (availabilityRegistered) {
            runCatching { manager.unregisterAvailabilityCallback(availability) }
            availabilityRegistered = false
        }
        closeSessionAndReader()
        runCatching { device?.close() }
        device = null
        thread.quitSafely()
    }

    /** The values that matter from a completed result, against what was asked for. */
    private fun describeResult(r: TotalCaptureResult, askedExp: Long, askedIso: Int): String {
        fun <T> get(k: CaptureResult.Key<T>): T? = runCatching { r.get(k) }.getOrNull()
        val exp = get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val iso = get(CaptureResult.SENSOR_SENSITIVITY)
        val fd = get(CaptureResult.SENSOR_FRAME_DURATION)
        val focus = get(CaptureResult.LENS_FOCUS_DISTANCE)
        val lens = when (get(CaptureResult.LENS_STATE)) {
            CaptureResult.LENS_STATE_STATIONARY -> "still"
            CaptureResult.LENS_STATE_MOVING -> "MOVING"
            else -> "?"
        }
        val lsc = get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        val black = get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        return buildString {
            append("frame ${r.frameNumber} exp ${exp?.let { Fmt.exposure(it) } ?: "?"}")
            if (exp != null && kotlin.math.abs(exp - askedExp) > askedExp / 50) append(" (ASKED ${Fmt.exposure(askedExp)})")
            append(" ISO ${iso ?: "?"}")
            if (iso != null && iso != askedIso) append(" (ASKED $askedIso)")
            append(" fd ${fd?.let { Fmt.exposure(it) } ?: "?"}")
            append(" focus ${focus?.let { "%.3f".format(it) } ?: "?"} lens $lens")
            if (lsc != null) append(" lsc ${lsc.columnCount}x${lsc.rowCount}")
            if (black != null) append(" black ${black.joinToString(",") { "%.1f".format(it) }}")
        }
    }

    companion object {
        private const val TAG = "cam"

        /** Framing-boost preview exposure. Also sets the preview frame rate (1/exposure). */
        const val BOOST_EXPOSURE_NS = 125_000_000L

        /** RAW buffers the app may hold at once (each ~25 MB at 12.6 MP). */
        private const val MAX_IMAGES = 3

        /**
         * Bounds on the two Camera2 waits that have no timeout of their own. If the callback
         * never comes (camera thread gone, HAL wedged) the caller gets an error, not a hang.
         */
        private const val OPEN_TIMEOUT_MS = 10_000L
        private const val CONFIGURE_TIMEOUT_MS = 10_000L

        /**
         * Exposures from this length on leave the session needing a rebuild. Back-to-back 2 s
         * focus-sweep frames have always worked on this phone; 22.5 s ones never did. The rebuild
         * is cheap, so the line sits low.
         */
        const val TAINT_EXPOSURE_NS = 2_500_000_000L

        fun failureName(reason: Int): String = when (reason) {
            CaptureFailure.REASON_ERROR -> "ERROR(0)"
            CaptureFailure.REASON_FLUSHED -> "FLUSHED(1)"
            else -> "UNKNOWN($reason)"
        }

        fun deviceErrorName(error: Int): String = when (error) {
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE(1)"
            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE(2)"
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED(3)"
            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE(4) — fatal device error"
            CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE(5) — camera service crashed"
            else -> "UNKNOWN($error)"
        }
    }
}
