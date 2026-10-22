package com.wm.starcam.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.asin

/**
 * NightPlanner: self-consistency everywhere, and real nights against JPL Horizons (to the
 * second), the US Naval Observatory (aa.usno.navy.mil, rise and set to the minute) and
 * sunrise-sunset.org (twilight).
 */
class NightPlannerTest {

    @Test
    fun amsterdamOctober_matchesJplToSeconds() {
        // From JPL Horizons' airless topocentric altitudes, a minute apart, interpolated to
        // the same definitions: Sun's centre at -0.833 and -18 deg; the Moon's upper limb on
        // the horizon with 34' of refraction, its radius from Horizons' angular diameter.
        val plan = NightPlanner.plan(LocalDate.of(2026, 10, 23), AMS_ZONE, AMS)
        assertTime("2026-10-23T16:28:50.6Z", plan.sunsetMs, 5, "sunset")
        assertTime("2026-10-23T18:22:41.7Z", plan.astroDuskMs, 5, "astronomical dusk")
        assertTime("2026-10-24T06:21:36.7Z", plan.sunriseMs, 5, "sunrise")
        assertTime("2026-10-23T15:12:58.9Z", plan.moonriseMs, 5, "moonrise")
        assertTime("2026-10-24T04:05:27.6Z", plan.moonsetMs, 5, "moonset")
    }

    @Test
    fun amsterdamOctober_matchesAlmanacs() {
        val plan = NightPlanner.plan(LocalDate.of(2026, 10, 23), AMS_ZONE, AMS)
        // sunrise-sunset.org (its times come out ~30 s later than JPL's); USNO agrees to its
        // minute. Sunset is 18:29 CEST, not 18:21.
        assertTime("2026-10-23T16:29:23Z", plan.sunsetMs, 60, "sunset")
        assertTime("2026-10-23T17:04:15Z", plan.civilDuskMs, 60, "civil dusk")
        assertTime("2026-10-23T17:43:54Z", plan.nauticalDuskMs, 60, "nautical dusk")
        assertTime("2026-10-23T18:23:14Z", plan.astroDuskMs, 60, "astronomical dusk")
        assertTime("2026-10-24T04:27:51Z", plan.astroDawnMs, 60, "astronomical dawn")
        assertTime("2026-10-24T05:07:12Z", plan.nauticalDawnMs, 60, "nautical dawn")
        assertTime("2026-10-24T05:46:54Z", plan.civilDawnMs, 60, "civil dawn")
        assertTime("2026-10-24T06:21:51Z", plan.sunriseMs, 60, "sunrise")
        // USNO: moonrise 17:13 CEST, moonset next morning 06:05 CEST.
        assertTime("2026-10-23T15:13:00Z", plan.moonriseMs, 120, "moonrise")
        assertTime("2026-10-24T04:05:00Z", plan.moonsetMs, 120, "moonset")
        // A gibbous Moon three days before full, up until 23 minutes before astronomical
        // dawn: that sliver is the only dark window.
        assertEquals(MoonPhase.WAXING_GIBBOUS, plan.moonPhase.name)
        assertEquals(listOf(TimeRange(plan.moonsetMs!!, plan.astroDawnMs!!)), plan.darkWindows)
        // The core culminates in daylight, due south and only 8.6 deg up.
        assertEquals(90.0 - 52.37 - 29.017, plan.coreMaxAltDeg, 0.05)
        assertEquals(180.0, SkyMath.galacticCenter(plan.coreTransitMs!!, AMS).azDeg, 0.01)
        assertNull(plan.bestCoreWindow)
        assertDarkWindowsMatchSamples(plan)
    }

    @Test
    fun tromsoOctober_matchesUsno() {
        val plan = NightPlanner.plan(LocalDate.of(2026, 10, 23), OSLO_ZONE, TROMSO)
        assertTime("2026-10-23T14:26:00Z", plan.sunsetMs, 120, "sunset")
        assertTime("2026-10-24T06:34:00Z", plan.sunriseMs, 120, "sunrise")
        // Far north the Moon rises at a slant, so the rise/set altitude matters most here.
        assertTime("2026-10-23T14:11:00Z", plan.moonriseMs, 120, "moonrise")
        assertTime("2026-10-24T03:32:00Z", plan.moonsetMs, 120, "moonset")
        // At 69.65 N the core culminates 8.7 deg below the horizon.
        assertNull(plan.coreRiseMs)
        assertNull(plan.coreSetMs)
        assertNull(plan.bestCoreWindow)
        assertEquals(90.0 - 69.65 - 29.017, plan.coreMaxAltDeg, 0.05)
        assertDarkWindowsMatchSamples(plan)
    }

