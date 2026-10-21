package com.wm.starcam.camera

import android.hardware.camera2.CaptureResult
import com.wm.starcam.engine.AnalyzeStats
import com.wm.starcam.engine.NativeEngine
import com.wm.starcam.util.AppLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Star autofocus (PLAN.md §5.2): sweep LENS_FOCUS_DISTANCE, measure real star FWHM per position
 * via the native analyzer, parabola-refine, lock the sharpest. The lens's "0.0 = infinity" is
 * rarely true infinity, so this beats any fixed setting.
 */
object FocusSweep {

    data class Result(
        val diopters: Float,
        val fwhm: Float,
        val starCount: Int,
        val ok: Boolean,
        val message: String,
    )

    private const val EXPOSURE_NS = 2_000_000_000L
    private const val SETTLE_EXPOSURE_NS = 200_000_000L
    private const val ISO = 6400
    private const val MIN_STARS = 8

    /**
     * Rough cost of one sweep point: the settle frame, the exposure and the native FWHM measure.
     * Drives the UI clock.
     */
    const val STEP_DURATION_MS = (SETTLE_EXPOSURE_NS + EXPOSURE_NS) / 1_000_000L + 2_000L

    /**
     * Coarse 7 points over [0, 0.30] diopters, then 3-point fine pass around the vertex.
     * [isCancelled] is polled between exposures so tapping Cancel doesn't wait out the whole
     * ~30 s sweep.
     */
    suspend fun run(
        controller: CameraController,
        wb: WbLock?,
        isCancelled: () -> Boolean = { false },
        onProgress: (step: Int, total: Int, label: String) -> Unit,
    ): Result {
        val cam = controller.cam
        if (cam.minFocusDistance <= 0f) {
            return Result(0f, 0f, 0, false, "fixed-focus lens; using infinity")
        }
        val coarse = FloatArray(7) { it * 0.05f }  // 0.00 .. 0.30
        val measured = ArrayList<Pair<Float, AnalyzeStats>>()
        val total = coarse.size + 3
        for ((i, d) in coarse.withIndex()) {
            if (isCancelled()) return Result(0f, 0f, 0, false, "autofocus cancelled")
            // The sweep starts at infinity, where stars are detectable if they exist at all.
            // Three starless positions in = cloudy/starless/blocked sky; the remaining points
            // cannot rescue it, so stop burning ~2s exposures and fall back now.
            if (i == 3 && measured.isEmpty()) {
                AppLog.d("FocusSweep: no stars in first 3 positions — aborting sweep early")
                return Result(0f, 0f, 0, false,
                    "no stars visible (clouds? blocked?) — using infinity")
            }
            onProgress(i + 1, total, "coarse %.2f".format(d))
            val stats = measureAt(controller, d, wb) ?: continue
            if (stats.starCount >= MIN_STARS) measured.add(d to stats)
        }
        if (measured.size < 3) {
            AppLog.d("FocusSweep: not enough stars (${measured.size} usable points) — falling back to infinity")
            return Result(0f, 0f, measured.maxOfOrNull { it.second.starCount } ?: 0, false,
                "not enough stars for autofocus; locked to infinity (0.0)")
        }
        // Vertex of the parabola through the best point and its neighbors.
        val bestIdx = measured.indices.minByOrNull { measured[it].second.medianFwhm }!!
        val vertex = parabolaVertex(measured, bestIdx)
        // Fine pass around the vertex.
        val fine = floatArrayOf(
            (vertex - 0.02f).coerceAtLeast(0f), vertex.coerceAtLeast(0f), vertex + 0.02f
        )
        for ((i, d) in fine.withIndex()) {
            if (isCancelled()) break
            onProgress(coarse.size + i + 1, total, "fine %.3f".format(d))
            val stats = measureAt(controller, d, wb) ?: continue
            if (stats.starCount >= MIN_STARS) measured.add(d to stats)
        }
        val best = measured.minByOrNull { it.second.medianFwhm }!!
        return Result(
            best.first, best.second.medianFwhm, best.second.starCount, true,
            "focus locked at %.3f dpt, FWHM %.2f px, %d stars"
                .format(best.first, best.second.medianFwhm, best.second.starCount)
        )
    }

