package com.wm.starcam.sky

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** A place on Earth: latitude north positive, longitude east positive, as GPS reports them. */
data class GeoLocation(val latDeg: Double, val lonDeg: Double)

/** Azimuth from true north through east (0..360), altitude above the horizon (deg). */
data class Horizontal(val azDeg: Double, val altDeg: Double)

/**
 * The Moon's phase, seen from the Earth's centre (where on Earth you stand changes it by under
 * 0.1%).
 *
 * [illuminated] is the lit fraction of the disk, 0 at new moon to 1 at full; [waxing] is true
 * from new to full. [ageDays] is the time since the last new moon. A lunation lasts 29.3 to 29.8
 * days (29.53 on average), so just before a new moon the age can run a little past 29.53.
 * [name] is one of the constants below. A principal phase (new, quarters, full) is an instant;
 * its name covers the 24 hours centred on it, the way a calendar gives it one day.
 */
data class MoonPhase(
    val illuminated: Double,
    val waxing: Boolean,
    val ageDays: Double,
    val name: String,
) {
    companion object {
        const val NEW_MOON = "New moon"
        const val WAXING_CRESCENT = "Waxing crescent"
        const val FIRST_QUARTER = "First quarter"
        const val WAXING_GIBBOUS = "Waxing gibbous"
        const val FULL_MOON = "Full moon"
        const val WANING_GIBBOUS = "Waning gibbous"
        const val LAST_QUARTER = "Last quarter"
        const val WANING_CRESCENT = "Waning crescent"
    }
}

/** Ecliptic longitude and latitude of date (deg) and distance from the Earth's centre (km). */
internal data class Ecliptic(val lonDeg: Double, val latDeg: Double, val distanceKm: Double)

/** Right ascension and declination (deg), and distance from the Earth's centre (km). */
internal data class Equatorial(
    val raDeg: Double,
    val decDeg: Double,
    /** Infinite for a star. */
    val distanceKm: Double = Double.POSITIVE_INFINITY,
)

/** Local hour angle (deg, west positive) and declination (deg) as seen from one place. */
internal data class HourAngleDec(val haDeg: Double, val decDeg: Double, val distanceKm: Double)

/** Nutation in longitude and in obliquity (deg). */
internal data class Nutation(val dPsiDeg: Double, val dEpsDeg: Double)

/** The Moon from one place: where it is, and its apparent radius (deg) for rise and set. */
internal data class MoonView(val position: Horizontal, val semidiameterDeg: Double)

/**
 * Positions of the Sun, the Moon and the galactic centre, computed on the device with the
 * low-precision methods of Jean Meeus, "Astronomical Algorithms" (2nd ed., 1998): the Sun from
 * ch. 25 (rated 0.01 deg; at worst 30" from JPL Horizons in 2026 spot checks), the Moon from
 * the complete ch. 47 series (within 4" in longitude and 40 km in distance of Horizons), the
 * illuminated fraction from ch. 48, sidereal time from ch. 12, precession from ch. 21 and an
 * abridged nutation from ch. 22. That is far more than a night planner needs: rise, set and
 * twilight times built on it land within a couple of seconds of Horizons, except where a body
 * only skims the horizon and the moment itself is ill-defined.
 *
 * Times are Unix milliseconds, taken as UT (UTC and UT1 differ by under a second). The
 * ephemerides run on dynamical time, TT = UT + [DELTA_T_SEC]. Pure Kotlin, no Android and no
 * network, so it works offline in the field and runs in plain JVM unit tests.
 */
object SkyMath {

    /**
     * ΔT = TT − UT in seconds: how far the ephemerides' uniform clock runs ahead of the Earth's
     * rotation. Held at 69.2 s, the value throughout 2020-2026 (it has moved by under half a
     * second since 2019). It only matters through the Moon's motion of 0.55 deg/h: an error of
     * 30 s, more than is likely within a decade either side, moves the Moon 0.005 deg.
     */
    internal const val DELTA_T_SEC = 69.2

    /** Sagittarius A*, the galactic centre: J2000.0 right ascension and declination (deg). */
    internal const val GALACTIC_CENTER_RA_J2000 = 266.41683
    internal const val GALACTIC_CENTER_DEC_J2000 = -29.00781

