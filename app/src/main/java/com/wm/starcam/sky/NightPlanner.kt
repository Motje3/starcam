package com.wm.starcam.sky

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A stretch of time in Unix milliseconds, from [startMs] to [endMs]. */
data class TimeRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * One point of the night's timeline. Altitudes are geometric (no refraction) in degrees, the
 * Moon's seen from the observer rather than the Earth's centre; [coreAz] is the galactic
 * centre's azimuth from north through east.
 */
data class NightSample(
    val epochMs: Long,
    val sunAlt: Double,
    val moonAlt: Double,
    val coreAlt: Double,
    val coreAz: Double,
)

/**
 * One night at one place, as the Tonight planner shows it. Times are Unix milliseconds. An event
 * that does not happen inside the window is null: no sunset under the midnight sun, no
 * astronomical dusk in a northern summer, no moonrise on a day the Moon stays down.
 */
data class NightPlan(
    val location: GeoLocation,
    /** The night examined: local noon of the given date to local noon of the next day. */
    val windowStartMs: Long,
    val windowEndMs: Long,
    /** Upper limb on the horizon: centre at -0.833 deg (16' semidiameter, 34' refraction). */
    val sunsetMs: Long?,
    val sunriseMs: Long?,
    /** The Sun's centre passing -6, -12 and -18 deg on the way down... */
    val civilDuskMs: Long?,
    val nauticalDuskMs: Long?,
    val astroDuskMs: Long?,
    /** ...and on the way up after it. */
    val astroDawnMs: Long?,
    val nauticalDawnMs: Long?,
    val civilDawnMs: Long?,
    /**
     * The first moonrise and the first moonset inside the window, either may come first. Rise
     * and set are the upper limb on the refracted horizon, as almanacs give them.
     */
    val moonriseMs: Long?,
    val moonsetMs: Long?,
    /** At the middle of the astronomical night, or at local midnight when there is none. */
    val moonPhase: MoonPhase,
    /** The galactic centre's first rise and set (refracted horizon) inside the window. */
    val coreRiseMs: Long?,
    val coreSetMs: Long?,
    /**
     * Its upper transit, when it stands highest: due south, or due north for observers south
     * of its declination (29 deg S). Given even when the core never clears the horizon.
     */
    val coreTransitMs: Long?,
    /** Geometric altitude at the transit; negative where the core never rises. */
    val coreMaxAltDeg: Double,
    /** Sun below -18 deg, and the Moon down or less than 10% lit: a truly dark sky. */
    val darkWindows: List<TimeRange>,
    /** The longest part of [darkWindows] with the core at least [coreMinAltDeg] up, if any. */
    val bestCoreWindow: TimeRange?,
    /** Every 10 minutes from [windowStartMs] to [windowEndMs], for drawing a timeline. */
    val samples: List<NightSample>,
    /** How high the core must stand to count here ([NightPlanner.coreMinAltDeg]). */
    val coreMinAltDeg: Double = NightPlanner.CORE_MIN_ALT_DEG,
)

/**
 * Works out a night for astrophotography: when it gets dark, when the Moon is out of the way and
 * when the Milky Way's core stands high enough to shoot. Pure arithmetic on [SkyMath], no
 * network and no Android: about 2.5 ms a night on a desktop JVM, so call it from a background
 * dispatcher rather than from composition.
 *
 * Events are found by scanning the window every 5 minutes and bisecting each bracket where a
 * quantity crosses its threshold down to a second, so they are as good as the ephemeris (a
 * couple of seconds). A dip shorter than the scan step (the Sun grazing -18 deg for a few
 * minutes on the last nights before astronomical night ends for the summer) can be missed;
 * such a window is too short to use anyway.
 */
object NightPlanner {

