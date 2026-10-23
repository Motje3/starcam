package com.wm.starcam.darksky

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A place worth driving to: its sky, how far, which way, and the nearest town for reference. */
data class DarkSpot(
    val lat: Double,
    val lon: Double,
    val alr: Double,
    val distanceKm: Double,
    val bearingDeg: Double,
    /** Why it is suggested: "Darkest within 50 km", "Nearest darker sky", a dark-sky park's name. */
    val title: String,
    /** "14 km NE of Assen", when a town is near enough to say. */
    val near: String?,
) {
    val sqm: Double get() = SkyQuality.sqm(alr)
    val bortle: Int get() = SkyQuality.bortle(sqm)
}

/**
 * Finds the darker skies within reach of a place on the [GlowGrid]: the nearest sky at least a
 * Bortle class darker, the darkest within 25, 50, 100 and 150 km (each only if it beats the nearer
 * ones by enough to be worth the drive), and the nearest certified dark-sky place.
 */
object SpotFinder {

    data class Result(
        /** The sky where you are; 0 ALR on water or off the map. */
        val hereAlr: Double,
        val nearestDarker: DarkSpot?,
        val darkest: List<DarkSpot>,
        val darkSite: DarkSpot?,
    ) {
        val hereSqm: Double get() = SkyQuality.sqm(hereAlr)
        val hereBortle: Int get() = SkyQuality.bortle(hereSqm)

        /** Every suggestion once, nearest darker first. */
        val all: List<DarkSpot> get() = listOfNotNull(nearestDarker) + darkest + listOfNotNull(darkSite)
    }

    private val BANDS = doubleArrayOf(25.0, 50.0, 100.0, 150.0)

    /** A farther spot must be this much darker (mag/arcsec2) than the nearer ones to be listed. */
    private const val WORTH_THE_DRIVE = 0.15

    fun find(data: DarkSkyData, lat: Double, lon: Double): Result {
        val g = data.glow
        val here = g.alr(lat, lon).takeIf { g.value(lat, lon) > 0 } ?: nearestLandAlr(g, lat, lon)
        val hereSqm = SkyQuality.sqm(here)
        val hereBortle = SkyQuality.bortle(hereSqm)
        val maxKm = BANDS.last()
        val centreRow = g.row(lat) ?: return Result(here, null, emptyList(), null)
        val cellKm = 111.32 / g.cellsPerDeg
        val dRows = (maxKm / cellKm).toInt() + 1
        val bestValue = IntArray(BANDS.size) { Int.MAX_VALUE }
        val bestDist = DoubleArray(BANDS.size) { Double.MAX_VALUE }
        val bestCell = Array(BANDS.size) { IntArray(2) }
        var darkerDist = Double.MAX_VALUE
        val darkerCell = IntArray(2)
        // A Bortle class darker, and at least half a magnitude: a small step is not worth a drive.
        val darkerTarget = minOf(hereSqm + 0.5, 22.0)
        for (r in (centreRow - dRows)..(centreRow + dRows)) {
            if (r < 0 || r >= g.height) continue
            val cLat = g.cellLat(r)
            val kx = cos(Math.toRadians(cLat)) * cellKm
            val dCols = if (kx > 0.01) (maxKm / kx).toInt() + 1 else g.width / 2
            val centreCol = g.col(lon)
            for (dc in -dCols..dCols) {
                val c = centreCol + dc
                val v = g.value(r, c)
                if (v == 0) continue
                val cLon = g.cellLon(Math.floorMod(c, g.width))
                val d = distanceKm(lat, lon, cLat, cLon)
                if (d > maxKm) continue
                val band = BANDS.indexOfFirst { d <= it }
                if (v < bestValue[band] || (v == bestValue[band] && d < bestDist[band])) {
                    bestValue[band] = v
                    bestDist[band] = d
                    bestCell[band][0] = r
                    bestCell[band][1] = c
                }
                if (d < darkerDist) {
                    val s = SkyQuality.sqm(g.alr(v))
                    if (s >= darkerTarget && SkyQuality.bortle(s) < hereBortle) {
                        darkerDist = d
                        darkerCell[0] = r
                        darkerCell[1] = c
                    }
                }
            }
        }
        fun spot(r: Int, c: Int, title: String): DarkSpot {
            val sLat = g.cellLat(r)
            val sLon = g.cellLon(Math.floorMod(c, g.width))
            return DarkSpot(sLat, sLon, g.alr(g.value(r, c)), distanceKm(lat, lon, sLat, sLon),
                bearingDeg(lat, lon, sLat, sLon), title, describe(data.towns, sLat, sLon))
        }
        val nearestDarker = if (darkerDist < Double.MAX_VALUE) spot(darkerCell[0], darkerCell[1], "Nearest darker sky") else null
        val darkest = ArrayList<DarkSpot>()
        var bestSoFar = maxOf(hereSqm, nearestDarker?.sqm ?: 0.0)
        // Inner bands first: a farther one is listed only when it is clearly darker still.
        for (k in BANDS.indices) {
            if (bestValue[k] == Int.MAX_VALUE) continue
            val s = spot(bestCell[k][0], bestCell[k][1], "Darkest within ${BANDS[k].roundToInt()} km")
            if (s.sqm >= bestSoFar + WORTH_THE_DRIVE) {
                darkest += s
                bestSoFar = s.sqm
            }
        }
        val site = data.darkSites.minByOrNull { distanceKm(lat, lon, it.lat, it.lon) }
            ?.takeIf { distanceKm(lat, lon, it.lat, it.lon) <= DARK_SITE_KM }
            ?.let {
                DarkSpot(it.lat, it.lon, g.alr(it.lat, it.lon), distanceKm(lat, lon, it.lat, it.lon),
                    bearingDeg(lat, lon, it.lat, it.lon), it.name, describe(data.towns, it.lat, it.lon))
            }
        return Result(here, nearestDarker, darkest, site)
    }

    /** Certified dark-sky places are worth naming this far away. */
    private const val DARK_SITE_KM = 400.0

    /** Out at sea or on a lake shore: the sky of the nearest land cell. */
    private fun nearestLandAlr(g: GlowGrid, lat: Double, lon: Double): Double {
        val r0 = g.row(lat) ?: return 0.0
        val c0 = g.col(lon)
        for (radius in 1..6) {
            var best = -1
            for (r in r0 - radius..r0 + radius) for (c in c0 - radius..c0 + radius) {
                val v = g.value(r, c)
                if (v > 0 && (best < 0 || v > best)) best = v
            }
            if (best > 0) return g.alr(best)
        }
        return 0.0
    }

    /** "14 km NE of Assen", from the nearest sizeable town within 60 km. */
    fun describe(towns: List<MapPlace>, lat: Double, lon: Double): String? {
        var best: MapPlace? = null
        var bestD = 60.0
        for (t in towns) {
            if (t.value < 5_000) continue
            if (kotlin.math.abs(t.lat - lat) > 0.6) continue
            val d = distanceKm(t.lat, t.lon, lat, lon)
            if (d < bestD) { bestD = d; best = t }
        }
        val town = best ?: return null
        if (bestD < 2.0) return "at ${town.name}"
        return "${bestD.roundToInt()} km ${compass(bearingDeg(town.lat, town.lon, lat, lon))} of ${town.name}"
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * 6371.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private val POINTS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun compass(bearingDeg: Double): String = POINTS[((bearingDeg / 45.0).roundToInt() % 8 + 8) % 8]
}
