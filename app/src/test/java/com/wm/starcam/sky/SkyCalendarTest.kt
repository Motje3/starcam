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

/**
 * The sky calendar against published facts: NASA's and the IMO's tables, the 2026 eclipses as
 * timed for Reykjavik, London and Berlin, the planets' 2026 oppositions and elongations.
 */
class SkyCalendarTest {

    private val utc = ZoneOffset.UTC
    private val amsterdam = GeoLocation(52.37, 4.90)
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    private fun minutesApart(a: Long, b: Long) = abs(a - b) / 60_000.0

    @Test
    fun moonPhasesOf2026() {
        val phases = SkyCalendar.moonPhases(ms("2026-01-01T00:00:00Z"), ms("2027-01-01T00:00:00Z"))
        val full = phases.filter { it.second == 2 }.map { it.first }
        val new = phases.filter { it.second == 0 }.map { it.first }
        assertEquals(13, full.size)  // 2026 has a blue moon (31 May)
        assertEquals(12, new.size)
        // Full moons at the two lunar eclipses, the new moon at the August solar eclipse.
        assertTrue(full.any { minutesApart(it, ms("2026-03-03T11:38:00Z")) < 30 })
        assertTrue(full.any { minutesApart(it, ms("2026-08-28T04:18:00Z")) < 30 })
        assertTrue(new.any { minutesApart(it, ms("2026-08-12T17:37:00Z")) < 30 })
    }

    @Test
    fun lunarEclipsesOf2026() {
        val march = SkyCalendar.lunarEclipseNear(ms("2026-03-03T11:38:00Z"))
        assertNotNull(march)
        // NASA: greatest 11:34 UT, umbral magnitude 1.151; total.
        assertTrue(minutesApart(march!!.greatestMs, ms("2026-03-03T11:34:00Z")) < 4)
        assertEquals(1.151, march.umbral, 0.02)
        assertNotNull(march.totalStartMs)
        // Totality lasts about an hour (NASA: 58 min).
        assertEquals(58.0, (march.totalEndMs!! - march.totalStartMs!!) / 60_000.0, 4.0)
        val august = SkyCalendar.lunarEclipseNear(ms("2026-08-28T04:18:00Z"))
        assertNotNull(august)
        // Partial, umbral magnitude 0.932, greatest 04:13 UT.
        assertTrue(minutesApart(august!!.greatestMs, ms("2026-08-28T04:13:00Z")) < 4)
        assertEquals(0.932, august.umbral, 0.02)
        assertNull(august.totalStartMs)
        // An ordinary full moon: no eclipse at all.
        assertNull(SkyCalendar.lunarEclipseNear(ms("2026-10-26T04:12:00Z")))
    }

    @Test
    fun theAugust2026SolarEclipseFromThreeCities() {
        val newMoon = ms("2026-08-12T17:37:00Z")
        // Reykjavik sits at the edge of the path of totality (about a minute of it); maximum
        // 17:48:48 UTC. The ephemeris is good to a few arcseconds: within a hair of total.
        val reykjavik = SkyCalendar.localSolarEclipse(newMoon, GeoLocation(64.146, -21.94))
        assertNotNull(reykjavik)
        assertTrue("Reykjavik ${reykjavik!!.magnitude}", reykjavik.magnitude > 0.99)
        assertTrue(minutesApart(reykjavik.maxMs, ms("2026-08-12T17:48:48Z")) < 3)
        // León, well inside the path in Spain: total, at sunset (about 20:28 local).
        val leon = SkyCalendar.localSolarEclipse(newMoon, GeoLocation(42.60, -5.57))!!
        assertTrue(leon.total)
        assertTrue(minutesApart(leon.maxMs, ms("2026-08-12T18:28:00Z")) < 5)
        // London about 91% at 19:13 BST, Berlin about 84% at 20:08 CEST (published figures).
        val london = SkyCalendar.localSolarEclipse(newMoon, GeoLocation(51.507, -0.128))!!
        assertTrue(minutesApart(london.maxMs, ms("2026-08-12T18:13:00Z")) < 5)
        assertTrue("London ${london.magnitude} ${london.obscuration}", london.magnitude in 0.86..0.96)
        val berlin = SkyCalendar.localSolarEclipse(newMoon, GeoLocation(52.52, 13.405))!!
        assertTrue(minutesApart(berlin.maxMs, ms("2026-08-12T18:08:00Z")) < 6)
        assertTrue("Berlin ${berlin.magnitude} ${berlin.obscuration}", berlin.magnitude in 0.78..0.92)
        assertTrue(!london.total && !berlin.total)
        // No eclipse from Sydney (night there).
        assertNull(SkyCalendar.localSolarEclipse(newMoon, GeoLocation(-33.87, 151.21)))
    }

