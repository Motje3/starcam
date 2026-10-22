package com.wm.starcam.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos

/**
 * SkyMath against the worked examples in Meeus, "Astronomical Algorithms" (2nd ed.), and the US
 * Naval Observatory's 2026 Moon phase times.
 */
class SkyMathTest {

    @Test
    fun julianDay_unixEpochAndMeeus7a() {
        assertEquals(2440587.5, SkyMath.julianDay(0L), 1e-9)
        // Example 7.a: Sputnik 1, 1957 October 4.81 = JD 2436116.31.
        assertEquals(2436116.31, SkyMath.julianDay(utc("1957-10-04T19:26:24Z")), 1e-8)
    }

    @Test
    fun sun_meeus25a() {
        val jde = 2448908.5 // 1992 October 13.0 TD
        val ecl = SkyMath.sunEcliptic(jde)
        assertAngle(199.90895, ecl.lonDeg, 1e-4, "apparent longitude")
        assertEquals(0.99766, ecl.distanceKm / AU_KM, 1e-5)
        val eq = SkyMath.sunEquatorial(jde)
        assertAngle(198.38083, eq.raDeg, 1e-4, "right ascension")
        assertEquals(-7.78507, eq.decDeg, 1e-4)
    }

    @Test
    fun moon_meeus47a() {
        // The complete tables reproduce the book's sums, so the tolerances are the printed
        // digits rather than the 0.05 deg a truncated series would need.
        val jde = 2448724.5 // 1992 April 12, 0h TD
        val ecl = SkyMath.moonEcliptic(jde)
        assertAngle(133.162655, ecl.lonDeg, 1e-5, "longitude")
        assertEquals(-3.229126, ecl.latDeg, 1e-5)
        assertEquals(368409.7, ecl.distanceKm, 0.1)
        // Apparent place: the abridged nutation is good to 0.5", hence the looser limits.
        val eq = SkyMath.moonEquatorial(jde)
        assertAngle(134.688470, eq.raDeg, 2e-4, "right ascension")
        assertEquals(13.768368, eq.decDeg, 2e-4)
    }

    @Test
    fun illuminatedFraction_meeus48a() {
        assertEquals(0.6786, SkyMath.illuminatedFraction(2448724.5), 0.001)
    }

    @Test
    fun siderealTime_meeus12ab() {
        // Example 12.a: 1987 April 10, 0h UT.
        assertAngle(hms(13, 10, 46.3668), SkyMath.greenwichMeanSiderealDeg(2446895.5), 2e-6, "12.a")
        assertAngle(
            hms(13, 10, 46.1351), SkyMath.greenwichApparentSiderealDeg(2446895.5), 1e-4,
            "12.a apparent",
        )
        // Example 12.b: the same day at 19h21m00s UT.
        assertAngle(128.7378734, SkyMath.greenwichMeanSiderealDeg(2446896.30625), 2e-6, "12.b")
    }

    @Test
    fun horizontal_meeus13b() {
        // Venus from the US Naval Observatory, 1987 April 10 19:21 UT. Meeus counts azimuth
        // from the south; ours is from the north.
        val h = SkyMath.equatorialToHorizontal(
            hms(23, 9, 16.641), -dms(6, 43, 11.61), utc("1987-04-10T19:21:00Z"),
            GeoLocation(dms(38, 55, 17.0), -dms(77, 3, 56.0)),
        )
        assertAngle(68.0337 + 180.0, h.azDeg, 5e-4, "azimuth")
        assertEquals(15.1249, h.altDeg, 5e-4)
    }

    @Test
    fun precession_meeus21b() {
        // θ Persei, its J2000 place already carried along its proper motion, to 2028 Nov 13.19.
        val p = SkyMath.precessFromJ2000(41.054063, 49.227750, 2462088.69)
        assertAngle(41.547214, p.raDeg, 2e-5, "right ascension")
        assertEquals(49.348483, p.decDeg, 2e-5)
    }

