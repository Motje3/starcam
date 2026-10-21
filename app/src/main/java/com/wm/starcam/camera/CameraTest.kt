package com.wm.starcam.camera

import android.content.Context
import android.hardware.camera2.CaptureResult
import android.os.SystemClock
import com.wm.starcam.engine.NativeEngine
import com.wm.starcam.session.AppSettings
import com.wm.starcam.util.AppLog
import com.wm.starcam.util.Diagnostics
import com.wm.starcam.util.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A bare capture loop for reproducing camera failures at home instead of waiting for a clear
 * night: a probe frame with auto white balance, then N RAW frames at a fixed exposure with the
 * white balance locked — the same requests a real session sends, with the same Debug switches.
 * With [Params.simulateWork] each frame also gets the work a session does between exposures (the
 * native ingest into a scratch engine and a DNG write), so CPU, memory and storage pressure match.
 *
 * Indoors with the lens covered, 5 × 20 s replays the field failure in about two minutes, and
 * every request and callback lands in the log.
 */
object CameraTest {
    private const val TAG = "camtest"

    data class Params(
        val frames: Int = 5,
        val exposureMs: Long = 20_000L,
        val iso: Int = 800,
        /** Ingest each frame into a scratch engine and write a DNG, like a real session. */
        val simulateWork: Boolean = true,
        /** Repair the camera on failure like a session does; off shows the raw failure pattern. */
        val recovery: Boolean = false,
        // Per-run overrides of the Settings → Advanced switches (null = use the setting), so a PC can
        // A/B them over adb without touching the phone's settings.
        val previewInSession: Boolean? = null,
        val freshSession: Boolean? = null,
        val frameDuration: Boolean? = null,
        val shadingEveryFrame: Boolean? = null,
    ) {
        override fun toString() =
            "${frames}× ${Fmt.exposure(exposureMs * 1_000_000L)} ISO $iso, " +
                "work ${if (simulateWork) "on" else "off"}, recovery ${if (recovery) "on" else "off"}" +
                listOfNotNull(
                    previewInSession?.let { "preview-in-session=$it" },
                    freshSession?.let { "fresh-session=$it" },
                    frameDuration?.let { "frame-duration=$it" },
                    shadingEveryFrame?.let { "map-every-frame=$it" },
                ).joinToString("") { ", $it" }
    }

    data class FrameResult(val label: String, val ok: Boolean, val ms: Long, val detail: String)

    data class Status(
        val running: Boolean = false,
        val params: Params? = null,
        val current: String = "",
        val results: List<FrameResult> = emptyList(),
        val summary: String? = null,
    )