    private const val J2000 = 2451545.0
    private const val DAYS_PER_CENTURY = 36525.0
    private const val MS_PER_DAY = 86_400_000.0
    private const val JD_UNIX_EPOCH = 2440587.5
    private const val DELTA_T_DAYS = DELTA_T_SEC / 86_400.0
    private const val AU_KM = 149_597_870.7

    /** Earth's equatorial radius and polar/equatorial axis ratio (Meeus ch. 11, IAU 1976). */
    private const val EARTH_RADIUS_KM = 6378.14
    private const val EARTH_AXIS_RATIO = 0.99664719
    private const val MOON_RADIUS_KM = 1737.4

    /** The Moon's mean gain on the Sun: 360 deg per mean synodic month of 29.530589 days. */
    private const val SYNODIC_DEG_PER_DAY = 360.0 / 29.530588853

    /** Half-width (days) of the central difference that measures the elongation rate. */
    private const val RATE_STEP_DAYS = 1.0 / 24.0

    /**
     * Fixed-point steps from the mean-rate guess to the last new moon. The true elongation rate
     * (10.7..14.4 deg/day) stays within 20% of the mean, so each step cuts the error at least
     * fivefold and 8 steps take the worst first guess (~0.7 day) under a second.
     */
    private const val NEW_MOON_STEPS = 8

    /** Julian Day (UT) of a Unix time in milliseconds. */
    fun julianDay(epochMs: Long): Double = epochMs / MS_PER_DAY + JD_UNIX_EPOCH

    /** Where the Sun is: geometric altitude of its centre (no refraction, parallax ignored). */
    fun sun(epochMs: Long, loc: GeoLocation): Horizontal {
        val jd = julianDay(epochMs)
        val eq = sunEquatorial(jd + DELTA_T_DAYS)
        val ha = greenwichApparentSiderealDeg(jd) + loc.lonDeg - eq.raDeg
        return hourAngleToHorizontal(ha, eq.decDeg, loc.latDeg)
    }

    /**
     * Where the Moon is: topocentric position of its centre, no refraction. Seen from the
     * surface rather than the Earth's centre the Moon sits up to 1 deg (its horizontal
     * parallax) lower, which moves moonrise by several minutes.
     */
    fun moon(epochMs: Long, loc: GeoLocation): Horizontal = moonView(epochMs, loc).position

    /** Phase of the Moon at [epochMs]. */
    fun moonPhase(epochMs: Long): MoonPhase {
        val jde = julianDay(epochMs) + DELTA_T_DAYS
        val elongation = elongationDeg(jde)
        // Measured, not the mean 12.19 deg/day: the true rate swings between 10 and 15.
        val rate = wrap180(
            elongationDeg(jde + RATE_STEP_DAYS) - elongationDeg(jde - RATE_STEP_DAYS),
        ) / (2 * RATE_STEP_DAYS)
        return MoonPhase(
            illuminated = illuminatedFraction(jde),
            waxing = elongation < 180.0,
            ageDays = ageDays(jde, elongation),
            name = phaseName(elongation, rate),
        )
    }

    /**
     * Where the galactic centre (Sagittarius A*) is: the J2000 place precessed to the date, see
     * [j2000ToHorizontal]. Geometric altitude, no refraction.
     */
    fun galacticCenter(epochMs: Long, loc: GeoLocation): Horizontal =
        j2000ToHorizontal(GALACTIC_CENTER_RA_J2000, GALACTIC_CENTER_DEC_J2000, epochMs, loc)

    /**
     * Converts a right ascension and declination of date (true equator and equinox, as
     * [sun] and [moon] use) to azimuth and altitude at [loc]. A J2000 catalogue position has
     * precessed by about a third of a degree by 2026; pass it to [j2000ToHorizontal] instead.
     * No refraction.
     */
    fun equatorialToHorizontal(
        raDeg: Double,
        decDeg: Double,
        epochMs: Long,
        loc: GeoLocation,
    ): Horizontal {
        val ha = greenwichApparentSiderealDeg(julianDay(epochMs)) + loc.lonDeg - raDeg
        return hourAngleToHorizontal(ha, decDeg, loc.latDeg)
    }

