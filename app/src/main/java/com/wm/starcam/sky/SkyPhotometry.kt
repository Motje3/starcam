package com.wm.starcam.sky

import com.wm.starcam.darksky.SkyQuality
import kotlin.math.log10

/**
 * The sky measured on the photo's own stars. The catalogue's V magnitudes against the light each
 * star left in the stack give the photo's zero point: the magnitude of one unit of light. The
 * sky's own level per pixel is then a magnitude too, and with the size of a pixel on the sky its
 * brightness in mag per square arcsecond, what a sky quality meter reads - for this direction and
 * this night, haze and Moon included.
 *
 * Not here: how faint the stack reached. The star list holds the brightest few hundred blobs, so
 * its faintest stars say where the list ends (magnitude 6 on 31 Oct), not where the photo does;
 * and the noise the engine reports is that of single pixels, which a demosaiced, aligned stack
 * shares between neighbours, so a limit worked out from it comes out about a magnitude too deep.
 * It wants the engine to measure the scatter of empty apertures.
 */
object SkyPhotometry {

    /** Radians to arcseconds. */
    private const val ARCSEC_PER_RAD = 206264.806

    const val MIN_STARS = 5

    /** The engine's "snr" from which a star's light counts (see [StarColour.SNR_TIERS]). */
    const val MIN_SNR = 20.0

    /**
     * [zp]: V of a solar-coloured star that left one unit of green light (V + 2.5 log10 g = zp +
     * [colourTerm] (B-V - 0.65)), from [stars] stars that scatter [scatter] mag about the fit.
     * [magArcsec2] the sky's brightness and [bortle] the class it reads as.
     */
    class Result(
        val zp: Double,
        val colourTerm: Double,
        val scatter: Double,
        val stars: Int,
        val magArcsec2: Double,
        val bortle: Int,
    )

    /**
     * [matched] (the catalogue's stars found in [sky]) through a solution of [sky]'s picture at
     * [scalePxPerRad] (its focal length in px). Null when too few stars are measured well enough.
     */
    fun measure(matched: List<MatchedStar>, sky: SkyStars, scalePxPerRad: Double): Result? {
        val good = matched.filter {
            val bv = it.catalog.bv
            !it.star.sat && it.star.snr >= MIN_SNR && it.star.g > 0 && !bv.isNaN() && it.catalog.mag.isFinite()
        }
        if (good.size < MIN_STARS || !(scalePxPerRad > 0)) return null
        val x = DoubleArray(good.size) { good[it].catalog.bv.toDouble() }
        val y = DoubleArray(good.size) { good[it].catalog.mag + 2.5 * log10(good[it].star.g) }
        val range = (x.maxOrNull() ?: 0.0) - (x.minOrNull() ?: 0.0)
        // The colour term only where the stars span the colours to measure it.
        val line = (if (good.size >= 8 && range >= StarColour.MIN_BV_RANGE) RobustFit.line(x, y, StarColour.SOLAR_BV)
        else null) ?: RobustFit.offset(x, y, StarColour.SOLAR_BV, 0.0) ?: return null
        val zp = line.a
        val skyG = sky.skyBg[1]
        if (!(skyG > 0) || !zp.isFinite()) return null
        val arcsecPerPx = ARCSEC_PER_RAD / scalePxPerRad
        val mag = zp - 2.5 * log10(skyG / (arcsecPerPx * arcsecPerPx))
        return Result(zp, line.s, line.scatter, line.used, mag, SkyQuality.bortle(mag))
    }
}
