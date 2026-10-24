package com.wm.starcam.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.random.Random

/**
 * "What's in this photo" from end to end on a photo as the app saves it: 8 bits, stretched, sky
 * glow and noise, the Moon and street lamps in it - stars found, the sky solved, the labels made.
 */
class WhatsInPhotoTest {

    private val catalog = StarCatalog.parse(File("src/main/assets/sky/stars.txt").readText())

    private class Shot(val argb: IntArray, val w: Int, val h: Int, val moon: Pair<Int, Int>)

    /** A [w] x [h] photo centred on ([ra], [dec]) at [focal] px, turned by [angleDeg]. */
    private fun shoot(ra: Double, dec: Double, focal: Double, angleDeg: Double, w: Int, h: Int, seed: Int): Shot {
        val rnd = Random(seed)
        val img = FloatArray(w * h) { i -> 22f + 18f * (i / w) / h + rnd.nextFloat() * 4f - 2f }
        fun blob(x: Double, y: Double, amp: Double, sigma: Double) {
            val reach = (4 * sigma).toInt() + 1
            for (yy in maxOf(0, y.toInt() - reach)..minOf(h - 1, y.toInt() + reach)) {
                for (xx in maxOf(0, x.toInt() - reach)..minOf(w - 1, x.toInt() + reach)) {
                    val d2 = (xx - x) * (xx - x) + (yy - y) * (yy - y)
                    img[yy * w + xx] += (amp * exp(-d2 / (2 * sigma * sigma))).toFloat()
                }
            }
        }
        for (s in catalog.stars) {
            val (xi, eta) = PlateSolver.tangent(s.raDeg, s.decDeg, ra, dec) ?: continue
            val (x, y) = PlateSolver.toPixel(xi, eta, focal, Math.toRadians(angleDeg), false, w / 2.0, h / 2.0)
            if (x < 0 || y < 0 || x >= w || y >= h) continue
            // A stretched photo: bright stars bigger and clipped, faint ones a few levels up.
            val amp = (260 * 10.0.pow(-0.25 * (s.mag - 0.5))).coerceIn(14.0, 400.0)
            blob(x, y, amp, 1.1 + 0.5 * (5 - s.mag).coerceAtLeast(0f))
        }
        // Fainter stars than the catalogue has, everywhere.
        repeat(500) { blob(rnd.nextDouble() * w, rnd.nextDouble() * h, 8.0 + rnd.nextDouble() * 18, 1.0) }
        // The Moon and its glow, and lamps along the bottom.
        val moon = (w * 3 / 4) to (h / 5)
        blob(moon.first.toDouble(), moon.second.toDouble(), 900.0, 9.0)
        blob(moon.first.toDouble(), moon.second.toDouble(), 60.0, 60.0)
        repeat(6) { blob(100.0 + it * 260.0, h - 40.0, 700.0, 7.0) }
        val argb = IntArray(w * h) {
            val v = img[it].toInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Shot(argb, w, h, moon)
    }

    @Test
    fun orionFromAPhoto() {
        val shot = shoot(84.0, 2.0, 1500.0, 12.0, 1600, 1200, 1)
        val t0 = System.nanoTime()
        val stars = PhotoStars.find(shot.argb, shot.w, shot.h)
        val findMs = (System.nanoTime() - t0) / 1e6
        assertTrue("found ${stars.size}", stars.size in 60..400)
        // The Moon and the lamps are not stars.
        val nearMoon = stars.filter { hypot(it.x - shot.moon.first.toDouble(), it.y - shot.moon.second.toDouble()) < 20 }
        assertTrue("moon: " + nearMoon.joinToString { "${it.x},${it.y} ${it.flux}" }, nearMoon.isEmpty())
        val lamps = stars.filter { s -> (0 until 6).any { hypot(s.x - (100.0 + it * 260.0), s.y - (shot.h - 40.0)) < 15 } }
        assertTrue("lamps: " + lamps.joinToString { "${it.x},${it.y} ${it.flux}" }, lamps.isEmpty())

        val sol = PlateSolver.solve(stars, shot.w, shot.h, catalog, focalPx = 1450.0, scaleTolerance = 0.06)
        assertNotNull("not solved; brightest: " + stars.take(12).joinToString { "${it.x.toInt()},${it.y.toInt()} ${it.flux.toInt()}" }, sol)
        val betelgeuse = catalog.stars.first { it.name == "Betelgeuse" }
        val (xi, eta) = PlateSolver.tangent(betelgeuse.raDeg, betelgeuse.decDeg, 84.0, 2.0)!!
        val (x, y) = PlateSolver.toPixel(xi, eta, 1500.0, Math.toRadians(12.0), false, 800.0, 600.0)
        val p = sol!!.project(betelgeuse.raDeg, betelgeuse.decDeg)!!
        assertTrue("${p.first},${p.second} vs $x,$y", hypot(p.first - x, p.second - y) < 1.5)
        assertEquals(1500.0, sol.scale, 8.0)
        println("orion: ${stars.size} stars found in ${findMs.toInt()} ms, ${sol.matched} matched, rms ${sol.rmsPx}")

        // Jupiter was in Taurus at its opposition, 7 December 2024.
        val night = Instant.parse("2024-12-07T22:00:00Z").toEpochMilli()
        val labels = SkyLabels.build(sol, shot.w, shot.h, catalog, night)
        assertEquals("Orion", labels.constellations.first())
        assertTrue(labels.constellations.toString(), "Taurus" in labels.constellations)
        assertTrue(labels.stars.map { it.name }.containsAll(listOf("Betelgeuse", "Rigel", "Bellatrix")))
        assertTrue(labels.deepSky.map { it.name }.toString(), "Orion Nebula" in labels.deepSky.map { it.name })
        assertTrue(labels.planets.toString(), "Jupiter" in labels.planets)
        assertTrue(labels.segments.size >= 4 * 8)
        assertEquals(84.0, labels.raDeg, 1.0)
        assertEquals(2.0, labels.decDeg, 1.0)
        assertEquals(Math.toDegrees(2 * kotlin.math.atan(800.0 / 1500.0)), labels.fovAcrossDeg, 0.5)
        // The farthest light in it: the Orion Nebula's, unless something farther is in the frame.
        val (name, ly) = labels.farthest()!!
        assertTrue("$name $ly", ly >= 1344.0)
    }

    @Test
    fun andromedaIsTheOldestLight() {
        val shot = shoot(14.0, 38.0, 1400.0, -30.0, 1600, 1200, 2)
        val stars = PhotoStars.find(shot.argb, shot.w, shot.h)
        val sol = PlateSolver.solve(stars, shot.w, shot.h, catalog, focalPx = 1400.0)
        assertNotNull(sol)
        val labels = SkyLabels.build(sol!!, shot.w, shot.h, catalog, null)
        assertTrue(labels.constellations.toString(), "Andromeda" in labels.constellations)
        assertTrue("Andromeda Galaxy" in labels.deepSky.map { it.name })
        // The Triangulum Galaxy is in the frame too, and a little farther.
        assertEquals("Triangulum Galaxy" to 2_730_000.0, labels.farthest())
        assertTrue(labels.planets.isEmpty())
    }

    @Test
    fun lightYearsReadWell() {
        assertEquals("2.5 million light years", SkyLabels.lightYears(2_500_000.0))
        assertEquals("11.8 million light years", SkyLabels.lightYears(11_800_000.0))
        assertEquals("1,344 light years", SkyLabels.lightYears(1344.0))
        assertEquals("25 light years", SkyLabels.lightYears(25.0))
        assertEquals("8.6 light years", SkyLabels.lightYears(8.6))
        assertEquals("2 million light years", SkyLabels.lightYears(2_000_000.0))
    }

    @Test
    fun aCloudedPhotoSaysNothing() {
        val rnd = Random(5)
        val w = 1600
        val h = 1200
        // Cloud lit from below: smooth light, noise, a few lamps; no stars.
        val argb = IntArray(w * h) {
            val y = it / w
            val v = (60 + 50.0 * y / h + rnd.nextDouble() * 6).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val stars = PhotoStars.find(argb, w, h)
        assertTrue("found ${stars.size}", stars.size < 30)
        if (stars.size >= 6) {
            assertEquals(null, PlateSolver.solve(stars, w, h, catalog, focalPx = 1400.0, scaleTolerance = 0.13))
        }
    }
}
