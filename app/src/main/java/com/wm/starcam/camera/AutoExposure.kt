package com.wm.starcam.camera

import com.wm.starcam.engine.AnalyzeStats
import com.wm.starcam.util.Fmt
import kotlin.math.ceil

/**
 * Chooses exposure/ISO/frame count from the probe frame (PLAN.md §5.1), then checks the choice on
 * the first frame shot with it (the pilot, [refine]). Every output is shown in the HUD with its
 * reasoning and is overridable in the Pro sheet and Settings.
 */
object AutoExposure {

    data class Decision(
        val exposureNs: Long,
        val iso: Int,
        val frames: Int,
        val reasoning: String,
    )

    /**
     * What the exposure maths needs to know about the camera, as plain numbers ([CamInfo] holds
     * Android objects a unit test cannot build).
     */
    data class Limits(
        val isoMin: Int,
        val isoMax: Int,
        val exposureMinNs: Long,
        val exposureMaxNs: Long,
    ) {
        companion object {
            fun of(cam: CamInfo) = Limits(
                cam.isoRange.lower, cam.isoRange.upper, cam.exposureRangeNs.lower, cam.exposureRangeNs.upper,
            )
        }
    }

    /**
     * The pilot's verdict: the settings for the burst and whether the pilot itself is its first
     * frame (it is whenever the settings stay as they were).
     */
    data class Refinement(
        /** The settings for the burst; its reasoning ends with [note]. */
        val decision: Decision,
        val keepPilot: Boolean,
        /** What the pilot measured: its sky (share of white) and its stars. */
        val pilotBg: Double,
        val pilotStars: Int,
        /** The sky the burst's frames should have, share of white. */
        val predictedBg: Double,
        /** What the pilot found and changed: "sky measured 1.5% → ISO 1600". */
        val note: String,
    )

    private val ISO_STOPS = intArrayOf(200, 400, 800, 1600, 3200, 6400)

    /** Sky background target: 4% of white, linear. Below 2.5% or above 8% the pilot corrects it. */
    const val TARGET_BG = 0.04
    const val BAND_LOW = 0.025
    const val BAND_HIGH = 0.08

    /** The shortest exposure a bright sky is cut to; below this it is twilight, not night. */
    private const val MIN_BRIGHT_EXPOSURE_SEC = 1.0

    /** A pilot teaches the probe's offset only with this many stars and a sky below [LEARN_MAX_BG]. */
    const val LEARN_MIN_STARS = 20
    const val LEARN_MAX_BG = 0.15
    /** An offset outside 0..3% of white is a measurement gone wrong, not the probe's. */
    const val MAX_PROBE_OFFSET = 0.03

    fun decide(
        probe: AnalyzeStats,
        probeExposureNs: Long,
        probeIso: Int,
        cam: CamInfo,
        baseFrames: Int,
        trailCapSec: Double = 8.0,
        probeOffset: Double = 0.0,
    ): Decision = decide(probe, probeExposureNs, probeIso, Limits.of(cam), baseFrames, trailCapSec, probeOffset)

