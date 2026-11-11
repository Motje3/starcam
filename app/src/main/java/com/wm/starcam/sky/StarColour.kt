package com.wm.starcam.sky

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * White from the stars themselves. The phone's colour comes from its auto white balance on a
 * one-second night frame - right for the lamp-lit houses, wrong for the sky: every star of 31 Oct
 * came out cyan (CORE_REVIEW.md, finding 4). Once the photo is solved each catalogue star's B-V
 * says what colour it really is, and a star like the Sun (B-V 0.65) is white: the gains on red and
 * blue that make it so are the photo's white balance for the sky.
 */
object StarColour {

    /** The Sun's B-V: a star of this colour comes out white. */
    const val SOLAR_BV = 0.65

    /**
     * How ln(r/g) and ln(b/g) change with B-V on a phone sensor, for a fit whose stars are too
     * alike in colour to measure it: the lead's prototype on the two real sessions of 31 Oct gave
     * +0.29 / +0.34 and -0.43 / -0.41.
     */
    const val FIXED_SLOPE_R = 0.30
    const val FIXED_SLOPE_B = -0.42

    const val MIN_STARS = 12
    const val MIN_BV_RANGE = 0.6

    /**
     * How well a star must be measured for its colour to count, as the engine's "snr" (its green
     * light over the noise of single pixels: the light of a faint star is in truth two to three
     * times less sure than that says, red and blue less still). The best stars first; the wider
     * set only when they are too few or do not agree well enough. Fainter ones are never used:
     * the logarithm of a noisy ratio leans one way, and they pulled the simulated sky's white 4 %
     * off where the stars of snr 30 and more were right to 1 %.
     */
    val SNR_TIERS = doubleArrayOf(30.0, 20.0)

    /** How well the white point must be known: 3 %, about what an eye can tell. */
    const val MAX_ERROR = 0.03

    /** Stars scattering more than this about the fit are not telling one story (cloud, a wrong match). */
    const val MAX_SCATTER = 0.25

    /** How far a sensor's slopes may lie from the fixed ones (the two real sessions: within 0.04). */
    const val SLOPE_DOUBT = 0.1

    const val MIN_GAIN = 0.5
    const val MAX_GAIN = 2.0

    /** A matched star's colour: its catalogue B-V, the light the stack measured per channel, how well. */
    class Sample(val bv: Double, val r: Double, val g: Double, val b: Double, val snr: Double)

    /**
     * The gains the stars give ([wbR], [wbB] on red and blue, green 1), how many stars gave them
     * (of [snrCut] and more), how far they scatter about the fit, how well the white point itself
     * is known ([errorR], [errorB], in ln: 0.01 is 1 %), and the slopes ([freeSlopes] false: held
     * at the fixed ones, the stars' B-V too alike to measure them).
     */
    class Fit(
        val wbR: Double,
        val wbB: Double,
        val stars: Int,
        val scatterR: Double,
        val scatterB: Double,
        val errorR: Double,
        val errorB: Double,
        val slopeR: Double,
        val slopeB: Double,
        val freeSlopes: Boolean,
        val bvRange: Double,
        val snrCut: Double,
    )

    /**
     * The stars a colour can be read from: a catalogue B-V, not blown out (a clipped core has no
     * colour left), measured well enough for the widest tier, light in every channel.
     */
    fun samples(matched: List<MatchedStar>): List<Sample> = matched.mapNotNull { m ->
        val s = m.star
        val bv = m.catalog.bv.toDouble()
        if (bv.isNaN() || s.sat || s.snr < SNR_TIERS.last() || !(s.r > 0) || !(s.g > 0) || !(s.b > 0)) null
        else Sample(bv, s.r, s.g, s.b, s.snr)
    }

