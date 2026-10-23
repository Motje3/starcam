package com.wm.starcam.sky

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One thing in the sky worth knowing about, worked out for one place. */
data class SkyEvent(
    val kind: Kind,
    val title: String,
    /** The moment it is about: a peak, a phase, greatest eclipse, the first night of a window. */
    val atMs: Long,
    /** The end of a stretch of nights, when it is one. */
    val endMs: Long? = null,
    val rating: Rating,
    val summary: String,
    val details: List<String> = emptyList(),
    /** When to look, for the sky view; null when it cannot be seen from here. */
    val lookAtMs: Long? = null,
) {
    enum class Kind { MILKY_WAY, METEORS, NEW_MOON, FULL_MOON, LUNAR_ECLIPSE, SOLAR_ECLIPSE, PLANET, CONJUNCTION }

    enum class Rating(val label: String) { GREAT("Great"), GOOD("Good"), FAIR("Fair"), POOR("Poor"), INFO("") }

    /** A stable identity, for reminders. */
    val id: String get() = "${kind.name}:${atMs / 60_000L}"
}

/**
 * The sky calendar: meteor showers rated by the Moon and the radiant's height where you are,
 * the stretches of nights when the Milky Way's core is up in a dark sky, new and full moons,
 * lunar and solar eclipses as seen from your place, planets at their best and close pairings.
 * All of it computed on the phone from [SkyMath], [Planets] and [NightPlanner].
 */
object SkyCalendar {

    /** A meteor shower from the IMO working list: peak by J2000 solar longitude, so any year works. */
    data class Shower(
        val name: String,
        val peakSolarLon: Double,
        val zhr: Int,
        val raDeg: Double,
        val decDeg: Double,
        val speedKms: Int,
        val constellation: String,
        val active: String,
        val note: String,
        /** Population index: how much faster faint meteors add up than bright ones. */
        val r: Double = 2.3,
    )

    val SHOWERS = listOf(
        Shower("Quadrantids", 283.15, 80, 230.0, 49.0, 41, "Boötes", "Dec 28 – Jan 12",
            "A sharp peak lasting only a few hours: the timing decides the night.", 2.1),
        Shower("Lyrids", 32.32, 18, 271.0, 34.0, 49, "Lyra", "Apr 14 – 30", "Swift, bright meteors from near Vega.", 2.1),
        Shower("Eta Aquariids", 45.5, 50, 338.0, -1.0, 66, "Aquarius", "Apr 19 – May 28",
            "Dust of Halley's comet; best from the southern hemisphere, in the hour before dawn.", 2.4),
        Shower("Southern Delta Aquariids", 127.0, 25, 340.0, -16.0, 41, "Aquarius", "Jul 12 – Aug 23",
            "A long, gentle shower, best seen from the south.", 2.5),
        Shower("Alpha Capricornids", 127.0, 5, 307.0, -10.0, 23, "Capricornus", "Jul 3 – Aug 15",
            "Few, but slow and bright, with the odd fireball.", 2.5),
        Shower("Perseids", 140.0, 100, 48.0, 58.0, 59, "Perseus", "Jul 17 – Aug 24",
            "The summer favourite: rich, fast and bright, with trains that linger.", 2.2),
        Shower("Draconids", 195.4, 10, 262.0, 54.0, 20, "Draco", "Oct 6 – 10",
            "Usually quiet, now and then a storm; unusually, best in the evening.", 2.6),
        Shower("Southern Taurids", 197.0, 5, 32.0, 9.0, 27, "Cetus and Taurus", "Sep 10 – Nov 20",
            "Slow and sparse, but famous for fireballs.", 2.3),
        Shower("Orionids", 208.0, 20, 95.0, 16.0, 66, "Orion", "Oct 2 – Nov 7",
            "Halley's comet's dust again: fast meteors after midnight.", 2.5),
        Shower("Northern Taurids", 230.0, 5, 58.0, 22.0, 29, "Taurus", "Oct 20 – Dec 10",
            "Slow and sparse, but famous for fireballs.", 2.3),
        Shower("Leonids", 235.27, 15, 152.0, 22.0, 71, "Leo", "Nov 6 – 30",
            "The fastest meteors of the year, after midnight.", 2.5),
        Shower("Geminids", 262.2, 150, 112.0, 33.0, 35, "Gemini", "Dec 4 – 20",
            "The richest shower of the year, active all night from mid-evening.", 2.6),
        Shower("Ursids", 270.7, 10, 217.0, 76.0, 33, "Ursa Minor", "Dec 17 – 26",
            "A modest shower near the Little Dipper, up all night in the north.", 3.0),
    )

    private const val HOUR = 3_600_000L
    private const val DAY = 86_400_000L
    private const val J2000 = 2451545.0

    /** The Milky Way's nights are worked out this far ahead. */
    const val MILKY_WAY_DAYS = 366

    /** A night counts for the Milky Way with at least this long a dark, moonless window on the core. */
    private const val MIN_WINDOW_MS = 20 * 60_000L

    /**
     * Everything from [fromMs] for [days] days at [loc], times in [zone]. [skySqm] (the zenith
     * brightness where you are, from the dark-sky map) turns meteor rates into what your own
     * sky shows.
     */
    fun events(fromMs: Long, days: Int, loc: GeoLocation, zone: ZoneId, skySqm: Double? = null): List<SkyEvent> {
        val toMs = fromMs + days * DAY
        val out = ArrayList<SkyEvent>()
        out += moonEvents(fromMs, toMs, zone)
        out += meteorShowers(fromMs, toMs, loc, zone, skySqm)
        out += milkyWayNights(fromMs, minOf(days, MILKY_WAY_DAYS), loc, zone)
        out += lunarEclipses(fromMs, toMs, loc, zone)
        out += solarEclipses(fromMs, toMs, loc, zone)
        out += planetEvents(fromMs, toMs, loc, zone)
        out += conjunctions(fromMs, toMs, loc, zone)
        return out.sortedBy { it.atMs }
    }

