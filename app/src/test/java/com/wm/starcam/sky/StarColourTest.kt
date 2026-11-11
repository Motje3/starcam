package com.wm.starcam.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * The white balance from the stars' colours, on stars made to a known answer: a camera whose
 * balance leaves a solar-type star at r/g 1/1.33 and b/g 1/0.74, with noise, blended and variable
 * stars among them.
 */
class StarColourTest {

    private val wbR = 1.33
    private val wbB = 0.74
    private val slopeR = 0.31
    private val slopeB = -0.40

    /**
     * [n] stars with B-V spread over [bvFrom]..[bvTo], [noise] in ln ratio, [outliers] wild ones,
     * measured as well as [snr] says; [lean]: how far the noise pulls ln(r/g) one way (faint stars'
     * do).
     */
    private fun stars(
        n: Int, bvFrom: Double, bvTo: Double, noise: Double, outliers: Int, seed: Int, snr: Double = 60.0, lean: Double = 0.0,
    ): List<StarColour.Sample> {
        val rnd = Random(seed)
        return List(n) { i ->
            val bv = bvFrom + (bvTo - bvFrom) * rnd.nextDouble()
            val g = 5.0 + 50 * rnd.nextDouble()
            var lr = -ln(wbR) + slopeR * (bv - StarColour.SOLAR_BV) + noise * gauss(rnd) + lean
            var lb = -ln(wbB) + slopeB * (bv - StarColour.SOLAR_BV) + noise * gauss(rnd)
            if (i < outliers) {
                // A star blended with a neighbour of another colour, or one the catalogue mistakes.
                lr += if (rnd.nextBoolean()) 0.6 else -0.7
                lb += if (rnd.nextBoolean()) 0.8 else -0.5
            }
            StarColour.Sample(bv, g * exp(lr), g, g * exp(lb), snr)
        }
    }

    private fun gauss(r: Random): Double {
        val u1 = 1.0 - r.nextDouble()
        val u2 = r.nextDouble()
        return kotlin.math.sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }

    @Test
    fun recoversTheGainsThroughNoiseAndOutliers() {
        for (seed in 1..5) {
            val fit = StarColour.fit(stars(50, -0.2, 1.6, 0.06, 6, seed))
            assertNotNull("seed $seed", fit)
            fit!!
            assertTrue(fit.freeSlopes)
            assertEquals("seed $seed wbR", wbR, fit.wbR, wbR * 0.03)
            assertEquals("seed $seed wbB", wbB, fit.wbB, wbB * 0.03)
            assertEquals(slopeR, fit.slopeR, 0.05)
            assertEquals(slopeB, fit.slopeB, 0.05)
            // About the noise (0.06), the wild ones widening it a little: still well inside the limit.
            assertTrue("scatter ${fit.scatterR} ${fit.scatterB}", fit.scatterR in 0.04..0.12 && fit.scatterB in 0.04..0.12)
            // The wild ones were not counted.
            assertTrue("${fit.stars} stars", fit.stars in 40..50)
            // The white point is known far better than a single star scatters: to about 1 %.
            assertTrue("known to ${fit.errorR} ${fit.errorB}", fit.errorR in 0.004..0.02 && fit.errorB in 0.004..0.02)
        }
    }

    @Test
    fun refusesTooFewStars() {
        assertNull(StarColour.fit(stars(11, -0.2, 1.6, 0.03, 0, 7)))
        assertNotNull(StarColour.fit(stars(12, -0.2, 1.6, 0.03, 0, 7)))
        assertNull(StarColour.fit(emptyList()))
    }

    @Test
    fun refusesStarsThatDoNotAgree() {
        // Thin cloud, a bad match: the colours say nothing.
        assertNull(StarColour.fit(stars(40, -0.2, 1.6, 0.4, 0, 8)))
    }

    @Test
    fun whatCountsIsHowWellTheWhitePointIsKnown() {
        // Many stars that each scatter 15 % still fix the white point to 2 %...
        for (seed in 20..24) {
            val many = StarColour.fit(stars(90, -0.2, 1.6, 0.15, 0, seed))
            assertNotNull("seed $seed", many)
            assertEquals("seed $seed wbR", wbR, many!!.wbR, wbR * 0.06)
            assertEquals("seed $seed wbB", wbB, many.wbB, wbB * 0.06)
            assertTrue("known to ${many.errorR} ${many.errorB}", many.errorR < StarColour.MAX_ERROR && many.errorB < StarColour.MAX_ERROR)
        }
        // ...a dozen that scatter as much do not: 4 % and more is a colour cast one can see.
        var refused = 0
        for (seed in 30..39) if (StarColour.fit(stars(13, -0.2, 1.6, 0.15, 0, seed)) == null) refused++
        assertTrue("$refused of 10 refused", refused >= 8)
    }