    /**
     * The gains from the best-measured stars that give them well enough: each tier of
     * [SNR_TIERS] in turn, the first whose fit holds ([fitOf]). Null: no gains.
     */
    fun fit(samples: List<Sample>): Fit? {
        for (cut in SNR_TIERS) {
            val tier = samples.filter { it.snr >= cut }
            if (tier.size >= MIN_STARS) fitOf(tier, cut)?.let { return it }
        }
        return null
    }

    /**
     * ln(r/g) = aR + sR (B-V - 0.65) and ln(b/g) = aB + sB (B-V - 0.65), robustly; the gains that
     * make a solar-type star white are exp(-aR) and exp(-aB). With 12 stars or more over a B-V
     * range of 0.6 the slopes are fitted; with a narrower range they are held at the fixed ones
     * and only the offsets are. What decides is how well the white point is known, not how much
     * single stars scatter: many faint stars scatter widely and still fix it well, a dozen that
     * agree loosely do not. So the standard error of aR and aB must be under [MAX_ERROR] (with a
     * held slope: its doubt, [SLOPE_DOUBT], times how far the stars' mean colour lies from the
     * Sun's, added), and the scatter under [MAX_SCATTER]. Null otherwise.
     */
    fun fitOf(samples: List<Sample>, snrCut: Double = 0.0): Fit? {
        if (samples.size < MIN_STARS) return null
        val x = DoubleArray(samples.size) { samples[it].bv }
        val yR = DoubleArray(samples.size) { ln(samples[it].r / samples[it].g) }
        val yB = DoubleArray(samples.size) { ln(samples[it].b / samples[it].g) }
        val range = (x.maxOrNull() ?: 0.0) - (x.minOrNull() ?: 0.0)
        val free = range >= MIN_BV_RANGE
        val lineR = (if (free) RobustFit.line(x, yR, SOLAR_BV) else RobustFit.offset(x, yR, SOLAR_BV, FIXED_SLOPE_R))
            ?: return null
        val lineB = (if (free) RobustFit.line(x, yB, SOLAR_BV) else RobustFit.offset(x, yB, SOLAR_BV, FIXED_SLOPE_B))
            ?: return null
        val held = if (free) 0.0 else SLOPE_DOUBT * abs(x.average() - SOLAR_BV)
        val errorR = sqrt(lineR.error * lineR.error + held * held)
        val errorB = sqrt(lineB.error * lineB.error + held * held)
        if (!(lineR.scatter < MAX_SCATTER) || !(lineB.scatter < MAX_SCATTER)) return null
        if (!(errorR < MAX_ERROR) || !(errorB < MAX_ERROR)) return null
        return Fit(
            clampGain(exp(-lineR.a)), clampGain(exp(-lineB.a)), minOf(lineR.used, lineB.used),
            lineR.scatter, lineB.scatter, errorR, errorB, lineR.s, lineB.s, free, range, snrCut,
        )
    }

    fun clampGain(g: Double): Double = if (g.isFinite()) g.coerceIn(MIN_GAIN, MAX_GAIN) else 1.0

    /**
     * The sensor's own star balance (red, blue), for a night that does not solve: the session's
     * camera gains times the gains its stars gave. On the phone of 31 Oct R 2.11 / B 1.69 in two
     * sessions of different pointing, the same within 0.5 %.
     */
    fun absolute(cameraR: Double, cameraB: Double, fit: Fit): Pair<Double, Double> = cameraR * fit.wbR to cameraB * fit.wbB

    /** A remembered sensor balance as gains for a session shot at [cameraR], [cameraB]: clamped like a fit's. */
    fun fromRemembered(absR: Double, absB: Double, cameraR: Double, cameraB: Double): Pair<Double, Double> =
        clampGain(absR / cameraR) to clampGain(absB / cameraB)

    /** The remembered balance after one more measurement: smoothed, the first one taken as it is. */
    fun learn(stored: Pair<Double, Double>?, measured: Pair<Double, Double>): Pair<Double, Double> =
        if (stored == null) measured
        else (0.7 * stored.first + 0.3 * measured.first) to (0.7 * stored.second + 0.3 * measured.second)
}
