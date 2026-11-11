package com.wm.starcam.sky

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** out/stars.json read, the stars picked for the solver and the look, and matched to the catalogue. */
class SkyStarsTest {

    private val catalog = StarCatalog.parse(File("src/main/assets/sky/stars.txt").readText())

    /** One entry as the engine writes it. */
    private fun entry(
        x: Double, y: Double, flux: Double, sky: Boolean, pt: Boolean = false, fwhm: Double = 4.4, bg: Double = 0.013,
        snr: Double = 40.0, sat: Boolean = false, measured: Boolean = true,
    ): JSONObject = JSONObject().apply {
        put("x", x)
        put("y", y)
        put("flux", if (measured) flux else 0.0)
        put("r", if (measured) flux * 1.1 else 0.0)
        put("g", if (measured) flux else 0.0)
        put("b", if (measured) flux * 0.8 else 0.0)
        put("peak", if (measured) 0.2 else 0.0)
        put("snr", if (measured) snr else 0.0)
        put("fwhm", if (measured) fwhm else 0.0)
        put("bg", if (measured) bg else 0.0)
        put("seen", 6)
        put("tried", 7)
        put("moved", 7)
        put("sat", sat)
        put("sky", sky)
        put("pt", pt)
    }

    private fun file(entries: List<JSONObject>, w: Int = 3072, h: Int = 4096): String = JSONObject().apply {
        put("version", 1)
        put("width", w)
        put("height", h)
        put("rotation", 90)
        put("frames", 8)
        put("fwhm", 4.4)
        put("aperture", 6.2)
        put("annulus", JSONArray(listOf(10.2, 16.2)))
        put("skyBg", JSONArray(listOf(0.011, 0.0137, 0.017)))
        put("stars", JSONArray(entries))
    }.toString()

    @Test
    fun readsWhatTheEngineWrites() {
        val sky = SkyStars.parse(file(listOf(entry(10.5, 20.25, 3.0, sky = true, pt = true), entry(1.0, 2.0, 0.0, false, measured = false))))
        assertNotNull(sky)
        sky!!
        assertEquals(3072, sky.width)
        assertEquals(4096, sky.height)
        assertEquals(90, sky.rotation)
        assertEquals(8, sky.frames)
        assertEquals(4.4, sky.fwhm, 1e-9)
        assertEquals((0.011 + 2 * 0.0137 + 0.017) / 4, sky.skyBgLuma, 1e-12)
        assertEquals(2, sky.stars.size)
        val s = sky.stars[0]
        assertEquals(10.5, s.x, 1e-9)
        assertEquals(20.25, s.y, 1e-9)
        assertEquals(3.3, s.r, 1e-9)
        assertTrue(s.sky && s.pt && s.measured && !s.sat)
        assertEquals(7, s.moved)
        assertFalse("all zeros: not measured", sky.stars[1].measured)
        assertNull(SkyStars.parse("not json"))
        assertNull(SkyStars.parse("""{"width":0,"height":10,"stars":[]}"""))
    }

    @Test
    fun theSkyStarsAreTheSolversWhenThereAreEnough() {
        val rnd = Random(1)
        val entries = ArrayList<JSONObject>()
        repeat(20) { entries += entry(rnd.nextDouble(3000.0), rnd.nextDouble(4000.0), 5.0 + it, sky = true) }
        // A sky star of no light above its background helps nothing.
        entries += entry(100.0, 100.0, -0.2, sky = true)
        // Lit windows: brighter, but they did not follow the sky.
        repeat(30) { entries += entry(rnd.nextDouble(3000.0), 3500 + rnd.nextDouble(500.0), 50.0, sky = false, fwhm = 11.0, bg = 0.05) }
        val sky = SkyStars.parse(file(entries))!!
        val picked = sky.forSolving()
        assertEquals(20, picked.size)
        assertTrue(picked.all { it.sky && it.flux > 0 })
    }

    @Test
    fun aSkyThatHardlyMovedFallsBackOnAStarsShape() {
        val rnd = Random(2)
        val entries = ArrayList<JSONObject>()
        // Too few passed the sky test...
        repeat(5) { entries += entry(rnd.nextDouble(3000.0), rnd.nextDouble(4000.0), 9.0, sky = true) }
        // ...but stars have a star's width and a sky around them;
        repeat(15) { entries += entry(rnd.nextDouble(3000.0), rnd.nextDouble(4000.0), 6.0, sky = false, fwhm = 4.4 * (0.7 + 0.9 * rnd.nextDouble())) }
        // a lamp is wide, a window sits in a bright wall, a speck of noise is too narrow.
        entries += entry(10.0, 10.0, 40.0, sky = false, fwhm = 11.5)
        entries += entry(20.0, 20.0, 40.0, sky = false, fwhm = 4.5, bg = 0.2)
        entries += entry(30.0, 30.0, 40.0, sky = false, fwhm = 2.0)
        val sky = SkyStars.parse(file(entries))!!
        val picked = sky.forSolving()
        assertEquals(20, picked.size)
        assertTrue(picked.none { it.x < 50 && it.y < 50 })
    }