    /** Sunrise and sunset altitude of the Sun's centre (deg): semidiameter plus refraction. */
    const val SUNRISE_ALT_DEG = -0.833
    const val CIVIL_TWILIGHT_ALT_DEG = -6.0
    const val NAUTICAL_TWILIGHT_ALT_DEG = -12.0
    const val ASTRONOMICAL_TWILIGHT_ALT_DEG = -18.0

    /** A Moon lit less than this does not brighten the sky enough to spoil a dark window. */
    const val DIM_MOON_FRACTION = 0.10

    /** The core has to clear this altitude (deg) to be worth shooting through the haze. */
    const val CORE_MIN_ALT_DEG = 10.0

    /**
     * The core's working minimum at [latDeg]: [CORE_MIN_ALT_DEG], or 3 deg under the highest it
     * ever gets where that is lower - in the Netherlands, Germany or England the core culminates
     * at 5-9 deg and is shot low over an open southern horizon all summer; no lower than 3 deg.
     */
    fun coreMinAltDeg(latDeg: Double): Double {
        val highest = 90.0 - kotlin.math.abs(latDeg - SkyMath.GALACTIC_CENTER_DEC_J2000)
        return minOf(CORE_MIN_ALT_DEG, highest - 3.0).coerceAtLeast(3.0)
    }

    /**
     * Refraction at the horizon (deg), the standard 34'. Rise and set are the moment the top of
     * a body appears on the horizon, which refraction lifts into view while it is still 34'
     * below. For the Moon, Meeus's h0 = 0.7275 π − 0.5667 (about +0.125 deg) is the same moment
     * for the geocentric altitude; [SkyMath.moon] is topocentric, which already carries the
     * parallax π, so here the centre stands at -(34' + semidiameter), about -0.83 deg.
     */
    private const val HORIZON_REFRACTION_DEG = 34.0 / 60.0

    private const val SCAN_STEP_MS = 5 * 60_000L
    private const val REFINE_MS = 1_000L
    private const val SAMPLE_STEP_MS = 10 * 60_000L
    private const val DAY_MS = 86_400_000L

    /** How fast the sky turns: degrees of hour angle per millisecond. */
    private const val SIDEREAL_DEG_PER_MS = 360.98564736629 / 86_400_000.0

