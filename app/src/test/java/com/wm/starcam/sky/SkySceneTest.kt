package com.wm.starcam.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.tan

/**
 * The sky view's geometry: catalogue, galactic coordinates, planets and the projection onto
 * the screen, each against an independent reference.
 */
class SkySceneTest {

    private val amsterdam = GeoLocation(52.37, 4.90)
    private val sydney = GeoLocation(-33.87, 151.21)

    private fun utc(s: String) = Instant.parse(s).toEpochMilli()

    private fun catalog(): StarCatalog =
        StarCatalog.parse(File("src/main/assets/sky/stars.txt").readText())

    private fun angleDiff(a: Double, b: Double): Double = abs(((a - b) % 360.0 + 540.0) % 360.0 - 180.0)

    @Test
    fun catalogueLoadsAndEveryFigureStarExists() {
        val cat = catalog()
        assertTrue("stars: ${cat.stars.size}", cat.stars.size in 1500..1800)
        val sirius = cat.stars.first { it.designation == "Alp CMa" }
        assertEquals("Sirius", sirius.name)
        assertEquals(-1.46f, sirius.mag, 0.01f)
        assertEquals(101.287, sirius.raDeg, 0.01)
        assertEquals(-16.716, sirius.decDeg, 0.01)
        assertEquals(emptyList<String>(), cat.missingFigureStars())
        assertTrue("lines ${cat.lines.size}", cat.lines.size >= 2 * 100)
        // Every named star is in the catalogue.
        val known = cat.stars.mapNotNull { it.designation }.toSet()
        assertEquals(emptyList<String>(), StarCatalog.NAMES.keys.filter { it !in known })
    }

    @Test
    fun galacticCentreAndPole() {
        val (ra, dec) = SkyScene.galacticToJ2000(0.0, 0.0)
        assertEquals(266.405, ra, 0.01)
        assertEquals(-28.936, dec, 0.01)
        val (raP, decP) = SkyScene.galacticToJ2000(123.0, 90.0)
        assertEquals(27.128, decP, 0.01)
        assertEquals(192.859, raP, 0.05)
        // Deneb (l 84.3, b 2.0) lies in the band, by the catalogue's own place.
        val (raD, decD) = SkyScene.galacticToJ2000(84.28, 2.00)
        assertEquals(310.358, raD, 0.1)
        assertEquals(45.280, decD, 0.1)
    }

    @Test
    fun theWholeCatalogueTurnsLikeSkyMath() {
        val cat = catalog()
        val t = utc("2026-08-15T22:30:00Z")
        for (loc in listOf(amsterdam, sydney)) {
            val m = SkyScene.j2000ToCompass(t, loc, 0.0)
            for (name in listOf("Alp CMa", "Alp Lyr", "Alp Sco", "Alp1 Cru", "Alp UMi", "Alp Ori")) {
                val s = cat.stars.first { it.designation == name }
                val ra = Math.toRadians(s.raDeg)
                val dec = Math.toRadians(s.decDeg)
                val v = doubleArrayOf(Math.cos(dec) * Math.cos(ra), Math.cos(dec) * Math.sin(ra), Math.sin(dec))
                val e = m[0] * v[0] + m[1] * v[1] + m[2] * v[2]
                val n = m[3] * v[0] + m[4] * v[1] + m[5] * v[2]
                val u = m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
                val alt = Math.toDegrees(asin(u))
                val az = (Math.toDegrees(atan2(e, n)) + 360.0) % 360.0
                val ref = SkyMath.j2000ToHorizontal(s.raDeg, s.decDeg, t, loc)
                assertEquals("$name alt", ref.altDeg, alt, 0.005)
                if (abs(ref.altDeg) < 85) assertTrue("$name az", angleDiff(ref.azDeg, az) < 0.01)
            }
        }
    }

    @Test
    fun declinationTurnsAzimuthsOnly() {
        val t = utc("2026-08-15T22:30:00Z")
        val true0 = SkyScene.j2000ToCompass(t, amsterdam, 0.0)
        val mag = SkyScene.j2000ToCompass(t, amsterdam, 10.0)
        val v = doubleArrayOf(0.3, 0.4, Math.sqrt(1 - 0.25))
        fun azAlt(m: DoubleArray): Pair<Double, Double> {
            val e = m[0] * v[0] + m[1] * v[1] + m[2] * v[2]
            val n = m[3] * v[0] + m[4] * v[1] + m[5] * v[2]
            val u = m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
            return Math.toDegrees(atan2(e, n)) to Math.toDegrees(asin(u))
        }
        val (az0, alt0) = azAlt(true0)
        val (az1, alt1) = azAlt(mag)
        assertEquals(alt0, alt1, 1e-9)
        // 10 deg east declination: magnetic azimuths are 10 deg smaller than true ones.
        assertTrue(angleDiff(az0 - 10.0, az1) < 1e-6)
    }