    @Test
    fun withNeitherEveryMeasuredStarIsTried() {
        val entries = ArrayList<JSONObject>()
        repeat(4) { entries += entry(100.0 * it, 100.0, 9.0, sky = true) }
        repeat(10) { entries += entry(100.0 * it, 500.0, 9.0, sky = false, fwhm = 12.0) }
        entries += entry(5.0, 5.0, 0.0, sky = false, measured = false)
        val picked = SkyStars.parse(file(entries))!!.forSolving()
        assertEquals(14, picked.size)
        assertTrue(picked.all { it.measured })
    }

    @Test
    fun theSkyPointsAreThePtStars() {
        val entries = listOf(
            entry(1.0, 2.0, 5.0, sky = true, pt = true, bg = 0.01),
            entry(3.0, 4.0, 5.0, sky = true, pt = false),
            entry(5.0, 6.0, 5.0, sky = true, pt = true, bg = 0.02),
        )
        val pts = SkyStars.parse(file(entries))!!.skyPoints()
        assertArrayEquals(doubleArrayOf(1.0, 2.0, 0.01, 5.0, 6.0, 0.02), pts, 1e-12)
    }

    @Test
    fun theSamePictureAtAnotherSize() {
        val sky = SkyStars.parse(file(emptyList(), 3072, 4096))!!
        assertEquals(1.0, sky.scaleTo(3072, 4096)!!, 0.0)
        assertEquals(0.5, sky.scaleTo(1536, 2048)!!, 1e-12)
        // A decoded JPEG rounds its sides.
        val sky2 = SkyStars.parse(file(emptyList(), 1393, 1857))!!
        assertNotNull(sky2.scaleTo(697, 929))
        // Turned a quarter, or cropped: not the same picture.
        assertNull(sky.scaleTo(4096, 3072))
        assertNull(sky.scaleTo(3072, 3500))
        // Pixel centres at integers in both: the first pixel's centre of a half-size picture is
        // where the first two of the full one meet.
        val star = SkyStars.parse(file(listOf(entry(0.5, 1.5, 3.0, true))))!!
        val p = star.imageStars(star.stars, 0.5).single()
        assertEquals(0.0, p.x.toDouble(), 1e-6)
        assertEquals(0.5, p.y.toDouble(), 1e-6)
    }

    @Test
    fun theCatalogueFindsItsStars() {
        // A photo of Orion: every catalogue star a measured star, 0.8 px off; a few strangers.
        val sol = PlateSolution(84.0, 2.0, 2800.0, Math.toRadians(20.0), false, 1536.0, 2048.0, 50, 1.0)
        val rnd = Random(3)
        val truth = HashMap<Int, Star>()
        val entries = ArrayList<JSONObject>()
        for (s in catalog.stars) {
            val (x, y) = sol.project(s.raDeg, s.decDeg) ?: continue
            if (x < 0 || y < 0 || x >= 3072 || y >= 4096) continue
            val a = rnd.nextDouble() * 2 * Math.PI
            truth[entries.size] = s
            entries += entry(x + 0.8 * kotlin.math.cos(a), y + 0.8 * kotlin.math.sin(a), 10.0, sky = true)
        }
        repeat(10) { entries += entry(rnd.nextDouble(3072.0), rnd.nextDouble(4096.0), 1.0, sky = true) }
        val sky = SkyStars.parse(file(entries))!!
        val matched = SkyStars.match(sol, catalog, sky.stars, sky.width, sky.height)
        assertTrue("${matched.size} of ${truth.size}", matched.size >= truth.size - 3)
        // Each catalogue star on its own star, and no star taken twice.
        assertEquals(matched.size, matched.map { it.star }.toSet().size)
        var right = 0
        for (m in matched) {
            val i = sky.stars.indexOf(m.star)
            if (truth[i] === m.catalog) right++
            assertTrue(m.distPx <= 3.0)
        }
        assertTrue("$right of ${matched.size} on their own star", right >= matched.size - 3)
        // Far off, nothing is matched.
        val elsewhere = PlateSolution(250.0, -40.0, 2800.0, 0.0, false, 1536.0, 2048.0, 50, 1.0)
        val wrong = SkyStars.match(elsewhere, catalog, sky.stars, sky.width, sky.height)
        assertTrue("${wrong.size} by chance", wrong.size < matched.size / 4)
    }
}