    // ------------------------------------------------------------------------- formatting

    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.US)
    private val WEEKDAY_DAY_MONTH = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)

    internal fun hm(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(HM)
    internal fun dayMonth(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(DAY_MONTH)
    private fun weekdayDayMonth(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(WEEKDAY_DAY_MONTH)

    private val POINTS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    internal fun compass(azDeg: Double): String = POINTS[((azDeg / 45.0).roundToInt() % 8 + 8) % 8]

    /** "the night of 12-13 Aug" for the night that starts on the evening of [evening]. */
    private fun nightOf(evening: LocalDate): String {
        val next = evening.plusDays(1)
        return if (evening.month == next.month) "${evening.dayOfMonth}–${next.dayOfMonth} ${next.format(DateTimeFormatter.ofPattern("MMM", Locale.US))}"
        else "${evening.format(DateTimeFormatter.ofPattern("d MMM", Locale.US))} – ${next.format(DateTimeFormatter.ofPattern("d MMM", Locale.US))}"
    }

    // ---------------------------------------------------------------------------- helpers

    private fun jde(ms: Long) = SkyMath.julianDay(ms) + SkyMath.DELTA_T_SEC / 86_400.0

    /** Angle between two directions (deg), any coordinate pair of the same kind. */
    internal fun separation(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val a = Math.toRadians(dec1)
        val b = Math.toRadians(dec2)
        val d = Math.toRadians(ra1 - ra2)
        // The haversine form keeps small separations exact.
        val h = sin((b - a) / 2).pow(2) + cos(a) * cos(b) * sin(d / 2).pow(2)
        return Math.toDegrees(2 * asin(sqrt(h.coerceIn(0.0, 1.0))))
    }

    /** Bisection on [f] for its sign change between [a] and [b] (ms), to [tolMs]. */
    private inline fun bisect(aIn: Long, bIn: Long, tolMs: Long = 20_000L, f: (Long) -> Double): Long {
        var a = aIn
        var b = bIn
        val fa = f(a)
        while (b - a > tolMs) {
            val m = (a + b) / 2
            if ((f(m) > 0) == (fa > 0)) a = m else b = m
        }
        return (a + b) / 2
    }

    /** The minimum of [f] between [a] and [b] (ms), golden section, to [tolMs]. */
    private inline fun minimize(aIn: Long, bIn: Long, tolMs: Long = 30_000L, f: (Long) -> Double): Long {
        var a = aIn.toDouble()
        var b = bIn.toDouble()
        val g = (sqrt(5.0) - 1) / 2
        var c = b - g * (b - a)
        var d = a + g * (b - a)
        var fc = f(c.toLong())
        var fd = f(d.toLong())
        while (b - a > tolMs) {
            if (fc < fd) {
                b = d; d = c; fd = fc
                c = b - g * (b - a); fc = f(c.toLong())
            } else {
                a = c; c = d; fc = fd
                d = a + g * (b - a); fd = f(d.toLong())
            }
        }
        return ((a + b) / 2).toLong()
    }

    // ------------------------------------------------------------------------------ Moon

    private fun elongation(ms: Long) = SkyMath.elongationDeg(jde(ms))

    /** New moons (0), first quarters (1), full moons (2), last quarters (3) in [fromMs, toMs). */
    internal fun moonPhases(fromMs: Long, toMs: Long): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>()
        val step = 6 * HOUR
        var t = fromMs
        var e0 = elongation(t)
        while (t < toMs) {
            val t1 = t + step
            val e1 = elongation(t1)
            val next = (floor(e0 / 90.0) + 1) * 90.0
            val e1u = if (e1 < e0) e1 + 360.0 else e1
            if (e1u >= next) {
                val target = next % 360.0
                val at = bisect(t, t1) { SkyMath.wrap180(elongation(it) - target) }
                if (at in fromMs until toMs) out += at to ((next / 90.0).roundToInt() % 4)
            }
            t = t1
            e0 = e1
        }
        return out
    }

    private fun moonEvents(fromMs: Long, toMs: Long, zone: ZoneId): List<SkyEvent> =
        moonPhases(fromMs, toMs).mapNotNull { (at, phase) ->
            when (phase) {
                0 -> SkyEvent(
                    SkyEvent.Kind.NEW_MOON, "New moon", at, rating = SkyEvent.Rating.INFO,
                    summary = "The darkest nights of the month: around now the Moon is out of the way all night.",
                    details = listOf("New moon at ${hm(at, zone)} on ${weekdayDayMonth(at, zone)}. For about four nights " +
                        "either side the Moon stays below the horizon through most of the dark hours."),
                )
                2 -> {
                    val km = SkyMath.moonEcliptic(jde(at)).distanceKm
                    val supermoon = km < 362_000
                    SkyEvent(
                        SkyEvent.Kind.FULL_MOON, if (supermoon) "Full moon (a supermoon)" else "Full moon", at,
                        rating = SkyEvent.Rating.INFO,
                        summary = "Too bright for the Milky Way; good for moonlit landscapes and the Moon itself.",
                        details = listOf("Full moon at ${hm(at, zone)} on ${weekdayDayMonth(at, zone)}, " +
                            "${(km / 1000).roundToInt()} 000 km away" + if (supermoon) ", one of the year's closest." else "."),
                        lookAtMs = at,
                    )
                }
                else -> null
            }
        }

    // ----------------------------------------------------------------------- meteor showers

    /** The Sun's geometric longitude on the J2000 ecliptic (deg): what shower peaks are given in. */
    internal fun solarLongitudeJ2000(ms: Long): Double {
        val j = jde(ms)
        val t = (j - J2000) / 36525.0
        val apparent = SkyMath.sunEcliptic(j).lonDeg
        // Undo aberration and nutation (Meeus 25.8), then the precession since J2000.
        val omega = Math.toRadians(125.04 - 1934.136 * t)
        val geometric = apparent + 0.00569 + 0.00478 * sin(omega)
        val precession = (5029.0966 * t + 1.11113 * t * t) / 3600.0
        return SkyMath.norm360(geometric - precession)
    }

    /** When the Sun reaches J2000 longitude [lon], starting from a guess within a few weeks. */
    internal fun whenSolarLongitude(lon: Double, guessMs: Long): Long {
        var t = guessMs
        repeat(8) {
            val d = SkyMath.wrap180(lon - solarLongitudeJ2000(t))
            t += (d / 0.98565 * DAY).toLong()
        }
        return t
    }

    /** Naked-eye limiting magnitude for a zenith brightness (Schaefer's relation). */
    internal fun limitingMagnitude(sqm: Double): Double = 7.93 - 5 * log10(10.0.pow(4.316 - sqm / 5) + 1)

    private fun meteorShowers(fromMs: Long, toMs: Long, loc: GeoLocation, zone: ZoneId, skySqm: Double?): List<SkyEvent> {
        val out = ArrayList<SkyEvent>()
        val startYear = Instant.ofEpochMilli(fromMs).atZone(zone).year - 1
        val endYear = Instant.ofEpochMilli(toMs).atZone(zone).year
        for (year in startYear..endYear) {
            // The March equinox, then the mean motion to the shower's longitude.
            val equinox = LocalDate.of(year, 3, 20).atStartOfDay(zone).toInstant().toEpochMilli()
            for (s in SHOWERS) {
                val guess = equinox + (s.peakSolarLon / 0.98565 * DAY).toLong()
                val peak = whenSolarLongitude(s.peakSolarLon, guess)
                if (peak < fromMs - DAY || peak >= toMs) continue
                out += showerEvent(s, peak, loc, zone, skySqm)
            }
        }
        return out
    }

    /** One peak night: the radiant's height and the Moon hour by hour, the best moment, the verdict. */
    internal fun showerEvent(s: Shower, peakMs: Long, loc: GeoLocation, zone: ZoneId, skySqm: Double?): SkyEvent {
        // The night whose dark hours lie nearest the peak: the evening date of (peak - 12 h).
        val evening = Instant.ofEpochMilli(peakMs - 12 * HOUR).atZone(zone).toLocalDate()
        val noon = evening.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val illum = SkyMath.moonIlluminated(noon + 12 * HOUR)
        var bestRate = 0.0
        var bestDarkRate = 0.0
        var bestAt = -1L
        var bestAlt = 0.0
        var bestAz = 0.0
        var moonAtBest = 0.0
        var maxRadiant = -90.0
        var t = noon
        while (t <= noon + DAY) {
            val sunAlt = SkyMath.sun(t, loc).altDeg
            if (sunAlt < -12) {
                val rad = SkyMath.j2000ToHorizontal(s.raDeg, s.decDeg, t, loc)
                maxRadiant = maxOf(maxRadiant, rad.altDeg)
                if (rad.altDeg > 10) {
                    val moonAlt = SkyMath.moon(t, loc).altDeg
                    // A bright Moon above the horizon costs up to ~1.8 magnitudes of faint meteors.
                    val lmLoss = if (moonAlt > 0) 1.8 * illum * (0.4 + 0.6 * sin(Math.toRadians(moonAlt))) else 0.0
                    val dark = s.zhr * sin(Math.toRadians(rad.altDeg))
                    val rate = dark * s.r.pow(-lmLoss)
                    if (rate > bestRate) {
                        bestRate = rate
                        bestDarkRate = dark
                        bestAt = t
                        bestAlt = rad.altDeg
                        bestAz = rad.azDeg
                        moonAtBest = moonAlt
                    }
                }
            }
            t += 10 * 60_000L
        }
        val title = s.name
        if (bestAt < 0) {
            return SkyEvent(
                SkyEvent.Kind.METEORS, title, peakMs, rating = SkyEvent.Rating.POOR,
                summary = if (maxRadiant < 10) "Peak ${nightOf(evening)}: its radiant stays too low from here."
                else "Peak ${nightOf(evening)}: the radiant is up only in daylight or twilight.",
                details = listOf("Active ${s.active}. Radiant in ${s.constellation}.", s.note),
            )
        }
        val rating = when {
            bestRate >= 50 -> SkyEvent.Rating.GREAT
            bestRate >= 20 -> SkyEvent.Rating.GOOD
            bestRate >= 8 -> SkyEvent.Rating.FAIR
            else -> SkyEvent.Rating.POOR
        }
        val moonText = when {
            illum < 0.15 -> "no Moon to speak of"
            moonAtBest <= 0 -> "the Moon (${(illum * 100).roundToInt()}%) is down by then"
            else -> "the Moon (${(illum * 100).roundToInt()}%) is up and hides the faint ones"
        }
        val summary = "Peak ${nightOf(evening)}: up to ~${bestRate.roundToInt()} an hour around ${hm(bestAt, zone)} " +
            "under a dark sky; $moonText."
        val details = ArrayList<String>()
        details += "Active ${s.active}; the peak is due at ${hm(peakMs, zone)} on ${weekdayDayMonth(peakMs, zone)}."
        details += "Radiant in ${s.constellation}, ${bestAlt.roundToInt()}° up in the ${compass(bestAz)} at " +
            "${hm(bestAt, zone)}. Meteors appear all over the sky: look about halfway up, away from the radiant."
        if (skySqm != null) {
            val lm = limitingMagnitude(skySqm)
            val yours = bestRate * s.r.pow(lm - 6.5).coerceAtMost(1.0)
            details += "From your sky (limiting magnitude about ${"%.1f".format(Locale.US, lm)}) expect about " +
                "${yours.roundToInt().coerceAtLeast(1)} an hour; a dark site sees up to ${bestRate.roundToInt()}."
        }
        if (bestDarkRate > bestRate * 1.3) {
            details += "Without the Moon it would be up to ${bestDarkRate.roundToInt()} an hour."
        }
        details += s.note
        details += "For photos: the widest lens, frames as long as the stars allow, and many of them - a few " +
            "will catch a meteor. METEOR mode keeps shooting and flags the frames with streaks."
        return SkyEvent(SkyEvent.Kind.METEORS, title, peakMs, rating = rating, summary = summary,
            details = details, lookAtMs = bestAt)
    }

    // -------------------------------------------------------------------- Milky Way nights

    /**
     * A quick look before the full plan: is the core near its working height here while the Sun
     * is near astronomical darkness, in half-hour steps from late afternoon to morning? Most of
     * the year's nights fail this in a few dozen position computations instead of a planner's
     * thousands.
     */
    /** The first half hour of the night with the core up in the dark (null: none). */
    private fun coreUpInDark(date: LocalDate, loc: GeoLocation, zone: ZoneId): Long? {
        val start = date.atTime(16, 0).atZone(zone).toInstant().toEpochMilli()
        val coreMin = NightPlanner.coreMinAltDeg(loc.latDeg) - 1.0
        for (h in 0..32) {
            val t = start + h * HOUR / 2
            if (SkyMath.sun(t, loc).altDeg < -16.5 && SkyMath.galacticCenter(t, loc).altDeg > coreMin) return t
        }
        return null
    }

    private fun milkyWayNights(fromMs: Long, days: Int, loc: GeoLocation, zone: ZoneId): List<SkyEvent> {
        val first = Instant.ofEpochMilli(fromMs - 12 * HOUR).atZone(zone).toLocalDate()
        class Night(val date: LocalDate, val window: TimeRange, val peakAlt: Double, val az: Double)
        val nights = ArrayList<Night?>()
        // Nights with the core up in the dark at all (moonlit or not): the season.
        val upAt = arrayOfNulls<Long>(days)
        for (d in 0 until days) {
            val date = first.plusDays(d.toLong())
            upAt[d] = coreUpInDark(date, loc, zone)
            if (upAt[d] == null) {
                nights += null
                continue
            }
            val plan = runCatching { NightPlanner.plan(date, zone, loc) }.getOrNull()
            val w = plan?.bestCoreWindow
            // Far north (the Netherlands, England) the windows are short at best: still worth a note.
            if (plan == null || w == null || w.durationMs < MIN_WINDOW_MS || w.endMs < fromMs) {
                nights += null
                continue
            }
            // The core's highest point in the window, and where it stands then.
            val peakAt = listOfNotNull(w.startMs, w.endMs, plan.coreTransitMs?.takeIf { it in w.startMs..w.endMs })
                .maxByOrNull { SkyMath.galacticCenter(it, loc).altDeg }!!
            val h = SkyMath.galacticCenter(peakAt, loc)
            nights += Night(date, w, h.altDeg, h.azDeg)
        }
        val out = ArrayList<SkyEvent>()
        var i = 0
        while (i < nights.size) {
            if (nights[i] == null) { i++; continue }
            var j = i
            while (j + 1 < nights.size && nights[j + 1] != null) j++
            val run = nights.subList(i, j + 1).filterNotNull()
            val best = run.maxByOrNull { it.window.durationMs }!!
            val hours = best.window.durationMs / HOUR.toDouble()
            val rating = when {
                hours >= 3 -> SkyEvent.Rating.GREAT
                hours >= 1.5 -> SkyEvent.Rating.GOOD
                hours >= 0.75 -> SkyEvent.Rating.FAIR
                else -> SkyEvent.Rating.POOR
            }
            val span = if (run.size == 1) "The night of ${nightOf(run.first().date)}"
            else "${run.size} nights, ${run.first().date.format(DateTimeFormatter.ofPattern("d MMM", Locale.US))} – " +
                run.last().date.plusDays(1).format(DateTimeFormatter.ofPattern("d MMM", Locale.US))
            val bestText = "${hm(best.window.startMs, zone)}–${hm(best.window.endMs, zone)}"
            out += SkyEvent(
                SkyEvent.Kind.MILKY_WAY, "Milky Way nights", run.first().window.startMs, run.last().window.endMs,
                rating = rating,
                summary = "$span with the core up in a dark, moonless sky; best ${nightOf(best.date)}, $bestText.",
                details = listOfNotNull(
                    "Best night: ${nightOf(best.date)}, $bestText (" +
                        (if (hours >= 1) "${"%.1f".format(Locale.US, hours)} h" else "${(hours * 60).roundToInt()} min") +
                        "), the core up to ${best.peakAlt.roundToInt()}° in the ${compass(best.az)}.",
                    "On each of these nights the core stands ${NightPlanner.coreMinAltDeg(loc.latDeg).roundToInt()}° or " +
                        "more up in full darkness with no Moon, for at least ${MIN_WINDOW_MS / 60_000L} minutes.",
                    if (best.peakAlt < 10) "The core stays low from here: find an open horizon to the ${compass(best.az)}, " +
                        "away from towns in that direction." else null,
                ),
                lookAtMs = best.window.startMs + (best.window.durationMs / 2),
            )
            i = j + 1
        }
        // The season: the first night with the core in the dark after a month without it, and
        // the last before such a month (whatever the Moon does on those nights).
        fun quiet(range: IntRange) = range.all { it !in upAt.indices || upAt[it] == null }
        for (d in upAt.indices) {
            val t = upAt[d] ?: continue
            val h = SkyMath.galacticCenter(t, loc)
            if (d >= 30 && quiet(d - 30 until d)) {
                out += SkyEvent(
                    SkyEvent.Kind.MILKY_WAY, "Milky Way season begins", t, rating = SkyEvent.Rating.INFO,
                    summary = "The core is back in the dark sky: at ${hm(t, zone)}, ${h.altDeg.roundToInt()}° up in " +
                        "the ${compass(h.azDeg)}.",
                    details = listOf("From now on the moonless nights around each new moon are the ones for the " +
                        "Milky Way's core (see the Milky Way nights)."),
                    lookAtMs = t,
                )
            }
            if (d + 30 < upAt.size && quiet(d + 1..d + 30)) {
                out += SkyEvent(
                    SkyEvent.Kind.MILKY_WAY, "Milky Way season ends", t, rating = SkyEvent.Rating.INFO,
                    summary = "The last night with the core in the dark sky for a while: at ${hm(t, zone)}, " +
                        "${h.altDeg.roundToInt()}° up in the ${compass(h.azDeg)}.",
                    details = listOf(
                        if (loc.latDeg > 48 && Instant.ofEpochMilli(t).atZone(zone).monthValue in 5..6)
                            "This far north the summer nights never get fully dark; the core returns to a dark sky in the late summer."
                        else "The core spends the coming weeks in daylight; it returns to the dark sky later in the year.",
                    ),
                    lookAtMs = t,
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------------------- eclipses

    private const val EARTH_RADIUS_KM = 6378.14
    private const val MOON_RADIUS_KM = 1737.4
    private const val AU_KM = 149_597_870.7

    /** Geocentric Moon and the point opposite the Sun: (separation, shadow radii and Moon radius), deg. */
    private class Shadow(val sep: Double, val umbra: Double, val penumbra: Double, val moon: Double)

    private fun shadowAt(ms: Long): Shadow {
        val j = jde(ms)
        val moon = SkyMath.moonEquatorial(j)
        val sun = SkyMath.sunEquatorial(j)
        val anti = SkyMath.norm360(sun.raDeg + 180.0)
        val sep = separation(moon.raDeg, moon.decDeg, anti, -sun.decDeg)
        val piMoon = Math.toDegrees(asin(EARTH_RADIUS_KM / moon.distanceKm))
        val sMoon = Math.toDegrees(asin(MOON_RADIUS_KM / moon.distanceKm))
        val rAu = sun.distanceKm / AU_KM
        val piSun = 8.794 / 3600.0 / rAu
        val sSun = 959.63 / 3600.0 / rAu
        // Chauvenet's 1/50 enlargement of the Earth's shadow by its atmosphere.
        val umbra = 1.02 * (0.998340 * piMoon - sSun + piSun)
        val penumbra = 1.02 * (0.998340 * piMoon + sSun + piSun)
        return Shadow(sep, umbra, penumbra, sMoon)
    }

    /** Greatest eclipse, the umbral and penumbral magnitudes and the contacts, for one full moon. */
    internal class LunarEclipse(
        val greatestMs: Long,
        val umbral: Double,
        val penumbral: Double,
        /** Partial phase (umbra) start/end, totality start/end; null when it does not happen. */
        val partialStartMs: Long?,
        val partialEndMs: Long?,
        val totalStartMs: Long?,
        val totalEndMs: Long?,
        val penumbralStartMs: Long,
        val penumbralEndMs: Long,
    )

    internal fun lunarEclipseNear(fullMoonMs: Long): LunarEclipse? {
        val a = fullMoonMs - 6 * HOUR
        val b = fullMoonMs + 6 * HOUR
        val g = minimize(a, b) { shadowAt(it).sep }
        val s = shadowAt(g)
        val umbral = (s.umbra + s.moon - s.sep) / (2 * s.moon)
        val penumbral = (s.penumbra + s.moon - s.sep) / (2 * s.moon)
        if (penumbral <= 0) return null
        fun contact(edge: (Shadow) -> Double, before: Boolean): Long? {
            val f = { t: Long -> val x = shadowAt(t); x.sep - edge(x) }
            // Inside at greatest, outside at the window's end: the contact lies between.
            if (f(g) > 0 || f(if (before) a else b) <= 0) return null
            return if (before) bisect(a, g) { f(it) } else bisect(g, b) { -f(it) }
        }
        return LunarEclipse(
            greatestMs = g, umbral = umbral, penumbral = penumbral,
            partialStartMs = contact({ it.umbra + it.moon }, true),
            partialEndMs = contact({ it.umbra + it.moon }, false),
            totalStartMs = contact({ it.umbra - it.moon }, true),
            totalEndMs = contact({ it.umbra - it.moon }, false),
            penumbralStartMs = contact({ it.penumbra + it.moon }, true) ?: g,
            penumbralEndMs = contact({ it.penumbra + it.moon }, false) ?: g,
        )
    }

    private fun lunarEclipses(fromMs: Long, toMs: Long, loc: GeoLocation, zone: ZoneId): List<SkyEvent> =
        moonPhases(fromMs - DAY, toMs).filter { it.second == 2 }.mapNotNull { (full, _) ->
            val e = lunarEclipseNear(full) ?: return@mapNotNull null
            if (e.greatestMs !in fromMs until toMs) return@mapNotNull null
            val type = when {
                e.umbral >= 1 -> "Total lunar eclipse"
                e.umbral > 0 -> "Partial lunar eclipse"
                else -> "Penumbral lunar eclipse"
            }
            // Seen from here: the Moon above the horizon at some point of the main phase.
            val phaseStart = e.partialStartMs ?: e.penumbralStartMs
            val phaseEnd = e.partialEndMs ?: e.penumbralEndMs
            var upAt: Long? = null
            var t = phaseStart
            while (t <= phaseEnd) {
                if (SkyMath.moon(t, loc).altDeg > 2 && SkyMath.sun(t, loc).altDeg < -2) { upAt = upAt ?: t }
                t += 5 * 60_000L
            }
            val greatestUp = SkyMath.moon(e.greatestMs, loc).altDeg > 2 && SkyMath.sun(e.greatestMs, loc).altDeg < -2
            val details = ArrayList<String>()
            if (e.totalStartMs != null && e.totalEndMs != null) {
                details += "Totality ${hm(e.totalStartMs, zone)}–${hm(e.totalEndMs, zone)} " +
                    "(${((e.totalEndMs - e.totalStartMs) / 60_000L)} min): the Moon turns copper-red."
            }
            if (e.partialStartMs != null && e.partialEndMs != null) {
                details += "The Earth's shadow takes its first bite at ${hm(e.partialStartMs, zone)} and leaves at ${hm(e.partialEndMs, zone)}."
            }
            details += "Greatest eclipse ${hm(e.greatestMs, zone)}, " + if (e.umbral > 0) {
                "magnitude ${"%.2f".format(Locale.US, e.umbral)}."
            } else {
                "penumbral magnitude ${"%.2f".format(Locale.US, e.penumbral)}: only a subtle shading of the Moon."
            }
            val visible = upAt != null
            if (!visible) details += "The Moon is below the horizon here (or it is daylight) during the eclipse."
            else if (!greatestUp) details += "From here the Moon is up for part of it only."
            val rating = when {
                !visible -> SkyEvent.Rating.INFO
                e.umbral >= 1 && greatestUp -> SkyEvent.Rating.GREAT
                e.umbral >= 1 || e.umbral > 0.3 -> SkyEvent.Rating.GOOD
                e.umbral > 0 -> SkyEvent.Rating.FAIR
                else -> SkyEvent.Rating.POOR
            }
            SkyEvent(
                SkyEvent.Kind.LUNAR_ECLIPSE, type, e.greatestMs, e.penumbralEndMs, rating,
                summary = if (visible) "${weekdayDayMonth(e.greatestMs, zone)}, greatest at ${hm(e.greatestMs, zone)}" +
                    (if (greatestUp) "" else " (the Moon is up for part of it)") + "."
                else "${weekdayDayMonth(e.greatestMs, zone)}: not visible from here.",
                details = details,
                lookAtMs = if (visible) (if (greatestUp) e.greatestMs else upAt) else null,
            )
        }

    /** The Sun's and the Moon's discs as seen from [loc] at [ms]: separation and radii (deg). */
    private class Discs(val sep: Double, val sun: Double, val moon: Double, val sunAlt: Double)

    private fun discsAt(ms: Long, loc: GeoLocation): Discs {
        val j = jde(ms)
        val jd = SkyMath.julianDay(ms)
        val sun = SkyMath.sunEquatorial(j)
        val moon = SkyMath.moonEquatorial(j)
        val gast = SkyMath.greenwichApparentSiderealDeg(jd)
        val haMoon = gast + loc.lonDeg - moon.raDeg
        val topo = SkyMath.topocentric(haMoon, moon.decDeg, moon.distanceKm, loc.latDeg)
        // Back from hour angle to right ascension for the topocentric Moon.
        val moonRa = gast + loc.lonDeg - topo.haDeg
        val sep = separation(moonRa, topo.decDeg, sun.raDeg, sun.decDeg)
        val sSun = 959.63 / 3600.0 / (sun.distanceKm / AU_KM)
        val sMoon = Math.toDegrees(asin(MOON_RADIUS_KM / topo.distanceKm))
        val sunAlt = SkyMath.sun(ms, loc).altDeg
        return Discs(sep, sSun, sMoon, sunAlt)
    }

    /** How much of the Sun's disc the Moon's covers (0..1), for discs of radii [r] (Sun) and [rm] (Moon). */
    internal fun obscuration(sep: Double, r: Double, rm: Double): Double {
        if (sep >= r + rm) return 0.0
        if (sep <= abs(rm - r)) return if (rm >= r) 1.0 else (rm * rm) / (r * r)
        val a = r * r * acos(((sep * sep + r * r - rm * rm) / (2 * sep * r)).coerceIn(-1.0, 1.0))
        val b = rm * rm * acos(((sep * sep + rm * rm - r * r) / (2 * sep * rm)).coerceIn(-1.0, 1.0))
        val c = 0.5 * sqrt(((-sep + r + rm) * (sep + r - rm) * (sep - r + rm) * (sep + r + rm)).coerceAtLeast(0.0))
        return ((a + b - c) / (Math.PI * r * r)).coerceIn(0.0, 1.0)
    }

    /** The eclipse seen from [loc] around the new moon at [newMoonMs]: the deepest moment with the Sun up. */
    internal class LocalSolarEclipse(
        val maxMs: Long, val magnitude: Double, val obscuration: Double, val total: Boolean, val annular: Boolean,
        val startMs: Long, val endMs: Long, val sunAltAtMax: Double,
    )

    internal fun localSolarEclipse(newMoonMs: Long, loc: GeoLocation): LocalSolarEclipse? {
        var bestT = -1L
        var bestMag = 0.0
        var start = -1L
        var end = -1L
        var t = newMoonMs - 5 * HOUR
        while (t <= newMoonMs + 5 * HOUR) {
            val d = discsAt(t, loc)
            val overlap = d.sep < d.sun + d.moon
            if (overlap && d.sunAlt > -0.8) {
                if (start < 0) start = t
                end = t
                val mag = (d.sun + d.moon - d.sep) / (2 * d.sun)
                if (mag > bestMag) { bestMag = mag; bestT = t }
            }
            t += 2 * 60_000L
        }
        if (bestT < 0) return null
        val refined = minimize(bestT - 3 * 60_000L, bestT + 3 * 60_000L, 5_000L) { discsAt(it, loc).sep }
        val d = discsAt(refined, loc)
        val mag = (d.sun + d.moon - d.sep) / (2 * d.sun)
        return LocalSolarEclipse(
            maxMs = refined, magnitude = mag, obscuration = obscuration(d.sep, d.sun, d.moon),
            total = d.moon > d.sun && d.sep < d.moon - d.sun, annular = d.moon < d.sun && d.sep < d.sun - d.moon,
            startMs = start, endMs = end, sunAltAtMax = d.sunAlt,
        )
    }

    /** Does the Moon's shadow touch the Earth at all at this new moon (an eclipse somewhere)? */
    private fun eclipseSomewhere(newMoonMs: Long): Boolean {
        val j = jde(newMoonMs)
        val moon = SkyMath.moonEquatorial(j)
        val sun = SkyMath.sunEquatorial(j)
        val g = minimize(newMoonMs - 6 * HOUR, newMoonMs + 6 * HOUR) {
            val jj = jde(it)
            val m = SkyMath.moonEquatorial(jj)
            val s = SkyMath.sunEquatorial(jj)
            separation(m.raDeg, m.decDeg, s.raDeg, s.decDeg)
        }
        val jj = jde(g)
        val m = SkyMath.moonEquatorial(jj)
        val s = SkyMath.sunEquatorial(jj)
        val sep = separation(m.raDeg, m.decDeg, s.raDeg, s.decDeg)
        val piMoon = Math.toDegrees(asin(EARTH_RADIUS_KM / moon.distanceKm))
        val sMoon = Math.toDegrees(asin(MOON_RADIUS_KM / moon.distanceKm))
        val sSun = 959.63 / 3600.0 / (sun.distanceKm / AU_KM)
        return sep < sSun + sMoon + piMoon
    }

    private fun solarEclipses(fromMs: Long, toMs: Long, loc: GeoLocation, zone: ZoneId): List<SkyEvent> =
        moonPhases(fromMs - DAY, toMs).filter { it.second == 0 }.mapNotNull { (newMoon, _) ->
            if (newMoon !in fromMs - 6 * HOUR until toMs) return@mapNotNull null
            if (!eclipseSomewhere(newMoon)) return@mapNotNull null
            val local = localSolarEclipse(newMoon, loc)
            if (local == null) {
                return@mapNotNull SkyEvent(
                    SkyEvent.Kind.SOLAR_ECLIPSE, "Solar eclipse", newMoon, rating = SkyEvent.Rating.INFO,
                    summary = "${weekdayDayMonth(newMoon, zone)}: not visible from here.",
                )
            }
            val pct = (local.obscuration * 100).roundToInt()
            val type = when {
                local.total -> "Total solar eclipse"
                local.annular -> "Annular solar eclipse"
                else -> "Partial solar eclipse"
            }
            val rating = when {
                local.total || local.annular -> SkyEvent.Rating.GREAT
                local.obscuration >= 0.5 -> SkyEvent.Rating.GOOD
                local.obscuration >= 0.15 -> SkyEvent.Rating.FAIR
                else -> SkyEvent.Rating.POOR
            }
            SkyEvent(
                SkyEvent.Kind.SOLAR_ECLIPSE, type, local.maxMs, local.endMs, rating,
                summary = "${weekdayDayMonth(local.maxMs, zone)}: " +
                    (if (local.total) "the Sun fully covered" else "$pct% of the Sun covered") +
                    " at ${hm(local.maxMs, zone)}, the Sun ${local.sunAltAtMax.roundToInt()}° up.",
                details = listOf(
                    "From ${hm(local.startMs, zone)} to ${hm(local.endMs, zone)}" +
                        (if (local.sunAltAtMax < 5) "; the Sun is low, so find a clear horizon." else "."),
                    "Never look at the Sun without certified eclipse glasses, and never point the phone at it " +
                        "without a solar filter: StarCam's long exposures are made for the night.",
                ),
                lookAtMs = local.maxMs,
            )
        }

    // ----------------------------------------------------------------------------- planets

    /** Angle of a planet from the Sun (deg), both geocentric J2000; positive east of the Sun. */
    private fun elongationOf(name: String, ms: Long): Pair<Double, Boolean> {
        val p = Planets.places(ms).first { it.name == name }
        val (sunRa, sunDec) = Planets.sun(ms)
        val sep = separation(p.raDeg, p.decDeg, sunRa, sunDec)
        return sep to (SkyMath.wrap180(p.raDeg - sunRa) > 0)
    }

    private fun planetEvents(fromMs: Long, toMs: Long, loc: GeoLocation, zone: ZoneId): List<SkyEvent> {
        val out = ArrayList<SkyEvent>()
        for (name in listOf("Mercury", "Venus", "Mars", "Jupiter", "Saturn")) {
            val inner = name == "Mercury" || name == "Venus"
            var tPrev = fromMs - DAY
            var ePrev = elongationOf(name, tPrev).first
            var t = fromMs
            var e = elongationOf(name, t).first
            while (t < toMs) {
                val tNext = t + DAY
                val eNext = elongationOf(name, tNext).first
                if (e > ePrev && e >= eNext) {
                    // A maximum of the elongation: opposition, or an inner planet's greatest elongation.
                    val at = minimize(tPrev, tNext, 10 * 60_000L) { -elongationOf(name, it).first }
                    val (el, east) = elongationOf(name, at)
                    if (!inner && el > 170) out += opposition(name, at, loc, zone)
                    else if (inner) greatestElongation(name, at, el, east, loc, zone)?.let { out += it }
                }
                tPrev = t; ePrev = e
                t = tNext; e = eNext
            }
        }
        return out.filter { it.atMs in fromMs until toMs }
    }

    private fun opposition(name: String, at: Long, loc: GeoLocation, zone: ZoneId): SkyEvent {
        // Highest around local midnight: where and how high.
        val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val midnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        var bestT = midnight
        var bestAlt = -90.0
        var t = midnight - 6 * HOUR
        val p = Planets.places(at).first { it.name == name }
        while (t <= midnight + 6 * HOUR) {
            val h = SkyMath.j2000ToHorizontal(p.raDeg, p.decDeg, t, loc)
            if (h.altDeg > bestAlt) { bestAlt = h.altDeg; bestT = t }
            t += 15 * 60_000L
        }
        val h = SkyMath.j2000ToHorizontal(p.raDeg, p.decDeg, bestT, loc)
        val rating = when {
            bestAlt < 10 -> SkyEvent.Rating.POOR
            name == "Mars" -> SkyEvent.Rating.GREAT
            else -> SkyEvent.Rating.GOOD
        }
        return SkyEvent(
            SkyEvent.Kind.PLANET, "$name at opposition", at, rating = rating,
            summary = "Closest and brightest for the year, up all night: highest around ${hm(bestT, zone)}, " +
                "${bestAlt.roundToInt()}° in the ${compass(h.azDeg)}.",
            details = listOf(
                "Opposite the Sun on ${weekdayDayMonth(at, zone)}: it rises at sunset and sets at sunrise.",
                "A bright point in any wide-field photo; a telephoto or the 1x lens shows " +
                    when (name) { "Jupiter" -> "its four largest moons in a line."; "Saturn" -> "it as a slightly oval dot (the rings)."; else -> "its colour." },
            ),
            lookAtMs = bestT,
        )
    }

    private fun greatestElongation(name: String, at: Long, el: Double, east: Boolean, loc: GeoLocation, zone: ZoneId): SkyEvent? {
        // How high it stands at civil twilight on that day: what decides whether it is worth it.
        val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val plan = runCatching { NightPlanner.plan(if (east) date else date.minusDays(1), zone, loc) }.getOrNull() ?: return null
        val twilight = (if (east) plan.civilDuskMs else plan.civilDawnMs) ?: return null
        val p = Planets.places(twilight).first { it.name == name }
        val h = SkyMath.j2000ToHorizontal(p.raDeg, p.decDeg, twilight, loc)
        if (name == "Mercury" && h.altDeg < 6) return null
        val rating = when {
            h.altDeg >= 20 -> SkyEvent.Rating.GOOD
            h.altDeg >= 8 -> SkyEvent.Rating.FAIR
            else -> SkyEvent.Rating.POOR
        }
        val sky = if (east) "evening" else "morning"
        return SkyEvent(
            SkyEvent.Kind.PLANET, "$name at its best in the $sky sky", at, rating = rating,
            summary = "${el.roundToInt()}° from the Sun: ${h.altDeg.roundToInt()}° up in the ${compass(h.azDeg)} at " +
                "${hm(twilight, zone)}, ${if (east) "after sunset" else "before sunrise"}.",
            details = listOf(
                if (name == "Venus") "The brightest thing in the sky after the Sun and the Moon: a beacon over a twilight landscape."
                else "Mercury is only ever seen low in twilight; this is one of its better showings from here.",
            ),
            lookAtMs = twilight,
        )
    }

    // ------------------------------------------------------------------------- conjunctions

    private fun moonPlace(ms: Long): Pair<Double, Double> {
        val m = SkyMath.moonEquatorial(jde(ms))
        return m.raDeg to m.decDeg
    }

    /** Planet place precessed to the date, to compare with the Moon's (which is of date). */
    private fun planetOfDate(name: String, ms: Long): Pair<Double, Double> {
        val p = Planets.places(ms).first { it.name == name }
        val q = SkyMath.precessFromJ2000(p.raDeg, p.decDeg, jde(ms))
        return q.raDeg to q.decDeg
    }

    private fun conjunctions(fromMs: Long, toMs: Long, loc: GeoLocation, zone: ZoneId): List<SkyEvent> {
        val out = ArrayList<SkyEvent>()
        val bright = listOf("Venus", "Mars", "Jupiter", "Saturn")
        // The Moon passes each planet once a month: the close ones, in a dark enough sky.
        for (name in bright) {
            val f = { ms: Long ->
                val (mr, md) = moonPlace(ms)
                val (pr, pd) = planetOfDate(name, ms)
                separation(mr, md, pr, pd)
            }
            var t = fromMs
            var prev = f(t - 3 * HOUR)
            var cur = f(t)
            while (t < toMs) {
                val next = f(t + 3 * HOUR)
                if (cur <= prev && cur < next && cur < 3.0) {
                    val at = minimize(t - 3 * HOUR, t + 3 * HOUR) { f(it) }
                    val sep = f(at)
                    // Monthly for every planet: only the close ones are worth a note.
                    if (sep < 2.0) visiblePair("The Moon and $name", at, sep, name, true, loc, zone)?.let { out += it }
                }
                prev = cur
                cur = next
                t += 3 * HOUR
            }
        }
        // Two planets close together.
        val pairs = listOf("Mercury", "Venus", "Mars", "Jupiter", "Saturn").let { l ->
            l.indices.flatMap { i -> (i + 1 until l.size).map { j -> l[i] to l[j] } }
        }
        for ((a, b) in pairs) {
            val f = { ms: Long ->
                val ps = Planets.places(ms)
                val pa = ps.first { it.name == a }
                val pb = ps.first { it.name == b }
                separation(pa.raDeg, pa.decDeg, pb.raDeg, pb.decDeg)
            }
            var t = fromMs
            var prev = f(t - DAY)
            var cur = f(t)
            while (t < toMs) {
                val next = f(t + DAY)
                if (cur <= prev && cur < next && cur < 2.0) {
                    val at = minimize(t - DAY, t + DAY) { f(it) }
                    val sep = f(at)
                    if (sep < 1.5) visiblePair("$a and $b together", at, sep, b, false, loc, zone, a)?.let { out += it }
                }
                prev = cur
                cur = next
                t += DAY
            }
        }
        return out.filter { it.atMs in fromMs until toMs }
    }

    /**
     * A pairing worth looking at: the moment nearest the closest approach when both are at least 5°
     * up in a sky at least in civil twilight. Null when there is no such moment.
     */
    private fun visiblePair(
        title: String, at: Long, sep: Double, planet: String, withMoon: Boolean, loc: GeoLocation, zone: ZoneId,
        other: String? = null,
    ): SkyEvent? {
        var best: Long? = null
        var bestAlt = 0.0
        var bestAz = 0.0
        for (k in 0..48) {
            // Out from the closest approach, alternately later and earlier, every 15 minutes.
            val off = ((k + 1) / 2) * 15 * 60_000L * (if (k % 2 == 0) 1 else -1)
            val t = at + off
            if (SkyMath.sun(t, loc).altDeg > -6) continue
            val p = Planets.places(t).first { it.name == planet }
            val hp = SkyMath.j2000ToHorizontal(p.raDeg, p.decDeg, t, loc)
            val h2 = if (withMoon) SkyMath.moon(t, loc) else {
                val o = Planets.places(t).first { it.name == other }
                SkyMath.j2000ToHorizontal(o.raDeg, o.decDeg, t, loc)
            }
            if (hp.altDeg > 5 && h2.altDeg > 5) {
                best = t
                bestAlt = minOf(hp.altDeg, h2.altDeg)
                bestAz = hp.azDeg
                break
            }
        }
        val t = best ?: return null
        val rating = if (sep < 1.0) SkyEvent.Rating.GOOD else SkyEvent.Rating.FAIR
        val moonText = if (withMoon) " · Moon ${(SkyMath.moonIlluminated(t) * 100).roundToInt()}% lit" else ""
        return SkyEvent(
            SkyEvent.Kind.CONJUNCTION, title, at, rating = rating,
            summary = "${"%.1f".format(Locale.US, sep)}° apart; best seen ${weekdayDayMonth(t, zone)} at ${hm(t, zone)}, " +
                "${bestAlt.roundToInt()}° up in the ${compass(bestAz)}$moonText.",
            details = listOf("A pretty pairing for a landscape at twilight: the 1x lens frames both with room for the foreground."),
            lookAtMs = t,
        )
    }
}