    suspend fun run(
        context: Context,
        controller: CameraController,
        s: AppSettings,
        p: Params,
        isCancelled: () -> Boolean,
        update: ((Status) -> Status) -> Unit,
    ) {
        val cam = controller.cam
        val rawSize = cam.rawSize(s.fullResRaw)
        val exposureNs = (p.exposureMs * 1_000_000L).coerceIn(cam.exposureRangeNs.lower, cam.exposureRangeNs.upper)
        val scratch = File(context.cacheDir, "camtest").apply { deleteRecursively(); mkdirs() }
        var handle = 0L
        var ok = 0
        var failed = 0
        var probeFailed = false
        var rawOnlyFallback = false
        val t0 = SystemClock.elapsedRealtime()
        fun record(r: FrameResult) {
            if (r.ok) AppLog.i(TAG, "${r.label}: ok in ${r.ms}ms ${r.detail}")
            else AppLog.e(TAG, "${r.label}: FAILED after ${r.ms}ms — ${r.detail}")
            update { it.copy(results = it.results + r) }
        }
        fun status(text: String) = update { it.copy(current = text) }

        val withPreview = p.previewInSession ?: s.debugPreviewInSession
        val fresh = p.freshSession ?: s.freshSessionAfterLong
        val mapEveryFrame = p.shadingEveryFrame ?: s.debugShadingEveryFrame
        AppLog.i(TAG, "==== camera test: $p · effective: preview-in-session=$withPreview " +
            "fresh-session=$fresh map-every-frame=$mapEveryFrame " +
            "frame-duration=${p.frameDuration ?: s.debugFrameDuration} ====")
        AppLog.i(TAG, "condition: ${Diagnostics.snapshot(context)}")
        update { Status(running = true, params = p, current = "preparing the camera") }
        try {
            controller.explicitFrameDuration = p.frameDuration ?: s.debugFrameDuration
            controller.freshSessionAfterLong = fresh
            controller.verbose = s.verboseCameraLog
            // A test meant to include the viewfinder stream must not quietly run without it: the
            // stream is one of the suspects, and a pass without it would clear it falsely.
            if (withPreview && !controller.hasLivePreview()) {
                status("waiting for the viewfinder")
                withTimeoutOrNull(5_000L) {
                    while (!controller.hasLivePreview()) delay(200)
                }
            }
            rawOnlyFallback = withPreview && !controller.hasLivePreview()
            if (rawOnlyFallback) AppLog.w(TAG, "no live viewfinder — this test runs RAW-only, not as configured")
            controller.stopPreview()
            controller.beginExclusive("camera test")
            controller.prepareCapture(rawSize, withPreview = withPreview)

            // Probe: same as a session's — auto WB, 1 s, and the only frame asking for the map.
            status("probe frame (auto white balance)")
            val probeExp = minOf(1_000_000_000L, cam.exposureRangeNs.upper)
            val probeStart = SystemClock.elapsedRealtime()
            val wb: WbLock? = try {
                val shot = controller.captureRaw(probeExp, 3200, 0f, null, "test probe",
                    wantShadingMap = s.lensShading)
                try {
                    val gains = shot.result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                    val transform = shot.result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
                    record(FrameResult("probe", true, SystemClock.elapsedRealtime() - probeStart,
                        "(lsc ${if (shot.result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) != null) "map" else "none"})"))
                    if (gains != null) WbLock(gains, transform) else null
                } finally {
                    shot.image.close()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                probeFailed = true
                record(FrameResult("probe", false, SystemClock.elapsedRealtime() - probeStart, t.message ?: t.javaClass.simpleName))
                null
            }

            if (p.simulateWork) {
                val config = JSONObject().apply {
                    put("sessionDir", scratch.absolutePath)
                    put("mode", 0)
                    put("width", rawSize.width)
                    put("height", rawSize.height)
                    put("cfa", cam.cfa)
                    put("whiteLevel", cam.whiteLevel)
                    put("blackLevel", JSONArray(cam.blackLevelRaster.map { it.toDouble() }))
                    put("keepCache", false)
                    put("livePreview", true)
                }.toString()
                handle = NativeEngine.createSession(config)
                if (handle == 0L) AppLog.w(TAG, "scratch engine could not start; continuing without ingest")
            }
            val wbRaster = CamMeta.rasterWbGains(wb?.gains, cam.cfa)

            var i = 0
            while (i < p.frames && !isCancelled()) {
                val label = "frame ${i + 1}/${p.frames}"
                val repairs = if (p.recovery) intArrayOf(1, 2, 3) else intArrayOf(0)
                var attempt = 0
                var shot: CapturedRaw? = null
                while (shot == null && !isCancelled()) {
                    val name = if (attempt == 0) label else "$label retry $attempt"
                    status("$name: exposing ${Fmt.exposure(exposureNs)}")
                    val start = SystemClock.elapsedRealtime()
                    try {
                        shot = controller.captureRaw(exposureNs, p.iso, 0f, wb, "test $name",
                            wantShadingMap = s.lensShading && mapEveryFrame)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        failed++
                        record(FrameResult(name, false, SystemClock.elapsedRealtime() - start,
                            t.message ?: t.javaClass.simpleName))
                        if (attempt >= repairs.size) break
                        val level = repairs[attempt++]
                        if (level > 0) {
                            status("$label: repairing the camera (level $level)")
                            runCatching { controller.recover(level, rawSize) }
                                .onFailure { AppLog.e(TAG, "repair level $level failed", it) }
                        }
                    }
                }
                val got = shot ?: break
                var detail = ""
                try {
                    if (handle != 0L) {
                        status("$label: native ingest (as in a session)")
                        val tIngest = SystemClock.elapsedRealtime()
                        val plane = got.image.planes[0]
                        val meta = JSONObject().apply {
                            put("index", i)
                            put("exposureNs", exposureNs)
                            put("iso", p.iso)
                            put("timestampNs", got.image.timestamp)
                            put("wbGains", JSONArray(wbRaster.map { it.toDouble() }))
                            put("ccm", JSONArray(CamMeta.ccmToFloatArray(wb?.transform).map { it.toDouble() }))
                            put("gyroRms", 0.0)
                        }.toString()
                        NativeEngine.addFrame(handle, plane.buffer, got.image.width, got.image.height,
                            plane.rowStride, meta)
                        detail += "ingest ${SystemClock.elapsedRealtime() - tIngest}ms"
                        status("$label: writing DNG (as in a session)")
                        val tDng = SystemClock.elapsedRealtime()
                        DngWriter.write(cam, got, File(scratch, "test_%02d.dng".format(i)))
                        detail += " · dng ${SystemClock.elapsedRealtime() - tDng}ms"
                    }
                } finally {
                    got.image.close()
                }
                ok++
                record(FrameResult(label, true, got.elapsedMs, detail))
                i++
            }
            val verdict = when {
                isCancelled() -> "stopped by you: $ok frame(s) ok, $failed failure(s)"
                ok == p.frames && failed == 0 -> "PASS — all ${p.frames} frames ok"
                ok == p.frames -> "PASS with repairs — ${p.frames} frames ok, $failed failed attempt(s) recovered"
                else -> "FAIL — $ok of ${p.frames} frames ok, $failed failure(s). See the log for the camera trace."
            }
            // Caveats that change what the verdict means, said in the verdict itself.
            val summary = verdict +
                (if (probeFailed) " · probe frame FAILED (frames shot with auto white balance)" else "") +
                (if (rawOnlyFallback) " · ran RAW-only (no live viewfinder)" else "")
            AppLog.i(TAG, "==== camera test finished in ${(SystemClock.elapsedRealtime() - t0) / 1000}s: $summary ====")
            update { it.copy(running = false, current = "", summary = summary) }
        } catch (t: Throwable) {
            AppLog.e(TAG, "camera test aborted — ${controller.dumpState()}", t)
            update {
                it.copy(running = false, current = "",
                    summary = "ABORTED — ${t.message ?: t.javaClass.simpleName}")
            }
            if (t is CancellationException) throw t
        } finally {
            controller.endExclusive()
            if (handle != 0L) NativeEngine.destroySession(handle)
            scratch.deleteRecursively()
        }
    }
}