    @Test
    fun sydneyJune_newMoonWithTheCoreOverhead() {
        val plan = NightPlanner.plan(LocalDate.of(2026, 6, 15), SYDNEY_ZONE, SYDNEY)
        // USNO, AEST = UTC+10.
        assertTime("2026-06-15T06:53:00Z", plan.sunsetMs, 120, "sunset")
        assertTime("2026-06-15T20:59:00Z", plan.sunriseMs, 120, "sunrise")
        assertTime("2026-06-15T06:40:00Z", plan.moonsetMs, 120, "moonset")
        assertTime("2026-06-15T22:09:00Z", plan.moonriseMs, 120, "moonrise")
        // New moon at 12:54 AEST that day: the whole astronomical night is dark.
        assertEquals(MoonPhase.NEW_MOON, plan.moonPhase.name)
        assertTrue(plan.moonPhase.illuminated < 0.02)
        assertEquals(listOf(TimeRange(plan.astroDuskMs!!, plan.astroDawnMs!!)), plan.darkWindows)
        // The core passes 4.9 deg north of the zenith, around midnight.
        assertTrue(plan.coreMaxAltDeg > 80.0)
        assertEquals(90.0 - (33.87 - 29.017), plan.coreMaxAltDeg, 0.05)
        val transit = plan.coreTransitMs!!
        assertTrue(transit > plan.astroDuskMs!! && transit < plan.astroDawnMs!!)
        assertEquals(0.0, SkyMath.wrap180(SkyMath.galacticCenter(transit, SYDNEY).azDeg), 0.5)
        val best = plan.bestCoreWindow!!
        assertTrue("best ${best.durationMs / HOUR_MS} h", best.durationMs > 8 * HOUR_MS)
        assertTrue(best.startMs >= plan.astroDuskMs!! && best.endMs <= plan.astroDawnMs!!)
        assertDarkWindowsMatchSamples(plan)
    }

    @Test
    fun quitoEquinox_matchesUsno() {
        val plan = NightPlanner.plan(LocalDate.of(2026, 3, 20), QUITO_ZONE, QUITO)
        // USNO, Ecuador = UTC-5.
        assertTime("2026-03-20T23:25:00Z", plan.sunsetMs, 120, "sunset")
        assertTime("2026-03-21T11:18:00Z", plan.sunriseMs, 120, "sunrise")
        assertTime("2026-03-21T00:52:00Z", plan.moonsetMs, 120, "moonset")
        assertTime("2026-03-21T13:20:00Z", plan.moonriseMs, 120, "moonrise")
        // A thin crescent, under 10% lit even while it is still up after dusk: the whole
        // astronomical night counts as dark.
        assertEquals(MoonPhase.WAXING_CRESCENT, plan.moonPhase.name)
        assertTrue(plan.moonPhase.illuminated < NightPlanner.DIM_MOON_FRACTION)
        assertTrue(plan.moonsetMs!! > plan.astroDuskMs!!)
        assertEquals(listOf(TimeRange(plan.astroDuskMs!!, plan.astroDawnMs!!)), plan.darkWindows)
        assertDarkWindowsMatchSamples(plan)
    }

    @Test
    fun sunEvents_hitTheirAltitudesInOrder() {
        for ((date, zone, loc) in CITY_NIGHTS) {
            val plan = NightPlanner.plan(date, zone, loc)
            val events = listOf(
                plan.sunsetMs to NightPlanner.SUNRISE_ALT_DEG,
                plan.civilDuskMs to NightPlanner.CIVIL_TWILIGHT_ALT_DEG,
                plan.nauticalDuskMs to NightPlanner.NAUTICAL_TWILIGHT_ALT_DEG,
                plan.astroDuskMs to NightPlanner.ASTRONOMICAL_TWILIGHT_ALT_DEG,
                plan.astroDawnMs to NightPlanner.ASTRONOMICAL_TWILIGHT_ALT_DEG,
                plan.nauticalDawnMs to NightPlanner.NAUTICAL_TWILIGHT_ALT_DEG,
                plan.civilDawnMs to NightPlanner.CIVIL_TWILIGHT_ALT_DEG,
                plan.sunriseMs to NightPlanner.SUNRISE_ALT_DEG,
            )
            var previous = plan.windowStartMs
            for ((index, event) in events.withIndex()) {
                val (t, alt) = event
                val what = "$loc $date event $index"
                assertNotNull(what, t)
                val sunAlt = SkyMath.sun(t!!, loc).altDeg
                assertEquals(what, alt, sunAlt, 0.05)
                assertTrue("$what out of order", t > previous)
                // Going down in the evening, up in the morning.
                val later = SkyMath.sun(t + 60_000L, loc).altDeg
                assertEquals(what, index >= 4, later > sunAlt)
                previous = t
            }
            assertTrue(previous < plan.windowEndMs)
        }
    }

