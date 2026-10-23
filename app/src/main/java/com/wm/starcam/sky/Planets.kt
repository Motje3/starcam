package com.wm.starcam.sky

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A planet's J2000 place as seen from the Earth's centre (deg) and its typical brightness. */
data class PlanetPlace(val name: String, val raDeg: Double, val decDeg: Double, val mag: Float)

/**
 * The bright planets, from JPL's "Keplerian Elements for Approximate Positions of the Major
 * Planets" (E. M. Standish, Table 1: valid 1800-2050, errors of arcminutes - far below what a
 * phone's compass can show). Light time and aberration are left out for the same reason.
 */
object Planets {

    private class Elements(
        val name: String,
        val mag: Float,
        // a (au), e, I (deg), L (deg), long. perihelion (deg), long. ascending node (deg), and
        // their rates per Julian century.
        val a: Double, val e: Double, val i: Double, val l: Double, val peri: Double, val node: Double,
        val aDot: Double, val eDot: Double, val iDot: Double, val lDot: Double, val periDot: Double,
        val nodeDot: Double,
    )

    // Table 1 of https://ssd.jpl.nasa.gov/planets/approx_pos.html; mag: a typical value, for the dot.
    private val MERCURY = Elements("Mercury", 0.0f,
        0.38709927, 0.20563593, 7.00497902, 252.25032350, 77.45779628, 48.33076593,
        0.00000037, 0.00001906, -0.00594749, 149472.67411175, 0.16047689, -0.12534081)
    private val VENUS = Elements("Venus", -4.2f,
        0.72333566, 0.00677672, 3.39467605, 181.97909950, 131.60246718, 76.67984255,
        0.00000390, -0.00004107, -0.00078890, 58517.81538729, 0.00268329, -0.27769418)
    private val EM_BARY = Elements("Earth", 0f,
        1.00000261, 0.01671123, -0.00001531, 100.46457166, 102.93768193, 0.0,
        0.00000562, -0.00004392, -0.01294668, 35999.37244981, 0.32327364, 0.0)
    private val MARS = Elements("Mars", 0.5f,
        1.52371034, 0.09339410, 1.84969142, -4.55343205, -23.94362959, 49.55953891,
        0.00001847, 0.00007882, -0.00813131, 19140.30268499, 0.44441088, -0.29257343)
    private val JUPITER = Elements("Jupiter", -2.4f,
        5.20288700, 0.04838624, 1.30439695, 34.39644051, 14.72847983, 100.47390909,
        -0.00011607, -0.00013253, -0.00183714, 3034.74612775, 0.21252668, 0.20469106)
    private val SATURN = Elements("Saturn", 0.6f,
        9.53667594, 0.05386179, 2.48599187, 49.95424423, 92.59887831, 113.66242448,
        -0.00125060, -0.00050991, 0.00193609, 1222.49362201, -0.41897216, -0.28867794)

    private val VISIBLE = listOf(MERCURY, VENUS, MARS, JUPITER, SATURN)

    /** Obliquity of the J2000 ecliptic (deg). */
    private const val OBLIQUITY_J2000 = 23.43928

    /** Where the naked-eye planets are at [epochMs] (geocentric, J2000 equator and equinox). */
    fun places(epochMs: Long): List<PlanetPlace> {
        val t = centuries(epochMs)
        val earth = heliocentric(EM_BARY, t)
        return VISIBLE.map { el ->
            val p = heliocentric(el, t)
            val (ra, dec) = toRaDec(p[0] - earth[0], p[1] - earth[1], p[2] - earth[2])
            PlanetPlace(el.name, ra, dec, el.mag)
        }
    }

    /** The Sun from the same elements (the Earth-Moon barycentre turned around); for tests. */
    internal fun sun(epochMs: Long): Pair<Double, Double> {
        val e = heliocentric(EM_BARY, centuries(epochMs))
        return toRaDec(-e[0], -e[1], -e[2])
    }

    /** Julian centuries of Terrestrial Time since J2000.0. */
    private fun centuries(epochMs: Long): Double =
        (SkyMath.julianDay(epochMs) + SkyMath.DELTA_T_SEC / 86_400.0 - 2451545.0) / 36525.0

    /** Heliocentric position in the J2000 ecliptic frame (au). */
    private fun heliocentric(el: Elements, t: Double): DoubleArray {
        val a = el.a + el.aDot * t
        val e = el.e + el.eDot * t
        val i = Math.toRadians(el.i + el.iDot * t)
        val l = el.l + el.lDot * t
        val peri = el.peri + el.periDot * t
        val node = el.node + el.nodeDot * t
        val w = Math.toRadians(peri - node)
        val o = Math.toRadians(node)
        var m = (l - peri) % 360.0
        if (m > 180.0) m -= 360.0
        if (m < -180.0) m += 360.0
        val mRad = Math.toRadians(m)
        // Kepler's equation by Newton's method; e < 0.21, so a few steps reach 1e-12.
        var ea = mRad + e * sin(mRad)
        for (step in 0 until 30) {
            val d = (ea - e * sin(ea) - mRad) / (1 - e * cos(ea))
            ea -= d
            if (abs(d) < 1e-12) break
        }
        val xp = a * (cos(ea) - e)
        val yp = a * sqrt(1 - e * e) * sin(ea)
        val cw = cos(w)
        val sw = sin(w)
        val co = cos(o)
        val so = sin(o)
        val ci = cos(i)
        val si = sin(i)
        return doubleArrayOf(
            (cw * co - sw * so * ci) * xp + (-sw * co - cw * so * ci) * yp,
            (cw * so + sw * co * ci) * xp + (-sw * so + cw * co * ci) * yp,
            (sw * si) * xp + (cw * si) * yp,
        )
    }

    /** Right ascension and declination (deg) of an ecliptic J2000 vector. */
    private fun toRaDec(x: Double, yEcl: Double, zEcl: Double): Pair<Double, Double> {
        val eps = Math.toRadians(OBLIQUITY_J2000)
        val y = cos(eps) * yEcl - sin(eps) * zEcl
        val z = sin(eps) * yEcl + cos(eps) * zEcl
        var ra = Math.toDegrees(atan2(y, x))
        if (ra < 0) ra += 360.0
        val dec = Math.toDegrees(atan2(z, sqrt(x * x + y * y)))
        return ra to dec
    }
}
