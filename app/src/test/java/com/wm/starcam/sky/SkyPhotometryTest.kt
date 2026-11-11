package com.wm.starcam.sky

import com.wm.starcam.darksky.SkyQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.pow
import kotlin.random.Random

/** The zero point and the sky's brightness, from stars made to a known answer. */
class SkyPhotometryTest {

    private val zp = 3.45
    private val colourTerm = -0.12

    /** The light a star of [v], [bv] leaves at the known zero point, [noise] mag off. */
    private fun green(v: Double, bv: Double, noise: Double) = 10.0.pow((zp + colourTerm * (bv - 0.65) - v + noise) / 2.5)

    private fun star(g: Double, snr: Double = 50.0, sat: Boolean = false, sky: Boolean = true) =
        StackStar(0.0, 0.0, g, g, g, g, 0.3, snr, 4.4, 0.0137, 6, 7, 7, sat, sky, sky)

    private fun sky(stars: List<StackStar>, skyG: Double) =
        SkyStars(3072, 4096, 90, 8, 4.4, doubleArrayOf(0.8 * skyG, skyG, 1.2 * skyG), stars)

    /** [n] catalogue stars, V 1.5..5, B-V -0.2..1.6, measured [noise] mag off, a few wild ones. */
    private fun matched(n: Int, noise: Double, wild: Int, seed: Int): List<MatchedStar> {
        val rnd = Random(seed)
        return List(n) { i ->
            val v = 1.5 + 3.5 * rnd.nextDouble()
            val bv = -0.2 + 1.8 * rnd.nextDouble()
            val off = noise * (rnd.nextDouble() * 2 - 1) + if (i < wild) 0.8 else 0.0
            MatchedStar(Star(0.0, 0.0, v.toFloat(), bv.toFloat(), null), star(green(v, bv, off)), 0.5)
        }
    }

    @Test
    fun recoversTheZeroPointAndTheSky() {
        val m = matched(50, 0.1, 5, 1)
        val skyG = 0.0137
        val scale = 2809.0  // px per radian
        val p = SkyPhotometry.measure(m, sky(m.map { it.star }, skyG), scale)
        assertNotNull(p)
        p!!
        assertEquals(zp, p.zp, 0.03)
        assertEquals(colourTerm, p.colourTerm, 0.05)
        val arcsec = 206264.806 / scale
        val expected = zp - 2.5 * log10(skyG / (arcsec * arcsec))
        assertEquals(expected, p.magArcsec2, 0.04)
        assertEquals(SkyQuality.bortle(p.magArcsec2), p.bortle)
        assertTrue("${p.stars} stars", p.stars in 40..50)
    }

    @Test
    fun aDarkerSkyReadsDarker() {
        val m = matched(30, 0.05, 0, 2)
        val town = SkyPhotometry.measure(m, sky(m.map { it.star }, 0.0137), 2800.0)!!
        val country = SkyPhotometry.measure(m, sky(m.map { it.star }, 0.0137 / 10), 2800.0)!!
        // Ten times less light from the sky: 2.5 magnitudes darker.
        assertEquals(2.5, country.magArcsec2 - town.magArcsec2, 1e-9)
        assertTrue(country.bortle < town.bortle)
    }

    @Test
    fun notEnoughStarsNoNumbers() {
        val m = matched(4, 0.05, 0, 3)
        assertNull(SkyPhotometry.measure(m, sky(m.map { it.star }, 0.01), 2800.0))
        // Blown out or poorly measured stars do not count.
        val poor = matched(20, 0.05, 0, 4).map { MatchedStar(it.catalog, star(it.star.g, snr = 19.0), 0.5) }
        assertNull(SkyPhotometry.measure(poor, sky(poor.map { it.star }, 0.01), 2800.0))
        val blown = matched(20, 0.05, 0, 4).map { MatchedStar(it.catalog, star(it.star.g, sat = true), 0.5) }
        assertNull(SkyPhotometry.measure(blown, sky(blown.map { it.star }, 0.01), 2800.0))
        val ok = matched(20, 0.05, 0, 4)
        assertNull("no sky level", SkyPhotometry.measure(ok, sky(ok.map { it.star }, 0.0), 2800.0))
    }
}