    /** [date] is the calendar date the night starts on in [zone]. */
    fun plan(date: LocalDate, zone: ZoneId, loc: GeoLocation): NightPlan {
        val start = date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        val nextNoon = date.plusDays(1).atTime(LocalTime.NOON).atZone(zone).toInstant()
            .toEpochMilli()
        // Only a zone that skipped a calendar day (Samoa, 30 Dec 2011) can make this empty.
        val end = if (nextNoon > start) nextNoon else start + DAY_MS
        val grid = scanGrid(start, end)

        // One pass computes everything at the grid points; the searches only revisit brackets.
        val sunAlt = DoubleArray(grid.size)
        val moonAlt = DoubleArray(grid.size)
        val moonUp = DoubleArray(grid.size)
        val moonLit = DoubleArray(grid.size)
        val coreAlt = DoubleArray(grid.size)
        val coreAz = DoubleArray(grid.size)
        for (i in grid.indices) {
            val t = grid[i]
            sunAlt[i] = SkyMath.sun(t, loc).altDeg
            val moon = SkyMath.moonView(t, loc)
            moonAlt[i] = moon.position.altDeg
            moonUp[i] = moonAboveHorizon(moon)
            moonLit[i] = SkyMath.moonIlluminated(t) - DIM_MOON_FRACTION
            val core = SkyMath.galacticCenter(t, loc)
            coreAlt[i] = core.altDeg
            coreAz[i] = core.azDeg
        }

        fun sunTrack(altDeg: Double): Track {
            val values = DoubleArray(grid.size) { sunAlt[it] - altDeg }
            return Track(grid, values) { SkyMath.sun(it, loc).altDeg - altDeg }
        }
        fun coreTrack(altDeg: Double): Track {
            val values = DoubleArray(grid.size) { coreAlt[it] - altDeg }
            return Track(grid, values) { SkyMath.galacticCenter(it, loc).altDeg - altDeg }
        }
        val sunHorizon = sunTrack(SUNRISE_ALT_DEG)
        val civil = sunTrack(CIVIL_TWILIGHT_ALT_DEG)
        val nautical = sunTrack(NAUTICAL_TWILIGHT_ALT_DEG)
        val astronomical = sunTrack(ASTRONOMICAL_TWILIGHT_ALT_DEG)
        val moonHorizon = Track(grid, moonUp) { moonAboveHorizon(SkyMath.moonView(it, loc)) }
        val moonBright = Track(grid, moonLit) { SkyMath.moonIlluminated(it) - DIM_MOON_FRACTION }
        val coreHorizon = coreTrack(-HORIZON_REFRACTION_DEG)
        val coreMin = coreMinAltDeg(loc.latDeg)
        val coreHigh = coreTrack(coreMin)

        val astroNight = astronomical.below()
        val darkWindows = intersect(astroNight, union(moonHorizon.below(), moonBright.below()))
        val bestCoreWindow = intersect(darkWindows, coreHigh.above()).maxByOrNull { it.durationMs }

        val longestNight = astroNight.maxByOrNull { it.durationMs }
        val phaseAt = longestNight?.let { it.startMs + it.durationMs / 2 }
            ?: date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val transit = coreTransit(start, end, loc)
        val coreMaxAlt = if (transit != null) {
            SkyMath.galacticCenter(transit, loc).altDeg
        } else {
            coreAlt.max()
        }

        val samples = grid.indices
            .filter { (grid[it] - start) % SAMPLE_STEP_MS == 0L }
            .map { NightSample(grid[it], sunAlt[it], moonAlt[it], coreAlt[it], coreAz[it]) }

        return NightPlan(
            location = loc,
            windowStartMs = start,
            windowEndMs = end,
            sunsetMs = sunHorizon.firstSet(),
            sunriseMs = sunHorizon.dawn(),
            civilDuskMs = civil.firstSet(),
            nauticalDuskMs = nautical.firstSet(),
            astroDuskMs = astronomical.firstSet(),
            astroDawnMs = astronomical.dawn(),
            nauticalDawnMs = nautical.dawn(),
            civilDawnMs = civil.dawn(),
            moonriseMs = moonHorizon.firstRise(),
            moonsetMs = moonHorizon.firstSet(),
            moonPhase = SkyMath.moonPhase(phaseAt),
            coreRiseMs = coreHorizon.firstRise(),
            coreSetMs = coreHorizon.firstSet(),
            coreTransitMs = transit,
            coreMaxAltDeg = coreMaxAlt,
            darkWindows = darkWindows,
            bestCoreWindow = bestCoreWindow,
            samples = samples,
            coreMinAltDeg = coreMin,
        )
    }

    /** How far the Moon's upper limb stands above the refracted horizon (deg); 0 at rise/set. */
    private fun moonAboveHorizon(moon: MoonView): Double =
        moon.position.altDeg + moon.semidiameterDeg + HORIZON_REFRACTION_DEG

    /**
     * The galactic centre's first upper transit inside the window. Its hour angle grows at the
     * sidereal rate, so the transit follows from the hour angle at the start; two Newton steps
     * on the real hour angle absorb what that rate leaves out (precession and nutation, well
     * under a second).
     */
    private fun coreTransit(start: Long, end: Long, loc: GeoLocation): Long? {
        fun hourAngle(t: Long) = SkyMath.j2000HourAngleDeg(
            SkyMath.GALACTIC_CENTER_RA_J2000, SkyMath.GALACTIC_CENTER_DEC_J2000, t, loc,
        )
        val untilTransitDeg = SkyMath.norm360(-hourAngle(start))
        var t = start + (untilTransitDeg / SIDEREAL_DEG_PER_MS).toLong()
        repeat(2) { t -= (hourAngle(t) / SIDEREAL_DEG_PER_MS).toLong() }
        return t.takeIf { it in start..end }
    }