    /** Not a check: the numbers behind the eclipse and year tests, in the report's output. */
    @Test
    fun printNumbers() {
        val newMoon = ms("2026-08-12T17:37:00Z")
        for ((name, loc) in listOf("Reykjavik" to GeoLocation(64.146, -21.94), "London" to GeoLocation(51.507, -0.128),
            "Berlin" to GeoLocation(52.52, 13.405), "Leon" to GeoLocation(42.6, -5.57), "Madrid" to GeoLocation(40.42, -3.70))) {
            val e = SkyCalendar.localSolarEclipse(newMoon, loc)
            println("$name: " + (e?.let { "max ${Instant.ofEpochMilli(it.maxMs)} mag %.3f obsc %.3f total ${it.total} sunAlt %.1f".format(it.magnitude, it.obscuration, it.sunAltAtMax) } ?: "none"))
        }
        val events = SkyCalendar.events(ms("2026-10-24T12:00:00Z"), 365, amsterdam, ZoneId.of("Europe/Amsterdam"), 19.5)
        events.forEach { println("${Instant.ofEpochMilli(it.atMs)} ${it.kind} ${it.rating} ${it.title}: ${it.summary}") }
        val zone = ZoneId.of("Europe/Amsterdam")
        for (d in listOf("2027-03-08", "2027-04-06", "2027-05-06", "2027-06-04", "2027-08-02", "2027-08-31")) {
            val p = NightPlanner.plan(LocalDate.parse(d), zone, amsterdam)
            println("$d: min ${p.coreMinAltDeg} dark ${p.darkWindows.map { SkyCalendar.hm(it.startMs, zone) + "-" + SkyCalendar.hm(it.endMs, zone) }} " +
                "best ${p.bestCoreWindow?.let { SkyCalendar.hm(it.startMs, zone) + "-" + SkyCalendar.hm(it.endMs, zone) }} " +
                "coreRise ${p.coreRiseMs?.let { SkyCalendar.hm(it, zone) }} transit ${p.coreTransitMs?.let { SkyCalendar.hm(it, zone) }}")
        }
    }

    @Test
    fun obscurationGeometry() {
        assertEquals(0.0, SkyCalendar.obscuration(1.0, 0.25, 0.26), 1e-9)
        assertEquals(1.0, SkyCalendar.obscuration(0.0, 0.25, 0.26), 1e-9)
        // An annular eclipse centred: the Moon's disc over the Sun's.
        assertEquals((0.24 * 0.24) / (0.25 * 0.25), SkyCalendar.obscuration(0.0, 0.25, 0.24), 1e-9)
        // Half-way: between none and all.
        val half = SkyCalendar.obscuration(0.25, 0.25, 0.25)
        assertTrue(half in 0.3..0.5)
    }

