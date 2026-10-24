package com.wm.starcam.sky

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Something the sky view labels: a planet, the Moon, the Sun. [xyz] as in [SkyScene]. */
class SkyBody(val name: String, val xyz: FloatArray, val altDeg: Double, val mag: Float)

/**
 * Everything the sky view draws, for one moment and place, as unit vectors in the phone's
 * compass frame - x east, y magnetic north, z up, the world frame of the rotation-vector sensor -
 * so a sensor reading turns them straight into screen positions ([SkyProjection]).
 */
class SkyScene(
    val epochMs: Long,
    /** xyz per catalogue star, in [StarCatalog] order. */
    val stars: FloatArray,
    /**
     * The Milky Way as nested strips along the galactic equator: for each level, xyz of the
     * lower and upper edge at every step of galactic longitude ([BAND_STEP_DEG] apart).
     */
    val band: Array<FloatArray>,
    /** Relative surface brightness of the band at each longitude step, 0..1. */
    val bandGlow: FloatArray,
    val core: FloatArray,
    val coreAltDeg: Double,
    val moon: SkyBody,
    val moonIlluminated: Double,
    val sun: SkyBody,
    val planets: List<SkyBody>,
    /** The horizon every [HORIZON_STEP_DEG] of azimuth, xyz each. */
    val horizon: FloatArray,
    val cardinals: List<Pair<String, FloatArray>>,
    /** J2000 unit vectors into this frame (row-major), for anything else to be placed in it. */
    val toCompass: DoubleArray = DoubleArray(9).also { it[0] = 1.0; it[4] = 1.0; it[8] = 1.0 },
) {
    /** A J2000 place in this frame. */
    fun vector(raDeg: Double, decDeg: Double): FloatArray = FloatArray(3).also { apply(toCompass, unit(raDeg, decDeg), it, 0) }

    companion object {
        const val BAND_STEP_DEG = 4
        const val HORIZON_STEP_DEG = 5

        /** Half-widths (fractions of the band's) of the nested Milky Way strips. */
        val BAND_LEVELS = floatArrayOf(1.0f, 0.62f, 0.3f)

        private const val J2000 = 2451545.0

        /** The galactic north pole and the north celestial pole's galactic longitude (J2000). */
        private const val NGP_RA = 192.85948
        private const val NGP_DEC = 27.12825
        private const val NCP_L = 122.93192

        /** J2000 right ascension and declination (deg) of galactic longitude [lDeg] and latitude [bDeg]. */
        fun galacticToJ2000(lDeg: Double, bDeg: Double): Pair<Double, Double> {
            val b = Math.toRadians(bDeg)
            val dl = Math.toRadians(NCP_L - lDeg)
            val dNgp = Math.toRadians(NGP_DEC)
            val sinDec = sin(b) * sin(dNgp) + cos(b) * cos(dNgp) * cos(dl)
            val y = cos(b) * sin(dl)
            val x = sin(b) * cos(dNgp) - cos(b) * sin(dNgp) * cos(dl)
            var ra = NGP_RA + Math.toDegrees(atan2(y, x))
            ra = ((ra % 360.0) + 360.0) % 360.0
            return ra to Math.toDegrees(asin(sinDec.coerceIn(-1.0, 1.0)))
        }

        /** Half-width (deg) and brightness of the Milky Way at galactic longitude [lDeg]. */
        private fun bandShape(lDeg: Double): Pair<Double, Float> {
            val l = if (lDeg > 180) lDeg - 360 else lDeg
            val bulge = exp(-(l / 28.0) * (l / 28.0))
            val width = 7.0 + 9.0 * bulge
            // Brightest towards the core, a second rise in Cygnus, faintest opposite the core.
            val cygnus = exp(-((l - 78.0) / 22.0) * ((l - 78.0) / 22.0))
            val glow = (0.28 + 0.72 * bulge + 0.25 * cygnus).coerceAtMost(1.0)
            return width to glow.toFloat()
        }

        /** J2000 unit vectors of the band's strip edges, and its brightness: fixed, worked out once. */
        private val bandJ2000: Pair<Array<DoubleArray>, FloatArray> by lazy {
            val steps = 360 / BAND_STEP_DEG + 1
            val glow = FloatArray(steps)
            val levels = Array(BAND_LEVELS.size) { DoubleArray(steps * 6) }
            for (k in 0 until steps) {
                val l = (k * BAND_STEP_DEG).toDouble()
                val (w, g) = bandShape(l)
                glow[k] = g
                for ((li, f) in BAND_LEVELS.withIndex()) {
                    for ((side, b) in listOf(-w * f, w * f).withIndex()) {
                        val (ra, dec) = galacticToJ2000(l, b)
                        val v = unit(ra, dec)
                        System.arraycopy(v, 0, levels[li], k * 6 + side * 3, 3)
                    }
                }
            }
            levels to glow
        }

        private fun unit(raDeg: Double, decDeg: Double): DoubleArray {
            val ra = Math.toRadians(raDeg)
            val dec = Math.toRadians(decDeg)
            return doubleArrayOf(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec))
        }

        /** Compass-frame vector of a horizontal position, the declination taken off its azimuth. */
        fun compassVector(h: Horizontal, declinationDeg: Double): FloatArray {
            val az = Math.toRadians(h.azDeg - declinationDeg)
            val alt = Math.toRadians(h.altDeg)
            return floatArrayOf((cos(alt) * sin(az)).toFloat(), (cos(alt) * cos(az)).toFloat(), sin(alt).toFloat())
        }

        /**
         * The matrix taking J2000 equatorial unit vectors into the compass frame at [epochMs] and
         * [loc] (row-major): precession to the date, the local sidereal time and latitude, and
         * the magnetic declination. One matrix turns the whole catalogue.
         */
        fun j2000ToCompass(epochMs: Long, loc: GeoLocation, declinationDeg: Double): DoubleArray {
            val jd = SkyMath.julianDay(epochMs)
            val jde = jd + SkyMath.DELTA_T_SEC / 86_400.0
            // Precession: the basis vectors' places of date are the matrix's columns.
            val p = DoubleArray(9)
            listOf(0.0 to 0.0, 90.0 to 0.0, 0.0 to 90.0).forEachIndexed { col, (ra, dec) ->
                val q = SkyMath.precessFromJ2000(ra, dec, jde)
                val v = unit(q.raDeg, q.decDeg)
                for (row in 0..2) p[row * 3 + col] = v[row]
            }
            val theta = Math.toRadians(SkyMath.greenwichApparentSiderealDeg(jd) + loc.lonDeg)
            val phi = Math.toRadians(loc.latDeg)
            val st = sin(theta)
            val ct = cos(theta)
            val sp = sin(phi)
            val cp = cos(phi)
            // Equator of date -> east, north, up (hour angle = sidereal time - right ascension).
            val h = doubleArrayOf(
                -st, ct, 0.0,
                -ct * sp, -st * sp, cp,
                ct * cp, st * cp, sp,
            )
            // True north -> magnetic north: azimuths shrink by the declination.
            val d = Math.toRadians(declinationDeg)
            val cd = cos(d)
            val sd = sin(d)
            val m = doubleArrayOf(cd, -sd, 0.0, sd, cd, 0.0, 0.0, 0.0, 1.0)
            return mul(m, mul(h, p))
        }

        private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
            val c = DoubleArray(9)
            for (r in 0..2) for (k in 0..2) {
                var s = 0.0
                for (j in 0..2) s += a[r * 3 + j] * b[j * 3 + k]
                c[r * 3 + k] = s
            }
            return c
        }

        private fun apply(m: DoubleArray, v: DoubleArray, out: FloatArray, at: Int) {
            for (r in 0..2) out[at + r] = (m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2]).toFloat()
        }

        /** Builds the scene for [epochMs] at [loc]; a few milliseconds for the whole catalogue. */
        fun build(epochMs: Long, loc: GeoLocation, declinationDeg: Double, catalog: StarCatalog): SkyScene {
            val m = j2000ToCompass(epochMs, loc, declinationDeg)
            val stars = FloatArray(catalog.stars.size * 3)
            catalog.stars.forEachIndexed { i, s -> apply(m, unit(s.raDeg, s.decDeg), stars, i * 3) }

            val (levels, glow) = bandJ2000
            val band = Array(levels.size) { li ->
                val src = levels[li]
                FloatArray(src.size).also { out ->
                    for (k in 0 until src.size / 3) {
                        apply(m, doubleArrayOf(src[k * 3], src[k * 3 + 1], src[k * 3 + 2]), out, k * 3)
                    }
                }
            }

            val coreH = SkyMath.galacticCenter(epochMs, loc)
            val moonH = SkyMath.moon(epochMs, loc)
            val sunH = SkyMath.sun(epochMs, loc)
            val planets = Planets.places(epochMs).map { p ->
                val xyz = FloatArray(3).also { apply(m, unit(p.raDeg, p.decDeg), it, 0) }
                SkyBody(p.name, xyz, Math.toDegrees(asin(xyz[2].toDouble().coerceIn(-1.0, 1.0))), p.mag)
            }

            val n = 360 / HORIZON_STEP_DEG
            val horizon = FloatArray((n + 1) * 3)
            for (k in 0..n) {
                val v = compassVector(Horizontal((k * HORIZON_STEP_DEG).toDouble(), 0.0), declinationDeg)
                System.arraycopy(v, 0, horizon, k * 3, 3)
            }
            val cardinals = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW").mapIndexed { i, name ->
                name to compassVector(Horizontal(i * 45.0, 0.0), declinationDeg)
            }
            return SkyScene(
                epochMs = epochMs,
                stars = stars,
                band = band,
                bandGlow = glow,
                core = compassVector(coreH, declinationDeg),
                coreAltDeg = coreH.altDeg,
                moon = SkyBody("Moon", compassVector(moonH, declinationDeg), moonH.altDeg, -10f),
                moonIlluminated = SkyMath.moonPhase(epochMs).illuminated,
                sun = SkyBody("Sun", compassVector(sunH, declinationDeg), sunH.altDeg, -26f),
                planets = planets,
                horizon = horizon,
                cardinals = cardinals,
                toCompass = m,
            )
        }
    }
}