    /**
     * Azimuth and altitude at [loc] of a fixed object from a J2000.0 catalogue (a star, a
     * nebula), precessed to the date. Nutation and aberration, each under 0.006 deg, are left
     * out. No refraction.
     */
    fun j2000ToHorizontal(
        raDeg: Double,
        decDeg: Double,
        epochMs: Long,
        loc: GeoLocation,
    ): Horizontal {
        val ofDate = precessFromJ2000(raDeg, decDeg, julianDay(epochMs) + DELTA_T_DAYS)
        return equatorialToHorizontal(ofDate.raDeg, ofDate.decDeg, epochMs, loc)
    }

    /** The Moon's topocentric position and apparent radius at [loc]: what rise/set needs. */
    internal fun moonView(epochMs: Long, loc: GeoLocation): MoonView {
        val jd = julianDay(epochMs)
        val eq = moonEquatorial(jd + DELTA_T_DAYS)
        val ha = greenwichApparentSiderealDeg(jd) + loc.lonDeg - eq.raDeg
        val topo = topocentric(ha, eq.decDeg, eq.distanceKm, loc.latDeg)
        return MoonView(
            position = hourAngleToHorizontal(topo.haDeg, topo.decDeg, loc.latDeg),
            semidiameterDeg = asinDeg(MOON_RADIUS_KM / topo.distanceKm),
        )
    }

    /** Lit fraction of the Moon's disk at [epochMs]: [moonPhase] without the age and name. */
    internal fun moonIlluminated(epochMs: Long): Double =
        illuminatedFraction(julianDay(epochMs) + DELTA_T_DAYS)

    /** Local hour angle (deg, -180..180, west positive) of a J2000 place; 0 at upper transit. */
    internal fun j2000HourAngleDeg(
        raDeg: Double,
        decDeg: Double,
        epochMs: Long,
        loc: GeoLocation,
    ): Double {
        val jd = julianDay(epochMs)
        val ofDate = precessFromJ2000(raDeg, decDeg, jd + DELTA_T_DAYS)
        return wrap180(greenwichApparentSiderealDeg(jd) + loc.lonDeg - ofDate.raDeg)
    }

    // ---- Sun (Meeus ch. 25, low accuracy) -------------------------------------------------

    /**
     * Apparent ecliptic longitude of the Sun (aberration and nutation included) and its
     * distance, at Julian Ephemeris Day [jde]. The latitude is taken as 0 (it stays under 1").
     */
    internal fun sunEcliptic(jde: Double): Ecliptic {
        val t = (jde - J2000) / DAYS_PER_CENTURY
        val l0 = 280.46646 + t * (36000.76983 + t * 0.0003032)
        val m = 357.52911 + t * (35999.05029 - t * 0.0001537)
        val e = 0.016708634 - t * (0.000042037 + t * 0.0000001267)
        val center = (1.914602 - t * (0.004817 + t * 0.000014)) * sinDeg(m) +
            (0.019993 - t * 0.000101) * sinDeg(2 * m) +
            0.000289 * sinDeg(3 * m)
        val trueAnomaly = m + center
        val rAu = 1.000001018 * (1 - e * e) / (1 + e * cosDeg(trueAnomaly))
        val lon = l0 + center - 0.00569 - 0.00478 * sinDeg(sunOmegaDeg(t))
        return Ecliptic(norm360(lon), 0.0, rAu * AU_KM)
    }

    /** Apparent right ascension and declination of the Sun (true equinox of date). */
    internal fun sunEquatorial(jde: Double): Equatorial {
        val ecl = sunEcliptic(jde)
        val t = (jde - J2000) / DAYS_PER_CENTURY
        // Meeus 25.8: the obliquity corrected for the main nutation term, matching the
        // longitude's own short-cut nutation above.
        val eps = meanObliquityDeg(jde) + 0.00256 * cosDeg(sunOmegaDeg(t))
        return eclipticToEquatorial(ecl.lonDeg, 0.0, eps, ecl.distanceKm)
    }

    /** Longitude of the Moon's ascending node as ch. 25 rounds it, driving its nutation terms. */
    private fun sunOmegaDeg(t: Double): Double = 125.04 - 1934.136 * t