    @Test
    fun theBestMeasuredStarsDecide() {
        // Thirty bright stars that agree, sixty faint ones whose noise leans one way (the logarithm
        // of a noisy ratio does): the faint ones are left out and the white is right.
        val bright = stars(30, -0.2, 1.6, 0.03, 0, 40, snr = 80.0)
        val faint = stars(60, -0.2, 1.6, 0.2, 0, 41, snr = 22.0, lean = -0.12)
        val fit = StarColour.fit(bright + faint)!!
        assertEquals(30.0, fit.snrCut, 0.0)
        assertTrue("${fit.stars} stars", fit.stars <= 30)
        assertEquals(wbR, fit.wbR, wbR * 0.02)
        // Too few bright ones: the wider set, when it holds.
        val few = StarColour.fit(stars(8, -0.2, 1.6, 0.03, 0, 42, snr = 80.0) + stars(40, -0.2, 1.6, 0.06, 0, 43, snr = 24.0))!!
        assertEquals(20.0, few.snrCut, 0.0)
        assertTrue("${few.stars} stars", few.stars > 30)
        // Stars measured worse than the widest tier never count.
        assertNull(StarColour.fit(stars(60, -0.2, 1.6, 0.03, 0, 44, snr = 15.0)))
    }

    @Test
    fun aNarrowRangeHoldsTheSlopes() {
        // Only yellowish stars: their spread cannot fix a slope, the offsets still hold.
        val fit = StarColour.fit(stars(30, 0.5, 0.95, 0.04, 2, 9))
        assertNotNull(fit)
        fit!!
        assertFalse(fit.freeSlopes)
        assertEquals(StarColour.FIXED_SLOPE_R, fit.slopeR, 0.0)
        assertEquals(StarColour.FIXED_SLOPE_B, fit.slopeB, 0.0)
        // The fixed slopes are a little off this camera's, at B-V 0.65 that does not matter.
        assertEquals(wbR, fit.wbR, wbR * 0.03)
        assertEquals(wbB, fit.wbB, wbB * 0.03)
        assertTrue(fit.bvRange < StarColour.MIN_BV_RANGE)
        // Only blue stars, far from the Sun's colour: there a slope that is a little off this
        // sensor's is a white point that is off, and the stars cannot say by how much.
        assertNull(StarColour.fit(stars(30, -0.2, 0.3, 0.04, 0, 12)))
    }

    @Test
    fun theGainsStayWithinReason() {
        // A sensor wildly off: clamped to [0.5, 2].
        val rnd = Random(10)
        val s = List(20) {
            val bv = -0.2 + 1.8 * rnd.nextDouble()
            StarColour.Sample(bv, 10.0 * exp(-ln(3.0) + 0.3 * (bv - 0.65)), 10.0, 10.0 * exp(ln(4.0) - 0.4 * (bv - 0.65)), 60.0)
        }
        val fit = StarColour.fit(s)!!
        assertEquals(2.0, fit.wbR, 0.0)
        assertEquals(0.5, fit.wbB, 0.0)
        assertEquals(1.0, StarColour.clampGain(Double.NaN), 0.0)
    }

    @Test
    fun onlyStarsWithAColourToReadAreUsed() {
        fun m(bv: Float, sat: Boolean = false, snr: Double = 30.0, r: Double = 1.0, g: Double = 1.0, b: Double = 1.0) =
            MatchedStar(Star(0.0, 0.0, 3f, bv, null),
                StackStar(0.0, 0.0, g, r, g, b, 0.2, snr, 4.0, 0.01, 5, 5, 5, sat, true, true), 0.5)
        val picked = StarColour.samples(listOf(
            m(0.6f), m(Float.NaN), m(0.6f, sat = true), m(0.6f, snr = 19.0), m(0.6f, b = 0.0), m(0.6f, r = -0.1),
        ))
        assertEquals(1, picked.size)
        assertEquals(30.0, picked[0].snr, 0.0)
    }

    @Test
    fun theCamerasOwnBalanceIsRememberedAndUsed() {
        val fit = StarColour.fit(stars(40, -0.2, 1.6, 0.03, 0, 11))!!
        // The camera's guess on 31 Oct, times what the stars said: the sensor's own balance.
        val (absR, absB) = StarColour.absolute(1.58, 2.295, fit)
        assertEquals(1.58 * fit.wbR, absR, 1e-12)
        assertEquals(2.295 * fit.wbB, absB, 1e-12)
        // The first measurement as it is, then smoothed.
        val first = StarColour.learn(null, 2.11 to 1.69)
        assertEquals(2.11, first.first, 1e-12)
        val next = StarColour.learn(first, 2.21 to 1.59)
        assertEquals(0.7 * 2.11 + 0.3 * 2.21, next.first, 1e-12)
        assertEquals(0.7 * 1.69 + 0.3 * 1.59, next.second, 1e-12)
        // A night that does not solve, shot with another camera guess, gets the same star white.
        val (r, b) = StarColour.fromRemembered(2.11, 1.69, 1.57, 2.259)
        assertEquals(2.11 / 1.57, r, 1e-12)
        assertEquals(1.69 / 2.259, b, 1e-12)
        val (r2, b2) = StarColour.fromRemembered(2.11, 1.69, 0.5, 5.0)
        assertEquals(2.0, r2, 0.0)
        assertEquals(0.5, b2, 0.0)
    }
}
