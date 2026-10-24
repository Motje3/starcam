package com.wm.starcam.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Plate solving against synthetic photos made from the catalogue itself: a known pointing, turn
 * and focal length, positions blurred, stars lost behind trees and haze, false stars added.
 */
class PlateSolverTest {

    private val catalog = StarCatalog.parse(File("src/main/assets/sky/stars.txt").readText())

    private class Truth(
        val ra: Double, val dec: Double, val focal: Double, val angle: Double, val flipped: Boolean, val k: Double = 0.0,
    )

    /** The photo a phone would take: stars to [maxMag] in a [w] x [h] frame, jittered, some lost, some false. */
    private fun photo(t: Truth, w: Int, h: Int, seed: Int, maxMag: Float = 4.6f, lose: Double = 0.2, fakes: Int = 15): List<ImageStar> {
        val rnd = Random(seed)
        val out = ArrayList<ImageStar>()
        for (s in catalog.stars) {
            if (s.mag > maxMag) continue
            val (xi, eta) = PlateSolver.tangent(s.raDeg, s.decDeg, t.ra, t.dec) ?: continue
            val (x, y) = PlateSolver.toPixel(xi, eta, t.focal, t.angle, t.flipped, w / 2.0, h / 2.0, t.k)
            if (x < 0 || y < 0 || x >= w || y >= h) continue
            if (rnd.nextDouble() < lose) continue
            val flux = Math.pow(10.0, -0.4 * s.mag).toFloat() * (0.7f + 0.6f * rnd.nextFloat())
            out += ImageStar((x + rnd.nextDouble(-1.5, 1.5)).toFloat(), (y + rnd.nextDouble(-1.5, 1.5)).toFloat(), flux)
        }
        repeat(fakes) {
            out += ImageStar(rnd.nextFloat() * w, rnd.nextFloat() * h, (0.2f + rnd.nextFloat()) * 0.05f)
        }
        return out
    }

    private fun assertSolved(sol: PlateSolution?, t: Truth, w: Int, h: Int, tolPx: Double = 4.0) {
        assertNotNull(sol)
        // Deneb, Vega, Altair or whichever named bright star is in the frame lands on its pixel.
        var checked = 0
        for (s in catalog.stars.filter { it.mag < 2.5f }) {
            val (xi, eta) = PlateSolver.tangent(s.raDeg, s.decDeg, t.ra, t.dec) ?: continue
            val (x, y) = PlateSolver.toPixel(xi, eta, t.focal, t.angle, t.flipped, w / 2.0, h / 2.0, t.k)
            if (x < 50 || y < 50 || x > w - 50 || y > h - 50) continue
            val p = sol!!.project(s.raDeg, s.decDeg)!!
            assertTrue("${s.name ?: s.designation}: ${p.first},${p.second} vs $x,$y", hypot(p.first - x, p.second - y) < tolPx)
            checked++
        }
        assertTrue(checked >= 2)
        assertEquals(t.flipped, sol!!.flipped)
        assertEquals(t.focal, sol.scale, t.focal * 0.01)
    }

    @Test
    fun theSummerTriangleWithAHint() {
        // A 4000 x 3000 photo at 3000 px focal length (67 deg across), turned 40 deg.
        val t = Truth(300.0, 35.0, 3000.0, Math.toRadians(40.0), false)
        val stars = photo(t, 4000, 3000, 1)
        // The phone's compass and level put the centre 8 deg off.
        val sol = PlateSolver.solve(stars, 4000, 3000, catalog, focalPx = 3050.0, hint = 308.0 to 31.0)
        assertSolved(sol, t, 4000, 3000)
        assertTrue("matched ${sol!!.matched}", sol.matched >= 30)
        assertTrue("rms ${sol.rmsPx}", sol.rmsPx < 2.5)
    }

    @Test
    fun orionWithoutAnyHint() {
        // Old sessions recorded no pointing: the whole sky is searched.
        val t = Truth(84.0, 0.0, 3000.0, Math.toRadians(-100.0), false)
        val stars = photo(t, 4000, 3000, 2)
        val t0 = System.nanoTime()
        val sol = PlateSolver.solve(stars, 4000, 3000, catalog, focalPx = 2950.0, hint = null)
        val ms = (System.nanoTime() - t0) / 1e6
        assertSolved(sol, t, 4000, 3000)
        assertTrue("took $ms ms", ms < 8000)
    }

    @Test
    fun theSouthernCrossInPortrait() {
        val t = Truth(190.0, -60.0, 3000.0, Math.toRadians(90.0), false)
        val stars = photo(t, 3000, 4000, 3)
        val sol = PlateSolver.solve(stars, 3000, 4000, catalog, focalPx = 3000.0, hint = null)
        assertSolved(sol, t, 3000, 4000)
    }

    @Test
    fun aMirroredPhotoIsFoundMirrored() {
        val t = Truth(40.0, 50.0, 3000.0, Math.toRadians(15.0), true)
        val stars = photo(t, 4000, 3000, 4)
        val sol = PlateSolver.solve(stars, 4000, 3000, catalog, focalPx = 3000.0, hint = 40.0 to 50.0)
        assertSolved(sol, t, 4000, 3000)
    }

    @Test
    fun noStarsNoSolution() {
        val rnd = Random(9)
        val noise = List(40) { ImageStar(rnd.nextFloat() * 4000, rnd.nextFloat() * 3000, rnd.nextFloat()) }
        assertNull(PlateSolver.solve(noise, 4000, 3000, catalog, focalPx = 3000.0, hint = 120.0 to 20.0))
        assertNull(PlateSolver.solve(noise.take(4), 4000, 3000, catalog, focalPx = 3000.0))
    }