    // ---- Moon (Meeus ch. 47) --------------------------------------------------------------

    /**
     * Geocentric ecliptic longitude and latitude of the Moon (mean equinox of date, before
     * nutation) and its distance, from the full series of Meeus tables 47.A and 47.B.
     */
    internal fun moonEcliptic(jde: Double): Ecliptic {
        val t = (jde - J2000) / DAYS_PER_CENTURY
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t3 * t
        // Mean longitude, mean elongation, the Sun's and the Moon's mean anomalies and the
        // argument of latitude (Meeus 47.1-47.5), reduced first so the trig sees small angles.
        val lp = norm360(
            218.3164477 + 481267.88123421 * t - 0.0015786 * t2 + t3 / 538841.0 -
                t4 / 65194000.0,
        )
        val d = norm360(
            297.8501921 + 445267.1114034 * t - 0.0018819 * t2 + t3 / 545868.0 -
                t4 / 113065000.0,
        )
        val m = norm360(357.5291092 + 35999.0502909 * t - 0.0001536 * t2 + t3 / 24490000.0)
        val mp = norm360(
            134.9633964 + 477198.8675055 * t + 0.0087414 * t2 + t3 / 69699.0 -
                t4 / 14712000.0,
        )
        val f = norm360(
            93.2720950 + 483202.0175233 * t - 0.0036539 * t2 - t3 / 3526000.0 +
                t4 / 863310000.0,
        )
        val a1 = 119.75 + 131.849 * t      // action of Venus
        val a2 = 53.09 + 479264.290 * t    // action of Jupiter
        val a3 = 313.45 + 481266.484 * t   // flattening of the Earth
        // The Earth's orbit is slowly getting rounder, which weakens the terms that carry the
        // Sun's anomaly M: each is scaled by E once per multiple of M.
        val e = 1.0 - t * (0.002516 + t * 0.0000074)

        var sumL = 3958.0 * sinDeg(a1) + 1962.0 * sinDeg(lp - f) + 318.0 * sinDeg(a2)
        var sumR = 0.0
        var i = 0
        while (i < MOON_LR.size) {
            val arg = MOON_LR[i] * d + MOON_LR[i + 1] * m + MOON_LR[i + 2] * mp +
                MOON_LR[i + 3] * f
            val scale = eccentricityScale(MOON_LR[i + 1], e)
            val rad = Math.toRadians(arg)
            sumL += MOON_LR[i + 4] * scale * sin(rad)
            sumR += MOON_LR[i + 5] * scale * cos(rad)
            i += 6
        }
        var sumB = -2235.0 * sinDeg(lp) + 382.0 * sinDeg(a3) + 175.0 * sinDeg(a1 - f) +
            175.0 * sinDeg(a1 + f) + 127.0 * sinDeg(lp - mp) - 115.0 * sinDeg(lp + mp)
        i = 0
        while (i < MOON_B.size) {
            val arg = MOON_B[i] * d + MOON_B[i + 1] * m + MOON_B[i + 2] * mp + MOON_B[i + 3] * f
            sumB += MOON_B[i + 4] * eccentricityScale(MOON_B[i + 1], e) * sinDeg(arg)
            i += 5
        }
        // Coefficients are in 1e-6 deg (longitude, latitude) and metres (distance).
        return Ecliptic(norm360(lp + sumL / 1e6), sumB / 1e6, 385000.56 + sumR / 1000.0)
    }

    /** Apparent geocentric right ascension and declination of the Moon (true equinox). */
    internal fun moonEquatorial(jde: Double): Equatorial {
        val ecl = moonEcliptic(jde)
        val nut = nutation(jde)
        val eps = meanObliquityDeg(jde) + nut.dEpsDeg
        return eclipticToEquatorial(ecl.lonDeg + nut.dPsiDeg, ecl.latDeg, eps, ecl.distanceKm)
    }

    private fun eccentricityScale(mMultiple: Int, e: Double): Double = when (mMultiple) {
        0 -> 1.0
        1, -1 -> e
        else -> e * e
    }

    // ---- Phase (Meeus ch. 48) -------------------------------------------------------------