    @Test
    fun moonEvents_upperLimbOnTheRefractedHorizon() {
        for ((date, zone, loc) in CITY_NIGHTS) {
            val plan = NightPlanner.plan(date, zone, loc)
            for ((t, rising) in listOf(plan.moonriseMs to true, plan.moonsetMs to false)) {
                assertNotNull(t)
                val moon = SkyMath.moonView(t!!, loc)
                assertEquals(0.0, moon.position.altDeg + moon.semidiameterDeg + 34.0 / 60.0, 0.01)
                assertEquals(rising, SkyMath.moon(t + 60_000L, loc).altDeg > moon.position.altDeg)
                // The same moment in Meeus's geocentric terms: h0 = 0.7275 π - 0°34', about
                // +0.125 deg.
                val jde = SkyMath.julianDay(t) + SkyMath.DELTA_T_SEC / 86_400.0
                val geo = SkyMath.moonEquatorial(jde)
                val geoAlt = SkyMath.equatorialToHorizontal(geo.raDeg, geo.decDeg, t, loc).altDeg
                val parallax = Math.toDegrees(asin(6378.14 / geo.distanceKm))
                assertEquals(0.7275 * parallax - 34.0 / 60.0, geoAlt, 0.02)
            }
        }
    }

    @Test
    fun midnightSun_noSunsetAndNoDarkness() {
        val plan = NightPlanner.plan(LocalDate.of(2026, 6, 21), OSLO_ZONE, TROMSO)
        assertNull(plan.sunsetMs)
        assertNull(plan.sunriseMs)
        assertNull(plan.civilDuskMs)
        assertNull(plan.nauticalDuskMs)
        assertNull(plan.astroDuskMs)
        assertNull(plan.astroDawnMs)
        assertNull(plan.nauticalDawnMs)
        assertNull(plan.civilDawnMs)
        assertTrue(plan.darkWindows.isEmpty())
        assertNull(plan.bestCoreWindow)
        assertNull(plan.coreRiseMs)
        assertNull(plan.coreSetMs)
        assertTrue(plan.coreMaxAltDeg < 0.0)
        // Lowest at midnight, still 3.1 deg up.
        assertEquals(69.65 + 23.43 - 90.0, plan.samples.minOf { it.sunAlt }, 0.1)
        assertEquals(145, plan.samples.size)
    }

    @Test
    fun summerAt52North_noAstronomicalNight() {
        val plan = NightPlanner.plan(LocalDate.of(2026, 6, 21), AMS_ZONE, AMS)
        assertNotNull(plan.sunsetMs)
        assertNotNull(plan.nauticalDuskMs)
        assertNotNull(plan.nauticalDawnMs)
        assertNotNull(plan.sunriseMs)
        assertNull(plan.astroDuskMs)
        assertNull(plan.astroDawnMs)
        assertTrue(plan.darkWindows.isEmpty())
        assertNull(plan.bestCoreWindow)
        // The Sun bottoms out 14.2 deg down.
        assertEquals(52.37 + 23.43 - 90.0, plan.samples.minOf { it.sunAlt }, 0.1)
    }

    @Test
    fun polarNight_twilightWithoutSunrise() {
        val longyearbyen = GeoLocation(78.22, 15.65)
        val plan = NightPlanner.plan(
            LocalDate.of(2026, 12, 21), ZoneId.of("Arctic/Longyearbyen"), longyearbyen,
        )
        assertNull(plan.sunsetMs)
        assertNull(plan.sunriseMs)
        assertNull(plan.civilDuskMs)
        assertNull(plan.civilDawnMs)
        // The Sun peaks 11.7 deg down at noon: nautical twilight around midday, then night.
        val events = listOf(
            plan.nauticalDuskMs, plan.astroDuskMs, plan.astroDawnMs, plan.nauticalDawnMs,
        )
        events.forEach { assertNotNull(it) }
        assertTrue(events.zipWithNext().all { (a, b) -> a!! < b!! })
        assertDarkWindowsMatchSamples(plan)
    }