    @Test
    fun noiseNeverPassesForTheSky() {
        // Clouds lit by a town, a noisy sensor: whatever the detector finds, no false answer.
        var slowest = 0.0
        for (seed in 0 until 12) {
            val rnd = Random(100 + seed)
            val noise = List(60 + 20 * (seed % 4)) { ImageStar(rnd.nextFloat() * 4000, rnd.nextFloat() * 3000, rnd.nextFloat()) }
            val t0 = System.nanoTime()
            val sol = PlateSolver.solve(noise, 4000, 3000, catalog, focalPx = 3000.0, scaleTolerance = 0.12)
            slowest = maxOf(slowest, (System.nanoTime() - t0) / 1e6)
            assertNull("seed $seed: ${sol?.matched} matched, rms ${sol?.rmsPx}", sol)
        }
        println("noise: slowest blind search ${slowest.toInt()} ms")
    }

    @Test
    fun aPhoneLensBendsTheCorners() {
        // Barrel distortion, uncorrected in RAW: 3 % at the corners of a wide field.
        val t = Truth(250.0, -30.0, 2900.0, Math.toRadians(170.0), false, k = -0.045)
        val stars = photo(t, 4000, 3000, 5)
        val sol = PlateSolver.solve(stars, 4000, 3000, catalog, focalPx = 2900.0)
        assertSolved(sol, t, 4000, 3000, tolPx = 3.0)
        assertEquals(-0.045, sol!!.k, 0.01)
        assertTrue("rms ${sol.rmsPx}", sol.rmsPx < 2.0)
    }

    @Test
    fun aTownSkyWithStreetLights() {
        // Only stars to 3.5 show, a third lost to haze, and lamps along the bottom outshine them.
        val t = Truth(60.0, 25.0, 3000.0, Math.toRadians(-20.0), false)
        val rnd = Random(6)
        val stars = photo(t, 4000, 3000, 6, maxMag = 3.5f, lose = 0.3, fakes = 5) +
            List(12) { ImageStar(rnd.nextFloat() * 4000, 2500 + rnd.nextFloat() * 500, 5f) }
        val sol = PlateSolver.solve(stars, 4000, 3000, catalog, focalPx = 3000.0)
        assertSolved(sol, t, 4000, 3000)
    }

    @Test
    fun anUnsureFocalLength() {
        // An old session: the focal length guessed from today's camera, 9 % off.
        val t = Truth(120.0, 45.0, 3300.0, Math.toRadians(60.0), false)
        val stars = photo(t, 4000, 3000, 7)
        val sol = PlateSolver.solve(stars, 4000, 3000, catalog, focalPx = 3000.0, scaleTolerance = 0.15)
        assertSolved(sol, t, 4000, 3000)
    }

    @Test
    fun aTelephotoField() {
        // A 3x lens: 25 deg across, so fainter stars have to do.
        val t = Truth(83.8, -5.4, 9000.0, Math.toRadians(5.0), false)
        val stars = photo(t, 4000, 3000, 8, maxMag = 5.0f, lose = 0.1)
        val sol = PlateSolver.solve(stars, 4000, 3000, catalog, focalPx = 9000.0)
        assertNotNull(sol)
        val betelgeuse = catalog.stars.first { it.name == "Betelgeuse" }
        val (xi, eta) = PlateSolver.tangent(betelgeuse.raDeg, betelgeuse.decDeg, t.ra, t.dec)!!
        val (x, y) = PlateSolver.toPixel(xi, eta, t.focal, t.angle, t.flipped, 2000.0, 1500.0)
        val p = sol!!.project(betelgeuse.raDeg, betelgeuse.decDeg)!!
        assertTrue("${p.first},${p.second} vs $x,$y", hypot(p.first - x, p.second - y) < 4)
    }

    @Test
    fun aWrongHintFindsNothingNearIt() {
        // The compass was off by a lot: the answer is not near the hint, so none is given there.
        val t = Truth(300.0, 35.0, 3000.0, Math.toRadians(40.0), false)
        val stars = photo(t, 4000, 3000, 1)
        assertNull(PlateSolver.solve(stars, 4000, 3000, catalog, focalPx = 3000.0, hint = 150.0 to -20.0))
    }

    @Test
    fun skyPlaceAtAPixel() {
        val t = Truth(300.0, 35.0, 3000.0, Math.toRadians(40.0), false)
        val sol = PlateSolver.solve(photo(t, 4000, 3000, 1), 4000, 3000, catalog, focalPx = 3000.0)!!
        val vega = catalog.stars.first { it.name == "Vega" }
        val (x, y) = sol.project(vega.raDeg, vega.decDeg)!!
        val (ra, dec) = PlateSolver.pixelToSky(x, y, sol)
        assertEquals(vega.raDeg, ra, 0.01)
        assertEquals(vega.decDeg, dec, 0.01)
    }

    @Test
    fun tangentPlaneRoundTrip() {
        for ((ra, dec) in listOf(10.0 to 20.0, 359.0 to -45.0, 180.0 to 89.0)) {
            val (xi, eta) = PlateSolver.tangent(ra + 7, dec - 5, ra, dec)!!
            val (r2, d2) = PlateSolver.untangent(xi, eta, ra, dec)
            assertEquals(SkyMath.norm360(ra + 7), r2, 1e-6)
            assertEquals(dec - 5, d2, 1e-6)
        }
        assertFalse(PlateSolver.tangent(10.0, 0.0, 190.0, 0.0) != null)
    }
}
