package com.wm.starcam.camera

import com.wm.starcam.engine.AnalyzeStats
import com.wm.starcam.util.Fmt
import kotlin.math.ceil

/**
 * Chooses exposure/ISO/frame count from the probe frame (PLAN.md §5.1). Every output is shown in
 * the HUD with its reasoning and is overridable in the Pro panel.
 */
object AutoExposure {

    data class Decision(
        val exposureNs: Long,
        val iso: Int,
        val frames: Int,
        val reasoning: String,
    )

    private val ISO_STOPS = intArrayOf(200, 400, 800, 1600, 3200, 6400)
    private const val TARGET_BG = 0.06  // sky background target: 6% of white level, linear

    /**
     * [baseFrames] comes from the quality preset (Fast 8 / Best 20 / Insane 40).
     * [trailCapSec] default 8s (~24mm equiv on a tripod; stacking tolerates slight residual trail).
     */
    fun decide(
        probe: AnalyzeStats,
        probeExposureNs: Long,
        probeIso: Int,
        cam: CamInfo,
        baseFrames: Int,
        trailCapSec: Double = 8.0,
    ): Decision {
        val maxExpSec = cam.maxExposureSeconds
        val expSec = minOf(maxExpSec, trailCapSec).coerceAtLeast(cam.exposureRangeNs.lower / 1e9)
        val exposureNs = (expSec * 1e9).toLong()

        // Device caps exposure below the trail limit -> recover total integration with more frames.
        val expScale = if (expSec < trailCapSec) {
            ceil(trailCapSec / expSec).toInt().coerceAtMost(6)
        } else 1
        val frames = (baseFrames * expScale).coerceAtMost(99)

        // Scale ISO so the sky background hits TARGET_BG at the chosen exposure.
        val bg = probe.bgFrac.toDouble().coerceAtLeast(1e-5)
        val probeExpSec = probeExposureNs / 1e9
        val ideal = probeIso * (TARGET_BG / bg) * (probeExpSec / expSec)
        var iso = ISO_STOPS.minByOrNull { kotlin.math.abs(it - ideal) } ?: 800
        // Prefer headroom for star cores when the scene allows it.
        if (iso > 1600 && bg > 0.02) iso = 1600
        iso = iso.coerceIn(cam.isoRange.lower, cam.isoRange.upper)

        val limitNote = when {
            maxExpSec < trailCapSec -> "device caps exposure at ${"%.1f".format(maxExpSec)}s ×$expScale frames"
            else -> "trail-limited"
        }
        val reasoning = "${Fmt.exposure(exposureNs)} · ISO $iso · ${frames}× — " +
            "sky ${"%.1f".format(probe.bgFrac * 100)}%→${(TARGET_BG * 100).toInt()}%, " +
            "${probe.starCount} stars, $limitNote"
        return Decision(exposureNs, iso, frames, reasoning)
    }
}