    @Test
    fun planetsAgreeWithTheSunAndTheirOppositions() {
        // The same elements give the Sun (the Earth's orbit turned around): against Meeus' Sun.
        for (iso in listOf("2024-03-20T03:06:00Z", "2026-06-21T12:00:00Z", "2026-12-01T00:00:00Z")) {
            val t = utc(iso)
            val (ra, dec) = Planets.sun(t)
            val fromPlanets = SkyMath.j2000ToHorizontal(ra, dec, t, amsterdam)
            val meeus = SkyMath.sun(t, amsterdam)
            assertEquals("sun alt $iso", meeus.altDeg, fromPlanets.altDeg, 0.03)
            assertTrue("sun az $iso", angleDiff(meeus.azDeg, fromPlanets.azDeg) < 0.06)
        }
        // At opposition a planet stands opposite the Sun: 180 deg apart in right ascension (to
        // within the planet's ecliptic latitude's effect, a degree or two).
        for ((name, iso) in listOf(
            "Mars" to "2022-12-08T05:00:00Z",
            "Jupiter" to "2023-11-03T05:00:00Z",
            "Saturn" to "2023-08-27T08:00:00Z",
        )) {
            val t = utc(iso)
            val planet = Planets.places(t).first { it.name == name }
            val (sunRa, _) = Planets.sun(t)
            assertTrue("$name elongation", angleDiff(planet.raDeg, sunRa + 180.0) < 2.5)
        }
        // Venus never strays more than 47 deg from the Sun.
        for (day in 0 until 600 step 7) {
            val t = utc("2025-01-01T00:00:00Z") + day * 86_400_000L
            val venus = Planets.places(t).first { it.name == "Venus" }
            val (sunRa, sunDec) = Planets.sun(t)
            val sep = separation(venus.raDeg, venus.decDeg, sunRa, sunDec)
            assertTrue("Venus $day: $sep", sep < 48.0)
        }
    }

    private fun separation(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val a = Math.toRadians(dec1)
        val b = Math.toRadians(dec2)
        val d = Math.toRadians(ra1 - ra2)
        val c = Math.sin(a) * Math.sin(b) + Math.cos(a) * Math.cos(b) * Math.cos(d)
        return Math.toDegrees(Math.acos(c.coerceIn(-1.0, 1.0)))
    }

    @Test
    fun projectionPutsWhatTheCameraFacesInTheMiddle() {
        // Phone upright in portrait, camera facing north: screen right = east, top = up, and the
        // back camera (-z) = north. Device-to-world matrix, row-major.
        val r = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        val tanW = tan(Math.toRadians(25.0)).toFloat()
        val tanH = tan(Math.toRadians(33.0)).toFloat()
        val proj = SkyProjection(r, tanW, tanH)
        val out = FloatArray(2)
        fun at(azDeg: Double, altDeg: Double): FloatArray? {
            val v = SkyScene.compassVector(Horizontal(azDeg, altDeg), 0.0)
            return if (proj.project(v, 0, 1000f, 1400f, out)) out.copyOf() else null
        }
        val centre = at(0.0, 0.0)!!
        assertEquals(500f, centre[0], 0.01f)
        assertEquals(700f, centre[1], 0.01f)
        // 25 deg to the east lands on the right edge; 33 deg up on the top edge.
        assertEquals(1000f, at(25.0, 0.0)!![0], 0.5f)
        assertEquals(0f, at(0.0, 33.0)!![1], 0.5f)
        // West is left, down is down.
        assertTrue(at(350.0, 0.0)!![0] < 500f)
        assertTrue(at(0.0, -10.0)!![1] > 700f)
        // Behind the camera: not drawn.
        assertFalse(proj.project(SkyScene.compassVector(Horizontal(180.0, 0.0), 0.0), 0, 1000f, 1400f, out))
        // ...but an arrow can still point at it: south of a north-facing camera, straight down
        // the screen when it is below the horizon.
        assertTrue(proj.direction(SkyScene.compassVector(Horizontal(180.0, -30.0), 0.0), 0, out))
        assertEquals(1f, out[1], 0.01f)
    }

    @Test
    fun sceneBuildsForADarkSiteNight() {
        val cat = catalog()
        val s = SkyScene.build(utc("2026-07-20T23:00:00Z"), amsterdam, 2.0, cat)
        assertEquals(cat.stars.size * 3, s.stars.size)
        assertEquals(SkyScene.BAND_LEVELS.size, s.band.size)
        assertEquals(5, s.planets.size)
        // Every vector is a unit vector.
        for (i in 0 until cat.stars.size) {
            val x = s.stars[i * 3]; val y = s.stars[i * 3 + 1]; val z = s.stars[i * 3 + 2]
            assertEquals(1f, x * x + y * y + z * z, 1e-4f)
        }
        // A July night in Amsterdam: the core skims the southern horizon around 23 UT.
        assertTrue("core alt ${s.coreAltDeg}", s.coreAltDeg in -5.0..10.0)
    }
}