    @Test
    fun northPoleInWinter_noSunEventsNoCrash() {
        val winter = LocalDate.of(2026, 12, 21)
        val plan = NightPlanner.plan(winter, ZoneOffset.UTC, GeoLocation(90.0, 0.0))
        assertNull(plan.sunsetMs)
        assertNull(plan.sunriseMs)
        assertNull(plan.astroDuskMs)
        assertNull(plan.astroDawnMs)
        assertTrue(plan.samples.all { it.sunAlt < -23.0 })
        assertEquals(145, plan.samples.size)
        assertDarkWindowsMatchSamples(plan)
        val southPole = GeoLocation(-90.0, 0.0)
        val south = NightPlanner.plan(LocalDate.of(2026, 6, 21), ZoneOffset.UTC, southPole)
        assertNull(south.sunsetMs)
        assertTrue(south.samples.all { it.sunAlt < -23.0 })
        assertDarkWindowsMatchSamples(south)
    }

    @Test
    fun moonBelowAllDay_noMoonriseOrMoonset() {
        // USNO: at Tromsø the Moon stays "continuously below the horizon" on 16 and 17 Oct.
        val plan = NightPlanner.plan(LocalDate.of(2026, 10, 16), OSLO_ZONE, TROMSO)
        assertNull(plan.moonriseMs)
        assertNull(plan.moonsetMs)
        assertTrue(plan.samples.all { it.moonAlt < 0.0 })
        // A 30% Moon, but out of the sky: the whole astronomical night is dark.
        assertTrue(plan.moonPhase.illuminated > NightPlanner.DIM_MOON_FRACTION)
        assertEquals(listOf(TimeRange(plan.astroDuskMs!!, plan.astroDawnMs!!)), plan.darkWindows)
    }

    @Test
    fun dstNight_windowIs25Hours() {
        // Clocks go back at 03:00 CEST on 25 October 2026.
        val plan = NightPlanner.plan(LocalDate.of(2026, 10, 24), AMS_ZONE, AMS)
        assertEquals(utc("2026-10-24T10:00:00Z"), plan.windowStartMs)
        assertEquals(utc("2026-10-25T11:00:00Z"), plan.windowEndMs)
        assertEquals(151, plan.samples.size)
        assertEquals(plan.windowStartMs, plan.samples.first().epochMs)
        assertEquals(plan.windowEndMs, plan.samples.last().epochMs)
        assertTrue(plan.samples.zipWithNext().all { (a, b) -> b.epochMs - a.epochMs == 600_000L })
    }

    @Test
    fun yearRound_invariantsHoldAtEveryLatitude() {
        val latitudes = doubleArrayOf(
            -90.0, -75.0, -60.0, -45.0, -30.0, -15.0, 0.0, 15.0, 30.0, 45.0, 60.0, 66.5, 75.0, 90.0,
        )
        for ((k, lat) in latitudes.withIndex()) {
            // Spread the longitudes too, with zones up to two hours off the local solar time
            // (as in Spain or western China), so some windows open well away from solar noon.
            val lon = (k * 47 % 360) - 180.0
            val loc = GeoLocation(lat, lon)
            val zone = ZoneOffset.ofHours(Math.round(lon / 15.0).toInt() + (k % 3 - 1) * 2)
            for (day in 0 until 365 step 10) {
                val date = LocalDate.of(2026, 1, 1).plusDays(day.toLong())
                val plan = NightPlanner.plan(date, zone, loc)
                val what = "$loc $date"
                val dusks = listOf(
                    plan.sunsetMs to NightPlanner.SUNRISE_ALT_DEG,
                    plan.civilDuskMs to NightPlanner.CIVIL_TWILIGHT_ALT_DEG,
                    plan.nauticalDuskMs to NightPlanner.NAUTICAL_TWILIGHT_ALT_DEG,
                    plan.astroDuskMs to NightPlanner.ASTRONOMICAL_TWILIGHT_ALT_DEG,
                )
                val dawns = listOf(
                    plan.astroDawnMs to NightPlanner.ASTRONOMICAL_TWILIGHT_ALT_DEG,
                    plan.nauticalDawnMs to NightPlanner.NAUTICAL_TWILIGHT_ALT_DEG,
                    plan.civilDawnMs to NightPlanner.CIVIL_TWILIGHT_ALT_DEG,
                    plan.sunriseMs to NightPlanner.SUNRISE_ALT_DEG,
                )
                for ((t, alt) in dusks + dawns) {
                    if (t == null) continue
                    assertTrue(what, t in plan.windowStartMs..plan.windowEndMs)
                    assertEquals(what, alt, SkyMath.sun(t, loc).altDeg, 0.01)
                }
                // Whatever exists of each chain is in order.
                for (chain in listOf(dusks, dawns)) {
                    val times = chain.mapNotNull { it.first }
                    assertEquals("$what: $times", times.sorted(), times)
                }
                for (i in dusks.indices) {
                    val dusk = dusks[i].first
                    val dawn = dawns[dawns.size - 1 - i].first
                    if (dusk != null && dawn != null) assertTrue(what, dusk < dawn)
                }
                assertDarkWindowsMatchSamples(plan)
                plan.bestCoreWindow?.let { best ->
                    val inDark = plan.darkWindows.any {
                        best.startMs >= it.startMs && best.endMs <= it.endMs
                    }
                    assertTrue(what, inDark)
                    val mid = best.startMs + best.durationMs / 2
                    val coreAlt = SkyMath.galacticCenter(mid, loc).altDeg
                    assertTrue(what, coreAlt >= NightPlanner.CORE_MIN_ALT_DEG)
                }
                assertTrue(what, plan.coreMaxAltDeg.isFinite())
                assertTrue(what, plan.samples.all { it.sunAlt.isFinite() && it.moonAlt.isFinite() })
                assertEquals(what, 145, plan.samples.size)
            }
        }
    }