    /** Scan points: every [SCAN_STEP_MS] from [start], plus [end] if the step misses it. */
    private fun scanGrid(start: Long, end: Long): LongArray {
        val steps = ((end - start) / SCAN_STEP_MS).toInt()
        val onGrid = start + steps * SCAN_STEP_MS == end
        return LongArray(if (onGrid) steps + 1 else steps + 2) { i ->
            if (i <= steps) start + i * SCAN_STEP_MS else end
        }
    }

    /** The moment a [Track] changes sign, and which way. */
    private class Crossing(val epochMs: Long, val rising: Boolean)

    /**
     * One quantity over the window, expressed as how far it is above its threshold: [values]
     * at the scan grid, [f] anywhere, for refining between grid points.
     */
    private class Track(val grid: LongArray, val values: DoubleArray, val f: (Long) -> Double) {

        /** Every sign change between grid points, each bisected down to [REFINE_MS]. */
        val crossings: List<Crossing> by lazy {
            val out = ArrayList<Crossing>()
            for (i in 0 until grid.size - 1) {
                val wasAbove = values[i] >= 0.0
                if ((values[i + 1] >= 0.0) == wasAbove) continue
                var lo = grid[i]
                var hi = grid[i + 1]
                while (hi - lo > REFINE_MS) {
                    val mid = (lo + hi) / 2
                    if ((f(mid) >= 0.0) == wasAbove) lo = mid else hi = mid
                }
                out += Crossing((lo + hi) / 2, rising = !wasAbove)
            }
            out
        }

        fun firstRise(): Long? = crossings.firstOrNull { it.rising }?.epochMs

        fun firstSet(): Long? = crossings.firstOrNull { !it.rising }?.epochMs

        /**
         * The morning crossing: the first rise after the evening's [firstSet]. With no set in
         * the window (the window opened in darkness, as at the end of a polar night) it is
         * simply the first rise.
         */
        fun dawn(): Long? {
            val dusk = firstSet() ?: return firstRise()
            return crossings.firstOrNull { it.rising && it.epochMs > dusk }?.epochMs
        }

        fun above(): List<TimeRange> = spans(positive = true)

        fun below(): List<TimeRange> = spans(positive = false)

        private fun spans(positive: Boolean): List<TimeRange> {
            val out = ArrayList<TimeRange>()
            var inside = (values[0] >= 0.0) == positive
            var from = grid.first()
            for (c in crossings) {
                if (inside) out += TimeRange(from, c.epochMs)
                inside = c.rising == positive
                from = c.epochMs
            }
            if (inside) out += TimeRange(from, grid.last())
            return out.filter { it.endMs > it.startMs }
        }
    }

    /** Overlap of two sorted, disjoint lists of ranges. */
    private fun intersect(a: List<TimeRange>, b: List<TimeRange>): List<TimeRange> {
        val out = ArrayList<TimeRange>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            val from = maxOf(a[i].startMs, b[j].startMs)
            val to = minOf(a[i].endMs, b[j].endMs)
            if (to > from) out += TimeRange(from, to)
            if (a[i].endMs < b[j].endMs) i++ else j++
        }
        return out
    }

    /** Either of two lists of ranges, merged where they touch or overlap. */
    private fun union(a: List<TimeRange>, b: List<TimeRange>): List<TimeRange> {
        val out = ArrayList<TimeRange>()
        for (r in (a + b).sortedBy { it.startMs }) {
            val last = out.lastOrNull()
            if (last != null && r.startMs <= last.endMs) {
                out[out.size - 1] = TimeRange(last.startMs, maxOf(last.endMs, r.endMs))
            } else {
                out += r
            }
        }
        return out
    }
}