    @Test
    fun meteorShowerPeaksLandOnTheirDates() {
        fun peak(name: String, year: Int): LocalDate {
            val s = SkyCalendar.SHOWERS.first { it.name == name }
            val guess = LocalDate.of(year, 3, 20).atStartOfDay(utc).toInstant().toEpochMilli() +
                (s.peakSolarLon / 0.98565 * 86_400_000.0).toLong()
            return Instant.ofEpochMilli(SkyCalendar.whenSolarLongitude(s.peakSolarLon, guess)).atZone(utc).toLocalDate()
        }
        // IMO 2026: Perseids 12-13 Aug, Geminids 14 Dec, Orionids 21 Oct, Lyrids 22 Apr; Quadrantids 3-4 Jan 2027.
        assertTrue(peak("Perseids", 2026) in listOf(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 13)))
        assertEquals(LocalDate.of(2026, 12, 14), peak("Geminids", 2026))
        assertTrue(peak("Orionids", 2026) in listOf(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 22)))
        assertTrue(peak("Lyrids", 2026) in listOf(LocalDate.of(2026, 4, 22), LocalDate.of(2026, 4, 23)))
        assertTrue(peak("Quadrantids", 2026) in listOf(LocalDate.of(2027, 1, 3), LocalDate.of(2027, 1, 4)))
        // The J2000 solar longitude at the 2026 March equinox (20 Mar 14:46 UT): 0 of date, -0.37 J2000.
        assertEquals(359.63, SkyCalendar.solarLongitudeJ2000(ms("2026-03-20T14:46:00Z")), 0.03)
    }

    @Test
    fun theMoonDecidesAShowersRating() {
        val perseids = SkyCalendar.SHOWERS.first { it.name == "Perseids" }
        // 2026: new moon on 12 Aug - as good as it gets.
        val e2026 = SkyCalendar.showerEvent(perseids, ms("2026-08-12T20:00:00Z"), amsterdam, ZoneId.of("Europe/Amsterdam"), null)
        assertEquals(SkyEvent.Rating.GREAT, e2026.rating)
        assertNotNull(e2026.lookAtMs)
        // 2022: full moon on 12 Aug.
        val e2022 = SkyCalendar.showerEvent(perseids, ms("2022-08-13T01:00:00Z"), amsterdam, ZoneId.of("Europe/Amsterdam"), null)
        assertTrue(e2022.rating.ordinal > e2026.rating.ordinal)
        assertTrue(e2022.summary, e2022.summary.contains("Moon"))
        // From Sydney the Quadrantids' radiant (dec +49) never gets up.
        val quads = SkyCalendar.SHOWERS.first { it.name == "Quadrantids" }
        val south = SkyCalendar.showerEvent(quads, ms("2027-01-04T00:00:00Z"), GeoLocation(-33.87, 151.21), ZoneId.of("Australia/Sydney"), null)
        assertEquals(SkyEvent.Rating.POOR, south.rating)
        assertNull(south.lookAtMs)
        // Your own sky: fewer meteors in town.
        val town = SkyCalendar.showerEvent(perseids, ms("2026-08-12T20:00:00Z"), amsterdam, ZoneId.of("Europe/Amsterdam"), 19.5)
        assertTrue(town.details.any { it.startsWith("From your sky") })
        assertEquals(6.6, SkyCalendar.limitingMagnitude(22.0), 0.1)
        assertEquals(5.1, SkyCalendar.limitingMagnitude(19.5), 0.15)
    }

    @Test
    fun aSouthernMilkyWaySeason() {
        // Sydney, 2027: the core high overhead; a run of nights around every new moon from
        // February to October, the winter ones long.
        val zone = ZoneId.of("Australia/Sydney")
        val events = SkyCalendar.events(ms("2027-01-01T00:00:00Z"), 365, GeoLocation(-33.87, 151.21), zone)
        val runs = events.filter { it.kind == SkyEvent.Kind.MILKY_WAY }
        assertTrue("runs ${runs.size}", runs.size >= 8)
        assertTrue(runs.count { it.rating == SkyEvent.Rating.GREAT } >= 4)
        // June's new moon (4 Jun 2027): a great night in the middle of a run.
        val june = runs.first { r -> r.endMs!! >= ms("2027-06-04T12:00:00Z") && r.atMs <= ms("2027-06-05T12:00:00Z") }
        assertEquals(SkyEvent.Rating.GREAT, june.rating)
    }

    @Test
    fun planetsAtTheirBestIn2026() {
        val events = SkyCalendar.events(ms("2026-01-01T00:00:00Z"), 365, amsterdam, utc)
        val planets = events.filter { it.kind == SkyEvent.Kind.PLANET }
        fun at(title: String) = planets.firstOrNull { it.title == title }?.atMs
        // Jupiter 10 Jan, Saturn 4 Oct, Venus greatest eastern elongation 15 Aug (45.9 deg).
        assertTrue(minutesApart(at("Jupiter at opposition")!!, ms("2026-01-10T17:42:00Z")) < 24 * 60)
        assertTrue(minutesApart(at("Saturn at opposition")!!, ms("2026-10-04T12:00:00Z")) < 24 * 60)
        val venus = planets.first { it.title == "Venus at its best in the evening sky" }
        assertTrue(minutesApart(venus.atMs, ms("2026-08-15T15:00:00Z")) < 24 * 60)
        assertTrue(venus.summary.startsWith("46°"))
        // No Mars opposition in 2026 (it comes in February 2027).
        assertNull(at("Mars at opposition"))
    }

    @Test
    fun aYearForAmsterdam() {
        val events = SkyCalendar.events(ms("2026-10-24T12:00:00Z"), 365, amsterdam, ZoneId.of("Europe/Amsterdam"), 19.5)
        val kinds = events.groupBy { it.kind }
        assertEquals(13, kinds[SkyEvent.Kind.METEORS]?.size)
        assertTrue((kinds[SkyEvent.Kind.NEW_MOON]?.size ?: 0) in 12..13)
        // From late October the core is gone from Amsterdam's dark hours until the spring, and
        // at 52 N it never gets far up: a short season of short windows, low in the south-east
        // before dawn (the summer has no astronomical night at all).
        val milkyWay = kinds[SkyEvent.Kind.MILKY_WAY].orEmpty()
        val opens = milkyWay.first()
        assertEquals("Milky Way season begins", opens.title)
        val month = Instant.ofEpochMilli(opens.atMs).atZone(utc).monthValue
        assertTrue("opens in month $month", month in 2..5)
        val runs = milkyWay.filter { it.title == "Milky Way nights" }
        assertTrue(runs.isNotEmpty())
        assertTrue(runs.all { it.details.any { d -> "stays low" in d } })
        assertTrue(milkyWay.any { it.title == "Milky Way season ends" })
        // 2 Aug 2027: a total solar eclipse over Spain and North Africa, partial in Amsterdam.
        val sun = kinds[SkyEvent.Kind.SOLAR_ECLIPSE].orEmpty().filter { it.lookAtMs != null }
        assertTrue(sun.any { Instant.ofEpochMilli(it.atMs).atZone(utc).toLocalDate() == LocalDate.of(2027, 8, 2) })
        // Sorted, with a summary each.
        assertEquals(events.sortedBy { it.atMs }, events)
        assertTrue(events.all { it.summary.isNotBlank() })
    }
}