    /** Lit fraction of the Moon's disk at [jde], Meeus 48.1-48.3 (geocentric). */
    internal fun illuminatedFraction(jde: Double): Double {
        val moon = moonEcliptic(jde)
        val sun = sunEcliptic(jde)
        val moonLon = moon.lonDeg + nutation(jde).dPsiDeg
        // ψ: the Moon's angle from the Sun; i: the Sun-Moon-Earth (phase) angle.
        val cosPsi = (cosDeg(moon.latDeg) * cosDeg(moonLon - sun.lonDeg)).coerceIn(-1.0, 1.0)
        val psi = acos(cosPsi)
        val phaseAngle = atan2(sun.distanceKm * sin(psi), moon.distanceKm - sun.distanceKm * cosPsi)
        return (1 + cos(phaseAngle)) / 2
    }

    /**
     * The Moon's apparent geocentric longitude minus the Sun's (deg, 0..360): 0 at new moon,
     * 90 first quarter, 180 full, 270 last quarter - the definition of the phases.
     */
    internal fun elongationDeg(jde: Double): Double =
        norm360(moonEcliptic(jde).lonDeg + nutation(jde).dPsiDeg - sunEcliptic(jde).lonDeg)

    /**
     * Days since the last new moon, where the elongation (now [elongation] deg) last passed
     * through zero: a mean-rate guess, then fixed-point steps onto the true crossing.
     */
    private fun ageDays(jde: Double, elongation: Double): Double {
        var newMoon = jde - elongation / SYNODIC_DEG_PER_DAY
        repeat(NEW_MOON_STEPS) {
            newMoon -= wrap180(elongationDeg(newMoon)) / SYNODIC_DEG_PER_DAY
        }
        return (jde - newMoon).coerceAtLeast(0.0)
    }

    /**
     * Names the phase at [elongation] deg. [rateDegPerDay] turns the distance to the nearest
     * principal phase into time: within half a day of it, that phase gives the name.
     */
    private fun phaseName(elongation: Double, rateDegPerDay: Double): String {
        val nearest = (elongation / 90.0).roundToInt() % 4
        val daysAway = wrap180(elongation - nearest * 90.0) / rateDegPerDay.coerceAtLeast(1.0)
        if (abs(daysAway) <= 0.5) return PRINCIPAL_PHASES[nearest]
        return when {
            elongation < 90.0 -> MoonPhase.WAXING_CRESCENT
            elongation < 180.0 -> MoonPhase.WAXING_GIBBOUS
            elongation < 270.0 -> MoonPhase.WANING_GIBBOUS
            else -> MoonPhase.WANING_CRESCENT
        }
    }

    private val PRINCIPAL_PHASES = arrayOf(
        MoonPhase.NEW_MOON,
        MoonPhase.FIRST_QUARTER,
        MoonPhase.FULL_MOON,
        MoonPhase.LAST_QUARTER,
    )

    // ---- Earth's orientation (Meeus ch. 12, 21, 22) ---------------------------------------

    /** Greenwich mean sidereal time (deg, 0..360) at Julian Day [jd] (UT), Meeus 12.4. */
    internal fun greenwichMeanSiderealDeg(jd: Double): Double {
        val d = jd - J2000
        val t = d / DAYS_PER_CENTURY
        return norm360(280.46061837 + 360.98564736629 * d + t * t * (0.000387933 - t / 38710000.0))
    }

    /** Greenwich apparent sidereal time (deg): the mean plus the equation of the equinoxes. */
    internal fun greenwichApparentSiderealDeg(jd: Double): Double {
        val jde = jd + DELTA_T_DAYS
        val nut = nutation(jde)
        val eps = meanObliquityDeg(jde) + nut.dEpsDeg
        return norm360(greenwichMeanSiderealDeg(jd) + nut.dPsiDeg * cosDeg(eps))
    }

    /** Mean obliquity of the ecliptic (deg), Meeus 22.2. */
    internal fun meanObliquityDeg(jde: Double): Double {
        val t = (jde - J2000) / DAYS_PER_CENTURY
        val arcsec = 21.448 - t * (46.8150 + t * (0.00059 - t * 0.001813))
        return 23.0 + 26.0 / 60.0 + arcsec / 3600.0
    }