    @Test
    fun samples_matchSkyMath() {
        val plan = NightPlanner.plan(LocalDate.of(2026, 6, 15), SYDNEY_ZONE, SYDNEY)
        for (s in plan.samples) {
            assertEquals(SkyMath.sun(s.epochMs, SYDNEY).altDeg, s.sunAlt, 1e-9)
            assertEquals(SkyMath.moon(s.epochMs, SYDNEY).altDeg, s.moonAlt, 1e-9)
            val core = SkyMath.galacticCenter(s.epochMs, SYDNEY)
            assertEquals(core.altDeg, s.coreAlt, 1e-9)
            assertEquals(core.azDeg, s.coreAz, 1e-9)
        }
    }

    /**
     * Each sample inside a dark window really is dark and each dark sample is inside one,
     * judged afresh from SkyMath.
     */
    private fun assertDarkWindowsMatchSamples(plan: NightPlan) {
        for (s in plan.samples) {
            val nearEdge = plan.darkWindows.any {
                abs(s.epochMs - it.startMs) < 2_000 || abs(s.epochMs - it.endMs) < 2_000
            }
            if (nearEdge) continue
            val moon = SkyMath.moonView(s.epochMs, plan.location)
            val moonDown = moon.position.altDeg + moon.semidiameterDeg + 34.0 / 60.0 < 0.0
            val moonDim = SkyMath.moonIlluminated(s.epochMs) < NightPlanner.DIM_MOON_FRACTION
            val sunDown = s.sunAlt < NightPlanner.ASTRONOMICAL_TWILIGHT_ALT_DEG
            val dark = sunDown && (moonDown || moonDim)
            val inWindow = plan.darkWindows.any { s.epochMs in it.startMs..it.endMs }
            assertEquals("${plan.location} at ${Instant.ofEpochMilli(s.epochMs)}", dark, inWindow)
        }
    }

    private fun assertTime(expectedIso: String, actualMs: Long?, tolSec: Long, what: String) {
        assertNotNull("$what missing", actualMs)
        val offSec = (actualMs!! - utc(expectedIso)) / 1000.0
        assertTrue(
            "$what: expected $expectedIso, got ${Instant.ofEpochMilli(actualMs)} ($offSec s)",
            abs(offSec) <= tolSec,
        )
    }

    private companion object {
        const val HOUR_MS = 3_600_000L

        val AMS = GeoLocation(52.37, 4.90)
        val AMS_ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")
        val SYDNEY = GeoLocation(-33.87, 151.21)
        val SYDNEY_ZONE: ZoneId = ZoneId.of("Australia/Sydney")
        val QUITO = GeoLocation(-0.18, -78.47)
        val QUITO_ZONE: ZoneId = ZoneId.of("America/Guayaquil")
        val TROMSO = GeoLocation(69.65, 18.96)
        val OSLO_ZONE: ZoneId = ZoneId.of("Europe/Oslo")

        val CITY_NIGHTS = listOf(
            Triple(LocalDate.of(2026, 10, 23), AMS_ZONE, AMS),
            Triple(LocalDate.of(2026, 6, 15), SYDNEY_ZONE, SYDNEY),
            Triple(LocalDate.of(2026, 3, 20), QUITO_ZONE, QUITO),
        )

        fun utc(iso: String): Long = Instant.parse(iso).toEpochMilli()
    }
}