    @Test
    fun precession_galacticCentreMatchesAnnualRates() {
        // Cross-check against the simple annual rates (Meeus 21.1) taken at mid-period, over
        // the 26.81 years to 2026-10-23: +57.22"/yr in right ascension, -1.215"/yr in
        // declination for Sgr A*.
        val jde = jdeOf(utc("2026-10-23T00:00:00Z"))
        val p = SkyMath.precessFromJ2000(
            SkyMath.GALACTIC_CENTER_RA_J2000, SkyMath.GALACTIC_CENTER_DEC_J2000, jde,
        )
        assertAngle(266.8430, p.raDeg, 0.002, "right ascension")
        assertEquals(-29.0169, p.decDeg, 0.0005)
    }

    @Test
    fun topocentric_meeus40a() {
        // Mars from Palomar (33°21'22" N, 1706 m), 2003 August 28 3:17 UT: Δα = +1.29 s, so
        // the hour angle drops by that much, and δ' = -15°46'30.0".
        val topo = SkyMath.topocentric(
            haDeg = 288.7958,
            decDeg = -15.771083,
            distanceKm = 0.37276 * AU_KM,
            latDeg = dms(33, 21, 22.0),
            heightM = 1706.0,
        )
        assertEquals(1.29 * 15.0 / 3600.0, SkyMath.wrap180(288.7958 - topo.haDeg), 5e-5)
        assertEquals(-dms(15, 46, 30.0), topo.decDeg, 5e-5)
    }

    @Test
    fun moon_isTopocentric() {
        // From the surface the Moon stands lower than from the Earth's centre by about its
        // horizontal parallax times the cosine of its altitude (up to a degree).
        val loc = GeoLocation(52.37, 4.90)
        var t = utc("2026-10-23T12:00:00Z")
        repeat(48) {
            val jde = jdeOf(t)
            val geo = SkyMath.moonEquatorial(jde)
            val geoAlt = SkyMath.equatorialToHorizontal(geo.raDeg, geo.decDeg, t, loc).altDeg
            val topoAlt = SkyMath.moon(t, loc).altDeg
            val parallax = Math.toDegrees(asin(6378.14 / geo.distanceKm))
            val expected = parallax * cos(Math.toRadians(topoAlt))
            assertEquals("at ${Instant.ofEpochMilli(t)}", expected, geoAlt - topoAlt, 0.02)
            t += 30 * 60_000L
        }
    }

    @Test
    fun sun_atSolarNoonIsDueSouth() {
        // Amsterdam's solar noon on 2026-10-23 is 11:24:43 UTC (sunrise-sunset.org).
        val sun = SkyMath.sun(utc("2026-10-23T11:24:43Z"), GeoLocation(52.37, 4.90))
        assertAngle(180.0, sun.azDeg, 0.1, "azimuth")
        assertEquals(90.0 - 52.37 - 11.53, sun.altDeg, 0.05)
    }

    @Test
    fun moonPhase_meeus49aNewMoon() {
        val t = epochOfJde(2443192.65118) // new moon, 1977 February 18 3:37:42 TD
        val phase = SkyMath.moonPhase(t)
        assertTrue("illuminated ${phase.illuminated}", phase.illuminated < 0.01)
        assertEquals(MoonPhase.NEW_MOON, phase.name)
        // Within a few minutes of the book's instant, the age is ~0 (or a whole lunation if
        // our new moon falls a moment later); ten days on it counts from that new moon.
        assertTrue("age ${phase.ageDays}", phase.ageDays < 0.002 || phase.ageDays > 29.0)
        assertEquals(10.0, SkyMath.moonPhase(t + 10 * DAY_MS).ageDays, 0.002)
    }