    private fun parabolaVertex(pts: List<Pair<Float, AnalyzeStats>>, bestIdx: Int): Float {
        val i0 = (bestIdx - 1).coerceAtLeast(0)
        val i2 = (bestIdx + 1).coerceAtMost(pts.size - 1)
        if (i0 == bestIdx || i2 == bestIdx) return pts[bestIdx].first
        val (x0, s0) = pts[i0]; val (x1, s1) = pts[bestIdx]; val (x2, s2) = pts[i2]
        val y0 = s0.medianFwhm; val y1 = s1.medianFwhm; val y2 = s2.medianFwhm
        val denom = (x0 - x1) * (x0 - x2) * (x1 - x2)
        if (kotlin.math.abs(denom) < 1e-9f) return x1
        val a = (x2 * (y1 - y0) + x1 * (y0 - y2) + x0 * (y2 - y1)) / denom
        val b = (x2 * x2 * (y0 - y1) + x1 * x1 * (y2 - y0) + x0 * x0 * (y1 - y2)) / denom
        if (a <= 0f) return x1  // not a minimum; keep the measured best
        return (-b / (2 * a)).coerceIn(0f, 0.5f)
    }

    private suspend fun measureAt(
        controller: CameraController, diopters: Float, wb: WbLock?
    ): AnalyzeStats? {
        val cam = controller.cam
        return runCatching {
            // The lens travels to a new position at the start of the frame that asks for it, and
            // a HAL with control latency gets there a frame later still. A short throwaway frame
            // takes that move, so the measured exposure is spent where it is scored.
            runCatching {
                controller.captureRaw(
                    exposureNs = SETTLE_EXPOSURE_NS.coerceIn(cam.exposureRangeNs.lower, cam.exposureRangeNs.upper),
                    iso = ISO.coerceIn(cam.isoRange.lower, cam.isoRange.upper),
                    focusDistance = diopters,
                    wb = wb,
                    label = "focus %.3f settle".format(diopters),
                ).close()
            }.onFailure { AppLog.w("focus", "settle frame at $diopters failed: ${it.message}") }
            var shot = controller.captureRaw(
                exposureNs = EXPOSURE_NS.coerceIn(cam.exposureRangeNs.lower, cam.exposureRangeNs.upper),
                iso = ISO.coerceIn(cam.isoRange.lower, cam.isoRange.upper),
                focusDistance = diopters,
                wb = wb,
                label = "focus %.3f".format(diopters),
            )
            if (shot.result.get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_MOVING) {
                AppLog.i("focus", "%.3f dpt: the lens was still moving — measuring again".format(diopters))
                shot.close()
                shot = controller.captureRaw(
                    exposureNs = EXPOSURE_NS.coerceIn(cam.exposureRangeNs.lower, cam.exposureRangeNs.upper),
                    iso = ISO.coerceIn(cam.isoRange.lower, cam.isoRange.upper),
                    focusDistance = diopters,
                    wb = wb,
                    label = "focus %.3f again".format(diopters),
                )
            }
            try {
                val plane = shot.image.planes[0]
                val gains = CamMeta.rasterWbGains(wb?.gains, cam.cfa)
                val meta = JSONObject().apply {
                    put("cfa", cam.cfa)
                    put("whiteLevel", cam.whiteLevel)
                    put("blackLevel", JSONArray(cam.blackLevelRaster.map { it.toDouble() }))
                    put("wbGains", JSONArray(gains.map { it.toDouble() }))
                }.toString()
                val json = NativeEngine.analyzeFrame(
                    plane.buffer, shot.image.width, shot.image.height, plane.rowStride, meta
                )
                AnalyzeStats.fromJson(json).also {
                    AppLog.i("focus", "%.3f dpt: %d stars, fwhm %.2f px, bg %.2f%%".format(
                        diopters, it.starCount, it.medianFwhm, it.bgFrac * 100))
                }
            } finally {
                shot.close()
            }
        }.onFailure { AppLog.e("focus", "measure failed at $diopters", it) }.getOrNull()
    }
}