    /**
     * [baseFrames] comes from the quality preset (Fast 8 / Best 20 / Insane 40).
     * [trailCapSec] default 8s (~24mm equiv on a tripod; stacking tolerates slight residual trail).
     * [probeOffset] is what this camera's probe frame reads above the sky (share of white, learnt
     * from earlier pilots, [learnOffset]); it is taken off the probe's sky before scaling.
     */
    fun decide(
        probe: AnalyzeStats,
        probeExposureNs: Long,
        probeIso: Int,
        cam: Limits,
        baseFrames: Int,
        trailCapSec: Double = 8.0,
        probeOffset: Double = 0.0,
    ): Decision {
        val maxExpSec = cam.exposureMaxNs / 1e9
        val expSec = minOf(maxExpSec, trailCapSec).coerceAtLeast(cam.exposureMinNs / 1e9)
        val exposureNs = (expSec * 1e9).toLong()

        // Device caps exposure below the trail limit -> recover total integration with more frames.
        val expScale = if (expSec < trailCapSec) {
            ceil(trailCapSec / expSec).toInt().coerceAtMost(6)
        } else 1
        val frames = (baseFrames * expScale).coerceAtMost(99)

        // Scale ISO so the sky background hits TARGET_BG at the chosen exposure. The probe's own
        // offset comes off first, but never more than 90% of what it read: an offset learnt on a
        // darker night must not turn tonight's sky into nothing.
        val measured = probe.bgFrac.toDouble()
        val offset = probeOffset.coerceAtLeast(0.0)
        val bg = (measured - offset).coerceAtLeast(0.1 * measured).coerceAtLeast(1e-5)
        val probeExpSec = probeExposureNs / 1e9
        val ideal = probeIso * (TARGET_BG / bg) * (probeExpSec / expSec)

        // A sky too bright for the full exposure even at the lowest ISO (city glow, moonlight)
        // used to get it anyway, background far over target and star cores clipped. The
        // exposure is shortened instead, with more frames for the same total time.
        val isoFloor = maxOf(ISO_STOPS.first(), cam.isoMin)
        var finalExpSec = expSec
        var finalFrames = frames
        var brightNote: String? = null
        if (ideal < isoFloor) {
            val minExpSec = maxOf(MIN_BRIGHT_EXPOSURE_SEC, cam.exposureMinNs / 1e9)
            val shorter = (expSec * ideal / isoFloor).coerceIn(minOf(minExpSec, expSec), expSec)
            if (shorter < expSec * 0.9) {
                finalExpSec = shorter
                finalFrames = (frames * (expSec / shorter)).toInt().coerceIn(frames, 99)
                brightNote = "bright sky: ${"%.1f".format(shorter)}s instead of ${"%.1f".format(expSec)}s"
            }
        }
        val finalExposureNs = (finalExpSec * 1e9).toLong()
        val idealAtFinal = ideal * (expSec / finalExpSec)
        var iso = ISO_STOPS.minByOrNull { kotlin.math.abs(it - idealAtFinal) } ?: 800
        // Prefer headroom for star cores when the scene allows it.
        if (iso > 1600 && bg > 0.02) iso = 1600
        iso = iso.coerceIn(cam.isoMin, cam.isoMax)

        val limitNote = when {
            brightNote != null -> brightNote
            maxExpSec < trailCapSec -> "device caps exposure at ${"%.1f".format(maxExpSec)}s ×$expScale frames"
            else -> "trail-limited"
        }
        val offsetNote = if (bg < measured) " (less ${"%.1f".format((measured - bg) * 100)}% probe offset)" else ""
        val reasoning = "${Fmt.exposure(finalExposureNs)} · ISO $iso · ${finalFrames}× — " +
            "sky ${"%.1f".format(measured * 100)}%$offsetNote→${(TARGET_BG * 100).toInt()}%, " +
            "${probe.starCount} stars, $limitNote"
        return Decision(finalExposureNs, iso, finalFrames, reasoning)
    }

    fun refine(decision: Decision, pilotBgFrac: Float, pilotStarCount: Int, cam: CamInfo): Refinement =
        refine(decision, pilotBgFrac, pilotStarCount, Limits.of(cam))