    @Test
    fun moonPhase_principalPhasesMatchUsno2026() {
        for ((name, iso) in USNO_PHASES_2026) {
            val t = utc(iso)
            val target = PHASE_ELONGATION.getValue(name)
            // How much later our instant of the phase is, from the elongation and its rate.
            // The ch. 25 Sun (0.01 deg = 36") dominates: against JPL it is 30" off in March,
            // under 10" in autumn, while the Moon stays within 4". The elongation gains only
            // 0.45..0.6"/s, so that is up to ~100 s, plus USNO's rounding to the minute.
            val jde = jdeOf(t)
            val rate = SkyMath.wrap180(
                SkyMath.elongationDeg(jde + 1.0 / 24) - SkyMath.elongationDeg(jde - 1.0 / 24),
            ) * 12.0
            val laterSec = SkyMath.wrap180(target - SkyMath.elongationDeg(jde)) / rate * 86_400.0
            assertTrue("$name $iso: ours is ${laterSec}s later", abs(laterSec) <= 150.0)

            assertEquals("$name $iso", name, SkyMath.moonPhase(t).name)
            assertEquals("$name $iso -11h", name, SkyMath.moonPhase(t - 11 * HOUR_MS).name)
            assertEquals("$name $iso +11h", name, SkyMath.moonPhase(t + 11 * HOUR_MS).name)
            val before = SkyMath.moonPhase(t - 13 * HOUR_MS)
            val after = SkyMath.moonPhase(t + 13 * HOUR_MS)
            assertTrue("$name $iso -13h: ${before.name}", before.name != name)
            assertTrue("$name $iso +13h: ${after.name}", after.name != name)
            when (name) {
                MoonPhase.NEW_MOON -> {
                    assertTrue(before.illuminated < 0.02 && after.illuminated < 0.02)
                    assertTrue(!before.waxing && after.waxing)
                    assertEquals(1.0, SkyMath.moonPhase(t + DAY_MS).ageDays, 0.002)
                }
                MoonPhase.FULL_MOON -> {
                    assertTrue(before.illuminated > 0.98 && after.illuminated > 0.98)
                    assertTrue(before.waxing && !after.waxing)
                }
                MoonPhase.FIRST_QUARTER -> assertEquals(0.5, SkyMath.moonPhase(t).illuminated, 0.01)
                MoonPhase.LAST_QUARTER -> assertEquals(0.5, SkyMath.moonPhase(t).illuminated, 0.01)
            }
        }
    }

    @Test
    fun moonPhase_namesBetweenThePrincipalPhases() {
        // 2026: first quarter Oct 18 16:12, full Oct 26 04:12, last quarter Nov 1 20:28 UT.
        val waxingGibbous = SkyMath.moonPhase(utc("2026-10-22T00:00:00Z"))
        assertEquals(MoonPhase.WAXING_GIBBOUS, waxingGibbous.name)
        assertTrue(waxingGibbous.waxing)
        val waningGibbous = SkyMath.moonPhase(utc("2026-10-29T00:00:00Z"))
        assertEquals(MoonPhase.WANING_GIBBOUS, waningGibbous.name)
        assertTrue(!waningGibbous.waxing)
        assertEquals(MoonPhase.WANING_CRESCENT, SkyMath.moonPhase(utc("2026-11-05T00:00:00Z")).name)
        assertEquals(MoonPhase.WAXING_CRESCENT, SkyMath.moonPhase(utc("2026-10-14T00:00:00Z")).name)
        // Age: the new moon was 2026-10-10 15:50 UT.
        assertEquals(15.0, SkyMath.moonPhase(utc("2026-10-25T15:50:00Z")).ageDays, 0.002)
    }

    @Test
    fun poles_doNotBreak() {
        val t = utc("2026-12-21T12:00:00Z")
        for (lat in doubleArrayOf(90.0, -90.0)) {
            val loc = GeoLocation(lat, 0.0)
            val bodies = listOf(
                SkyMath.sun(t, loc), SkyMath.moon(t, loc), SkyMath.galacticCenter(t, loc),
            )
            for (h in bodies) {
                assertTrue("$lat: $h", h.altDeg.isFinite() && h.azDeg.isFinite())
                assertTrue("$lat: $h", h.azDeg >= 0.0 && h.azDeg < 360.0)
            }
        }
        // At the North Pole the Sun's altitude is its declination: -23.4 at the solstice.
        assertEquals(-23.43, SkyMath.sun(t, GeoLocation(90.0, 0.0)).altDeg, 0.02)
    }

    private fun assertAngle(expected: Double, actual: Double, tolDeg: Double, what: String) {
        val off = SkyMath.wrap180(actual - expected)
        assertTrue("$what: expected $expected, got $actual (off by $off)", abs(off) <= tolDeg)
    }