/**
 * Screen positions from the rotation-vector sensor's matrix [r] (row-major, device to world):
 * the back camera looks along the phone's -z, x is the screen's right and y its top (the app is
 * held in portrait). [tanHalfW] and [tanHalfH] are the camera's half field of view across and up
 * the screen, as tangents.
 */
class SkyProjection(private val r: FloatArray, private val tanHalfW: Float, private val tanHalfH: Float) {

    /**
     * Screen position (pixels, in a [w] x [h] view) of the compass-frame vector at [v][[at]],
     * written to [out]; false when it lies behind the camera (or within [minDepth] of its side).
     */
    fun project(v: FloatArray, at: Int, w: Float, h: Float, out: FloatArray, minDepth: Float = 0.05f): Boolean {
        val x = v[at]
        val y = v[at + 1]
        val z = v[at + 2]
        val dx = r[0] * x + r[3] * y + r[6] * z
        val dy = r[1] * x + r[4] * y + r[7] * z
        val dz = r[2] * x + r[5] * y + r[8] * z
        if (-dz < minDepth) return false
        out[0] = w / 2f + (dx / -dz) / tanHalfW * (w / 2f)
        out[1] = h / 2f - (dy / -dz) / tanHalfH * (h / 2f)
        return true
    }

    /**
     * Direction on the screen (unit, y down) towards the vector at [v][[at]], for an arrow at
     * the edge when it is out of view; also right for things behind the camera.
     */
    fun direction(v: FloatArray, at: Int, out: FloatArray): Boolean {
        val x = v[at]
        val y = v[at + 1]
        val z = v[at + 2]
        val dx = r[0] * x + r[3] * y + r[6] * z
        val dy = r[1] * x + r[4] * y + r[7] * z
        val n = sqrt(dx * dx + dy * dy)
        if (n < 1e-4f) return false
        out[0] = dx / n
        out[1] = -dy / n
        return true
    }
}