    /** Nutation, the four main terms of Meeus ch. 22: good to 0.5" in Δψ and 0.1" in Δε. */
    internal fun nutation(jde: Double): Nutation {
        val t = (jde - J2000) / DAYS_PER_CENTURY
        val omega = 125.04452 - 1934.136261 * t + 0.0020708 * t * t + t * t * t / 450000.0
        val sunLon = 280.4665 + 36000.7698 * t
        val moonLon = 218.3165 + 481267.8813 * t
        val dPsi = -17.20 * sinDeg(omega) - 1.32 * sinDeg(2 * sunLon) -
            0.23 * sinDeg(2 * moonLon) + 0.21 * sinDeg(2 * omega)
        val dEps = 9.20 * cosDeg(omega) + 0.57 * cosDeg(2 * sunLon) +
            0.10 * cosDeg(2 * moonLon) - 0.09 * cosDeg(2 * omega)
        return Nutation(dPsi / 3600.0, dEps / 3600.0)
    }

    /**
     * Precesses a J2000.0 place to the mean equator and equinox of [jde], with the rigorous
     * rotation of Meeus 21.2-21.4 (it stays exact near the celestial poles, unlike the annual
     * rates).
     */
    internal fun precessFromJ2000(raDeg: Double, decDeg: Double, jde: Double): Equatorial {
        val t = (jde - J2000) / DAYS_PER_CENTURY
        val zeta = t * (2306.2181 + t * (0.30188 + t * 0.017998)) / 3600.0
        val z = t * (2306.2181 + t * (1.09468 + t * 0.018203)) / 3600.0
        val theta = t * (2004.3109 - t * (0.42665 + t * 0.041833)) / 3600.0
        val cosDec = cosDeg(decDeg)
        val a = cosDec * sinDeg(raDeg + zeta)
        val b = cosDeg(theta) * cosDec * cosDeg(raDeg + zeta) - sinDeg(theta) * sinDeg(decDeg)
        val c = sinDeg(theta) * cosDec * cosDeg(raDeg + zeta) + cosDeg(theta) * sinDeg(decDeg)
        return Equatorial(norm360(atan2Deg(a, b) + z), atan2Deg(c, hypot(a, b)))
    }

    // ---- Coordinate transforms (Meeus ch. 11, 13, 40) -------------------------------------

    /** Ecliptic to equatorial coordinates for obliquity [epsDeg], Meeus 13.3-13.4. */
    internal fun eclipticToEquatorial(
        lonDeg: Double,
        latDeg: Double,
        epsDeg: Double,
        distanceKm: Double,
    ): Equatorial {
        val sinEps = sinDeg(epsDeg)
        val cosEps = cosDeg(epsDeg)
        val ra = atan2Deg(sinDeg(lonDeg) * cosEps - tanDeg(latDeg) * sinEps, cosDeg(lonDeg))
        val dec = asinDeg(sinDeg(latDeg) * cosEps + cosDeg(latDeg) * sinEps * sinDeg(lonDeg))
        return Equatorial(norm360(ra), dec, distanceKm)
    }

    /**
     * Moves a geocentric hour angle and declination to an observer at geodetic latitude
     * [latDeg], [heightM] above sea level: the body's vector minus the observer's (the
     * rigorous Meeus ch. 40 correction, with the Earth's flattening from ch. 11).
     */
    internal fun topocentric(
        haDeg: Double,
        decDeg: Double,
        distanceKm: Double,
        latDeg: Double,
        heightM: Double = 0.0,
    ): HourAngleDec {
        val u = atan(EARTH_AXIS_RATIO * tanDeg(latDeg))
        val h = heightM / (EARTH_RADIUS_KM * 1000.0)
        val rhoSin = EARTH_AXIS_RATIO * sin(u) + h * sinDeg(latDeg)
        val rhoCos = cos(u) + h * cosDeg(latDeg)
        // Equatorial frame turning with the Earth: x to the observer's meridian, y to the
        // west, z to the pole. The observer sits at (rhoCos, 0, rhoSin) in Earth radii.
        val r = distanceKm / EARTH_RADIUS_KM
        val x = r * cosDeg(decDeg) * cosDeg(haDeg) - rhoCos
        val y = r * cosDeg(decDeg) * sinDeg(haDeg)
        val z = r * sinDeg(decDeg) - rhoSin
        val xy = hypot(x, y)
        return HourAngleDec(atan2Deg(y, x), atan2Deg(z, xy), hypot(xy, z) * EARTH_RADIUS_KM)
    }