    /**
     * The closed loop: the pilot is the first frame shot at [decision]'s settings, and its sky
     * ([pilotBgFrac], share of white) says whether the probe's guess held. Inside the band
     * ([BAND_LOW]..[BAND_HIGH]) nothing changes and the pilot is the burst's first frame. Below
     * it the ISO goes up to the lowest stop that reaches the band (the top stop when none does);
     * above it, down to the highest stop still in the band, and when even the lowest stop is too
     * bright the exposure is shortened as in [decide] (never below 1 s), with more frames for
     * the same total time. One correction only, no second pilot: the prediction scales the
     * pilot's own sky, so it lands in the band unless the camera runs out of range.
     */
    fun refine(decision: Decision, pilotBgFrac: Float, pilotStarCount: Int, cam: Limits): Refinement {
        val bg = pilotBgFrac.toDouble()
        fun kept(note: String) = Refinement(
            decision.copy(reasoning = "${decision.reasoning} · $note"), keepPilot = true,
            pilotBg = bg, pilotStars = pilotStarCount, predictedBg = bg, note = note,
        )
        // A frame the engine could not measure (0, or worse) says nothing about the exposure.
        if (!(bg > 0.0) || bg.isInfinite()) return kept("sky not measured on the first frame")
        val measuredNote = "sky measured ${pct(bg)}%"
        if (bg in BAND_LOW..BAND_HIGH) return kept(measuredNote)
        // The stops decide() chooses from, as this camera can set them.
        val stops = ISO_STOPS.map { it.coerceIn(cam.isoMin, cam.isoMax) }.distinct().sorted()
        fun predicted(iso: Int) = bg * iso / decision.iso
        fun inBand(iso: Int) = predicted(iso) in BAND_LOW..BAND_HIGH
        var iso = decision.iso
        var exposureNs = decision.exposureNs
        var frames = decision.frames
        var exposureNote: String? = null
        if (bg < BAND_LOW) {
            // Dark: the lowest stop that brings the sky into the band, else the highest there is.
            // The lowest, not the nearest to the aim: headroom for the star cores, the same intent
            // as decide() keeping to ISO 1600 when 1600 will do.
            iso = stops.firstOrNull { it > decision.iso && inBand(it) } ?: maxOf(stops.last(), decision.iso)
        } else {
            // Bright: the highest stop still in the band, else the lowest there is and a shorter
            // exposure.
            val lower = stops.lastOrNull { it < decision.iso && inBand(it) }
            if (lower != null) {
                iso = lower
            } else {
                iso = minOf(stops.first(), decision.iso)
                val expSec = decision.exposureNs / 1e9
                val atFloor = predicted(iso)
                val minExpSec = maxOf(MIN_BRIGHT_EXPOSURE_SEC, cam.exposureMinNs / 1e9)
                val shorter = (expSec * TARGET_BG / atFloor).coerceIn(minOf(minExpSec, expSec), expSec)
                if (shorter < expSec * 0.9) {
                    exposureNs = (shorter * 1e9).toLong()
                    frames = (decision.frames * (expSec / shorter)).toInt().coerceIn(decision.frames, 99)
                    exposureNote = "${Fmt.exposure(exposureNs)} ×$frames"
                }
            }
        }
        // Nothing this camera can set would do better: the pilot stands as the first frame.
        if (iso == decision.iso && exposureNs == decision.exposureNs) {
            return kept("$measuredNote, " + if (bg < BAND_LOW) "ISO $iso is this camera's highest" else "ISO $iso is the lowest")
        }
        val predictedBg = bg * (iso.toDouble() / decision.iso) * (exposureNs.toDouble() / decision.exposureNs)
        val change = listOfNotNull(if (iso != decision.iso) "ISO $iso" else null, exposureNote).joinToString(", ")
        val note = "$measuredNote → $change"
        return Refinement(
            decision.copy(exposureNs = exposureNs, iso = iso, frames = frames,
                reasoning = "${decision.reasoning} · $note"),
            keepPilot = false, pilotBg = bg, pilotStars = pilotStarCount, predictedBg = predictedBg, note = note,
        )
    }

    /** A sky level in percent, with a second decimal where a dark sky needs it. */
    private fun pct(frac: Double): String = if (frac < 0.01) "%.2f".format(frac * 100) else "%.1f".format(frac * 100)

    /**
     * The offset (share of white) a pilot implies in the probe frame: what the probe read beyond
     * the pilot's sky scaled back to the probe's exposure and ISO. Null when this pilot cannot
     * tell: too few stars (not a clear night sky), a sky too bright, or an answer outside 0..3%.
     */
    fun impliedProbeOffset(
        probeBg: Double, probeExposureNs: Long, probeIso: Int,
        pilotBg: Double, pilotExposureNs: Long, pilotIso: Int, pilotStars: Int,
    ): Double? {
        if (pilotStars < LEARN_MIN_STARS || !(pilotBg > 0.0) || pilotBg >= LEARN_MAX_BG) return null
        if (probeExposureNs <= 0L || pilotExposureNs <= 0L || probeIso <= 0 || pilotIso <= 0) return null
        val scale = (probeIso.toDouble() * probeExposureNs) / (pilotIso.toDouble() * pilotExposureNs)
        val offset = probeBg - pilotBg * scale
        return offset.takeIf { it in 0.0..MAX_PROBE_OFFSET }
    }

    /** The stored offset after one more measurement: smoothed, the first one taken as it is. */
    fun learnOffset(stored: Double?, measured: Double): Double =
        if (stored == null) measured else 0.7 * stored + 0.3 * measured
}
