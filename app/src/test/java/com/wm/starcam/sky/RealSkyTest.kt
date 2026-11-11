package com.wm.starcam.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * The two real sessions of 31 Oct 2026 (a OnePlus CPH2769 in a window facing north, a town sky,
 * 10 minutes apart and 6 degrees apart in pointing), from the stars their stacks wrote: both solve,
 * their stars give the same white balance, the camera's balance times it the same sensor balance,
 * and their sky the same brightness. The finished JPEGs' blobs - lit windows mostly - solved
 * neither.
 */
class RealSkyTest {

    private val catalog = StarCatalog.parse(File("src/main/assets/sky/stars.txt").readText())

    /** One session: its stars, its header's focal length (raw 4096 x 3072) and its camera's balance. */
    private class Session(val name: String, val ra: Double, val dec: Double, val cameraR: Double, val cameraB: Double)

    private val eight = Session("stars_20261031_235942", 285.70, 68.69, 1.58, 2.295)
    private val four = Session("stars_20261031_234936", 269.10, 67.48, 1.57, 2.259)

    private fun stars(s: Session) = SkyStars.read(File("src/test/resources/real/${s.name}.json"))!!

    private fun measure(s: Session): StackSky.Measured =
        StackSky.measure(stars(s), catalog, 2794.9999435385716, 4096, giveUpMs = 4000)

    /** Degrees between two places on the sky. */
    private fun apart(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val r = Math.toRadians(ra1 - ra2)
        val d1 = Math.toRadians(dec1)
        val d2 = Math.toRadians(dec2)
        return Math.toDegrees(acos((sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(r)).coerceIn(-1.0, 1.0)))
    }

    private fun report(s: Session, m: StackSky.Measured) {
        val sol = m.solution
        val c = m.colour
        val p = m.photometry
        println(String.format(java.util.Locale.US,
            "%s: %d of %d stars to solve, %s in %d ms; %d catalogue stars matched; colour %s; sky %s",
            s.name, m.solving, m.sky.stars.size,
            sol?.let { String.format(java.util.Locale.US, "ra %.2f dec %.2f, %d matched, rms %.2f px, focal %.1f, k %.4f",
                it.raDeg, it.decDeg, it.matched, it.rmsPx, it.scale, it.k) } ?: "not solved",
            m.solveMs, m.matched.size,
            c?.let { String.format(java.util.Locale.US,
                "wbR %.3f wbB %.3f from %d stars of snr %.0f+ (B-V range %.2f, %s slopes %+.3f %+.3f, scatter %.3f %.3f, " +
                    "known to %.4f %.4f), absolute R %.3f B %.3f",
                it.wbR, it.wbB, it.stars, it.snrCut, it.bvRange, if (it.freeSlopes) "free" else "fixed", it.slopeR, it.slopeB,
                it.scatterR, it.scatterB, it.errorR, it.errorB, s.cameraR * it.wbR, s.cameraB * it.wbB) } ?: "none",
            p?.let { String.format(java.util.Locale.US,
                "zp %.3f (colour %+.3f, scatter %.3f, %d stars), %.2f mag/arcsec2 (Bortle %d)",
                it.zp, it.colourTerm, it.scatter, it.stars, it.magArcsec2, it.bortle) } ?: "none"))
    }

    @Test
    fun bothSessionsSolveFromTheirOwnStars() {
        for (s in listOf(eight, four)) {
            val m = measure(s)
            report(s, m)
            val sol = m.solution
            assertNotNull("${s.name} not solved (tried${m.tried})", sol)
            assertTrue("${s.name}: centre ${sol!!.raDeg}, ${sol.decDeg}", apart(sol.raDeg, sol.decDeg, s.ra, s.dec) < 1.0)
            assertTrue("${s.name}: ${sol.matched} matched", sol.matched >= 40)
            assertTrue("${s.name}: a camera never mirrors", !sol.flipped)
        }
    }

    @Test
    fun theStarsGiveBothSessionsTheSameWhite() {
        val a = measure(eight).colour
        val b = measure(four).colour
        assertNotNull("8 frames: no colour fit", a)
        assertNotNull("4 frames: no colour fit", b)
        for ((name, f) in listOf("8 frames" to a!!, "4 frames" to b!!)) {
            assertTrue("$name: wbR ${f.wbR}", f.wbR in 1.2..1.45)
            assertTrue("$name: wbB ${f.wbB}", f.wbB in 0.66..0.82)
            // From the best-measured stars, and known to about 1 %: far inside what is asked.
            assertEquals("$name: tier", 30.0, f.snrCut, 0.0)
            assertTrue("$name: known to ${f.errorR} ${f.errorB}", f.errorR < 0.015 && f.errorB < 0.015)
        }
        assertEquals("wbR ${a.wbR} vs ${b.wbR}", 1.0, a.wbR / b.wbR, 0.04)
        assertEquals("wbB ${a.wbB} vs ${b.wbB}", 1.0, a.wbB / b.wbB, 0.04)
        // The sensor's own balance: two sessions, two camera guesses, one answer.
        val (rA, bA) = StarColour.absolute(eight.cameraR, eight.cameraB, a)
        val (rB, bB) = StarColour.absolute(four.cameraR, four.cameraB, b)
        assertEquals("absolute R $rA vs $rB", 1.0, rA / rB, 0.03)
        assertEquals("absolute B $bA vs $bB", 1.0, bA / bB, 0.03)
    }

    @Test
    fun theSkyReadsTheSameInBoth() {
        val a = measure(eight).photometry
        val b = measure(four).photometry
        assertNotNull(a)
        assertNotNull(b)
        for (p in listOf(a!!, b!!)) assertTrue("sky ${p.magArcsec2}", p.magArcsec2 in 16.5..18.0)
        assertTrue("sky ${a.magArcsec2} vs ${b.magArcsec2}", abs(a.magArcsec2 - b.magArcsec2) <= 0.3)
    }

    @Test
    fun bothMarkTheSkyForTheLook() {
        for (s in listOf(eight, four)) {
            val pts = stars(s).skyPoints()
            assertEquals(0, pts.size % 3)
            assertTrue("${s.name}: ${pts.size / 3} sky points", pts.size / 3 >= 100)
        }
    }
}