    /** Hour angle (west positive) and declination to azimuth and altitude, Meeus 13.5-13.6. */
    internal fun hourAngleToHorizontal(haDeg: Double, decDeg: Double, latDeg: Double): Horizontal {
        val sinDec = sinDeg(decDeg)
        val cosDec = cosDeg(decDeg)
        val cosHa = cosDeg(haDeg)
        val sinLat = sinDeg(latDeg)
        val cosLat = cosDeg(latDeg)
        // The direction split into zenith, north and east parts; atan2 rather than asin keeps
        // full precision near the zenith, where the galactic centre passes from the south.
        val up = sinLat * sinDec + cosLat * cosDec * cosHa
        val north = cosLat * sinDec - sinLat * cosDec * cosHa
        val east = -cosDec * sinDeg(haDeg)
        return Horizontal(norm360(atan2Deg(east, north)), atan2Deg(up, hypot(north, east)))
    }

    // ---- Angles ---------------------------------------------------------------------------

    private fun sinDeg(deg: Double) = sin(Math.toRadians(deg))
    private fun cosDeg(deg: Double) = cos(Math.toRadians(deg))
    private fun tanDeg(deg: Double) = tan(Math.toRadians(deg))
    private fun asinDeg(x: Double) = Math.toDegrees(asin(x.coerceIn(-1.0, 1.0)))
    private fun atan2Deg(y: Double, x: Double) = Math.toDegrees(atan2(y, x))

    /** [deg] reduced to 0..360. */
    internal fun norm360(deg: Double): Double {
        val r = deg - 360.0 * floor(deg / 360.0)
        return if (r >= 360.0) r - 360.0 else r
    }

    /** [deg] reduced to -180..180. */
    internal fun wrap180(deg: Double): Double = norm360(deg + 180.0) - 180.0

    /**
     * Meeus table 47.A, all 60 rows: multiples of D, M, M', F, then the longitude coefficient
     * (sine, 1e-6 deg) and the distance coefficient (cosine, metres). The full table costs
     * microseconds and keeps the Moon at about 10" instead of the truncated series' arcminute.
     */
    private val MOON_LR = intArrayOf(
        0, 0, 1, 0, 6288774, -20905355,
        2, 0, -1, 0, 1274027, -3699111,
        2, 0, 0, 0, 658314, -2955968,
        0, 0, 2, 0, 213618, -569925,
        0, 1, 0, 0, -185116, 48888,
        0, 0, 0, 2, -114332, -3149,
        2, 0, -2, 0, 58793, 246158,
        2, -1, -1, 0, 57066, -152138,
        2, 0, 1, 0, 53322, -170733,
        2, -1, 0, 0, 45758, -204586,
        0, 1, -1, 0, -40923, -129620,
        1, 0, 0, 0, -34720, 108743,
        0, 1, 1, 0, -30383, 104755,
        2, 0, 0, -2, 15327, 10321,
        0, 0, 1, 2, -12528, 0,
        0, 0, 1, -2, 10980, 79661,
        4, 0, -1, 0, 10675, -34782,
        0, 0, 3, 0, 10034, -23210,
        4, 0, -2, 0, 8548, -21636,
        2, 1, -1, 0, -7888, 24208,
        2, 1, 0, 0, -6766, 30824,
        1, 0, -1, 0, -5163, -8379,
        1, 1, 0, 0, 4987, -16675,
        2, -1, 1, 0, 4036, -12831,
        2, 0, 2, 0, 3994, -10445,
        4, 0, 0, 0, 3861, -11650,
        2, 0, -3, 0, 3665, 14403,
        0, 1, -2, 0, -2689, -7003,
        2, 0, -1, 2, -2602, 0,
        2, -1, -2, 0, 2390, 10056,
        1, 0, 1, 0, -2348, 6322,
        2, -2, 0, 0, 2236, -9884,
        0, 1, 2, 0, -2120, 5751,
        0, 2, 0, 0, -2069, 0,
        2, -2, -1, 0, 2048, -4950,
        2, 0, 1, -2, -1773, 4130,
        2, 0, 0, 2, -1595, 0,
        4, -1, -1, 0, 1215, -3958,
        0, 0, 2, 2, -1110, 0,
        3, 0, -1, 0, -892, 3258,
        2, 1, 1, 0, -810, 2616,
        4, -1, -2, 0, 759, -1897,
        0, 2, -1, 0, -713, -2117,
        2, 2, -1, 0, -700, 2354,
        2, 1, -2, 0, 691, 0,
        2, -1, 0, -2, 596, 0,
        4, 0, 1, 0, 549, -1423,
        0, 0, 4, 0, 537, -1117,
        4, -1, 0, 0, 520, -1571,
        1, 0, -2, 0, -487, -1739,
        2, 1, 0, -2, -399, 0,
        0, 0, 2, -2, -381, -4421,
        1, 1, 1, 0, 351, 0,
        3, 0, -2, 0, -340, 0,
        4, 0, -3, 0, 330, 0,
        2, -1, 2, 0, 327, 0,
        0, 2, 1, 0, -323, 1165,
        1, 1, -1, 0, 299, 0,
        2, 0, 3, 0, 294, 0,
        2, 0, -1, -2, 0, 8752,
    )