    private companion object {
        const val AU_KM = 149_597_870.7
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L

        fun utc(iso: String): Long = Instant.parse(iso).toEpochMilli()

        /** Julian Ephemeris Day of a Unix time, with SkyMath's ΔT. */
        fun jdeOf(epochMs: Long): Double =
            SkyMath.julianDay(epochMs) + SkyMath.DELTA_T_SEC / 86_400.0

        /** The Unix time SkyMath maps onto the dynamical-time instant [jde]. */
        fun epochOfJde(jde: Double): Long =
            Math.round((jde - SkyMath.DELTA_T_SEC / 86_400.0 - 2440587.5) * 86_400_000.0)

        fun hms(h: Int, m: Int, s: Double): Double = (h + m / 60.0 + s / 3600.0) * 15.0

        fun dms(d: Int, m: Int, s: Double): Double = d + m / 60.0 + s / 3600.0

        val PHASE_ELONGATION = mapOf(
            MoonPhase.NEW_MOON to 0.0,
            MoonPhase.FIRST_QUARTER to 90.0,
            MoonPhase.FULL_MOON to 180.0,
            MoonPhase.LAST_QUARTER to 270.0,
        )

        /** aa.usno.navy.mil/api/moon/phases, March 2026 onwards, UT rounded to the minute. */
        val USNO_PHASES_2026 = listOf(
            MoonPhase.FULL_MOON to "2026-03-03T11:38:00Z",
            MoonPhase.LAST_QUARTER to "2026-03-11T09:38:00Z",
            MoonPhase.NEW_MOON to "2026-03-19T01:23:00Z",
            MoonPhase.FIRST_QUARTER to "2026-03-25T19:18:00Z",
            MoonPhase.FULL_MOON to "2026-04-02T02:12:00Z",
            MoonPhase.LAST_QUARTER to "2026-04-10T04:51:00Z",
            MoonPhase.NEW_MOON to "2026-04-17T11:52:00Z",
            MoonPhase.FIRST_QUARTER to "2026-04-24T02:32:00Z",
            MoonPhase.FULL_MOON to "2026-05-01T17:23:00Z",
            MoonPhase.LAST_QUARTER to "2026-05-09T21:10:00Z",
            MoonPhase.NEW_MOON to "2026-05-16T20:01:00Z",
            MoonPhase.FIRST_QUARTER to "2026-05-23T11:11:00Z",
            MoonPhase.FULL_MOON to "2026-05-31T08:45:00Z",
            MoonPhase.LAST_QUARTER to "2026-06-08T10:00:00Z",
            MoonPhase.NEW_MOON to "2026-06-15T02:54:00Z",
            MoonPhase.FIRST_QUARTER to "2026-06-21T21:55:00Z",
            MoonPhase.FULL_MOON to "2026-06-29T23:56:00Z",
            MoonPhase.LAST_QUARTER to "2026-07-07T19:29:00Z",
            MoonPhase.NEW_MOON to "2026-07-14T09:43:00Z",
            MoonPhase.FIRST_QUARTER to "2026-07-21T11:05:00Z",
            MoonPhase.FULL_MOON to "2026-07-29T14:36:00Z",
            MoonPhase.LAST_QUARTER to "2026-08-06T02:21:00Z",
            MoonPhase.NEW_MOON to "2026-08-12T17:37:00Z",
            MoonPhase.FIRST_QUARTER to "2026-08-20T02:46:00Z",
            MoonPhase.FULL_MOON to "2026-08-28T04:18:00Z",
            MoonPhase.LAST_QUARTER to "2026-09-04T07:51:00Z",
            MoonPhase.NEW_MOON to "2026-09-11T03:27:00Z",
            MoonPhase.FIRST_QUARTER to "2026-09-18T20:44:00Z",
            MoonPhase.FULL_MOON to "2026-09-26T16:49:00Z",
            MoonPhase.LAST_QUARTER to "2026-10-03T13:25:00Z",
            MoonPhase.NEW_MOON to "2026-10-10T15:50:00Z",
            MoonPhase.FIRST_QUARTER to "2026-10-18T16:12:00Z",
            MoonPhase.FULL_MOON to "2026-10-26T04:12:00Z",
            MoonPhase.LAST_QUARTER to "2026-11-01T20:28:00Z",
            MoonPhase.NEW_MOON to "2026-11-09T07:02:00Z",
            MoonPhase.FIRST_QUARTER to "2026-11-17T11:48:00Z",
        )
    }
}