    /** Meeus table 47.B, all 60 rows: multiples of D, M, M', F, then the latitude (1e-6 deg). */
    private val MOON_B = intArrayOf(
        0, 0, 0, 1, 5128122,
        0, 0, 1, 1, 280602,
        0, 0, 1, -1, 277693,
        2, 0, 0, -1, 173237,
        2, 0, -1, 1, 55413,
        2, 0, -1, -1, 46271,
        2, 0, 0, 1, 32573,
        0, 0, 2, 1, 17198,
        2, 0, 1, -1, 9266,
        0, 0, 2, -1, 8822,
        2, -1, 0, -1, 8216,
        2, 0, -2, -1, 4324,
        2, 0, 1, 1, 4200,
        2, 1, 0, -1, -3359,
        2, -1, -1, 1, 2463,
        2, -1, 0, 1, 2211,
        2, -1, -1, -1, 2065,
        0, 1, -1, -1, -1870,
        4, 0, -1, -1, 1828,
        0, 1, 0, 1, -1794,
        0, 0, 0, 3, -1749,
        0, 1, -1, 1, -1565,
        1, 0, 0, 1, -1491,
        0, 1, 1, 1, -1475,
        0, 1, 1, -1, -1410,
        0, 1, 0, -1, -1344,
        1, 0, 0, -1, -1335,
        0, 0, 3, 1, 1107,
        4, 0, 0, -1, 1021,
        4, 0, -1, 1, 833,
        0, 0, 1, -3, 777,
        4, 0, -2, 1, 671,
        2, 0, 0, -3, 607,
        2, 0, 2, -1, 596,
        2, -1, 1, -1, 491,
        2, 0, -2, 1, -451,
        0, 0, 3, -1, 439,
        2, 0, 2, 1, 422,
        2, 0, -3, -1, 421,
        2, 1, -1, 1, -366,
        2, 1, 0, 1, -351,
        4, 0, 0, 1, 331,
        2, -1, 1, 1, 315,
        2, -2, 0, -1, 302,
        0, 0, 1, 3, -283,
        2, 1, 1, -1, -229,
        1, 1, 0, -1, 223,
        1, 1, 0, 1, 223,
        0, 1, -2, -1, -220,
        2, 1, -1, -1, -220,
        1, 0, 1, 1, -185,
        2, -1, -2, -1, 181,
        0, 1, 2, 1, -177,
        4, 0, -2, -1, 176,
        4, -1, -1, -1, 166,
        1, 0, 1, -1, -164,
        4, 0, 1, -1, 132,
        1, 0, -1, -1, -119,
        4, -1, 0, -1, 115,
        2, -2, 0, 1, 107,
    )
}
