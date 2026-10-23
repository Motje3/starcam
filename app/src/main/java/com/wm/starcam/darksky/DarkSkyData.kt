package com.wm.starcam.darksky

import android.content.Context
import java.io.InputStream
import java.util.zip.InflaterInputStream
import kotlin.math.log10

/** Lines of one base-map layer: line k runs over points starts[k] until starts[k + 1]. */
class Polylines(
    /** Per line: road class 1 major .. 3 minor; 0 for the other layers. */
    val classes: ByteArray,
    val starts: IntArray,
    /** Longitude, latitude pairs (deg). */
    val coords: FloatArray,
    /** Per line: min lon, min lat, max lon, max lat. */
    val bbox: FloatArray,
) {
    val size: Int get() = classes.size
}

/** A town (value: population) or a dark-sky place (value: 1 park, 2 reserve, 3 sanctuary, 4 community). */
class MapPlace(val lat: Double, val lon: Double, val value: Int, val name: String)

/**
 * Everything the dark-sky map draws, from the app's assets (tools/darksky/): the light-pollution
 * grid and the Natural Earth base map. Read once per process, on first use (a few hundred ms).
 */
class DarkSkyData(
    val glow: GlowGrid,
    val coast: Polylines,
    val lakes: Polylines,
    val borders: Polylines,
    val roads: Polylines,
    /** Largest first. */
    val towns: List<MapPlace>,
    val darkSites: List<MapPlace>,
) {
    companion object {
        @Volatile private var cached: DarkSkyData? = null
        @Volatile private var cachedGlow: GlowGrid? = null

        fun load(context: Context): DarkSkyData = cached ?: synchronized(this) {
            cached ?: read({ context.assets.open("darksky/$it") }, glow(context)).also { cached = it }
        }

        /** The light-pollution grid alone (3 MB, quick): enough to say how dark a place is. */
        fun glow(context: Context): GlowGrid = cachedGlow ?: synchronized(this) {
            cachedGlow ?: GlowGrid(context.assets.open("darksky/glow.bin").use { it.readBytes() }).also { cachedGlow = it }
        }

        /** [open] gives a file of assets/darksky by name (the tests read them off the disk). */
        fun read(open: (String) -> InputStream, glow: GlowGrid? = null): DarkSkyData = DarkSkyData(
            glow = glow ?: GlowGrid(open("glow.bin").use { it.readBytes() }),
            coast = polylines(open("coast.bin")),
            lakes = polylines(open("lakes.bin")),
            borders = polylines(open("borders.bin")),
            roads = polylines(open("roads.bin")),
            towns = places(open("places.bin")),
            darkSites = places(open("darksites.bin")),
        )

        private fun unzigzag(v: Long): Long = (v ushr 1) xor -(v and 1)

        /** The whole file inflated at once: parsing a byte array is many times faster than a stream. */
        private fun inflated(input: InputStream): ByteArray {
            val inflater = java.util.zip.Inflater()
            try {
                return InflaterInputStream(input.buffered(1 shl 16), inflater, 1 shl 16).use { it.readBytes() }
            } finally {
                inflater.end()  // an inflater handed in is not ended by the stream
            }
        }

        /** A cursor over big-endian ints (DataOutputStream's) and varints. */
        private class Reader(val b: ByteArray) {
            var pos = 0
            fun byte(): Int = b[pos++].toInt()
            fun int(): Int {
                val v = ((b[pos].toInt() and 0xff) shl 24) or ((b[pos + 1].toInt() and 0xff) shl 16) or
                    ((b[pos + 2].toInt() and 0xff) shl 8) or (b[pos + 3].toInt() and 0xff)
                pos += 4
                return v
            }
            fun varint(): Long {
                var shift = 0
                var result = 0L
                while (true) {
                    val x = b[pos++].toInt() and 0xff
                    result = result or ((x and 0x7F).toLong() shl shift)
                    if (x and 0x80 == 0) return result
                    shift += 7
                }
            }
            fun magic(): String = String(b, pos, 4, Charsets.US_ASCII).also { pos += 4 }
        }

        private fun polylines(input: InputStream): Polylines {
            val d = Reader(inflated(input))
            require(d.magic() == "SCPL") { "not a polyline layer" }
            require(d.int() == 1)
            val n = d.int()
            val classes = ByteArray(n)
            val starts = IntArray(n + 1)
            val bbox = FloatArray(4 * n)
            var coords = FloatArray(1 shl 16)
            var used = 0
            for (k in 0 until n) {
                classes[k] = d.byte().toByte()
                val count = d.varint().toInt()
                starts[k] = used / 2
                if (used + 2 * count > coords.size) coords = coords.copyOf(maxOf(coords.size * 2, used + 2 * count))
                var x = d.int().toLong()
                var y = d.int().toLong()
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                for (i in 0 until count) {
                    if (i > 0) {
                        x += unzigzag(d.varint())
                        y += unzigzag(d.varint())
                    }
                    val lon = (x / 1e5).toFloat()
                    val lat = (y / 1e5).toFloat()
                    coords[used++] = lon
                    coords[used++] = lat
                    if (lon < minX) minX = lon
                    if (lon > maxX) maxX = lon
                    if (lat < minY) minY = lat
                    if (lat > maxY) maxY = lat
                }
                bbox[4 * k] = minX; bbox[4 * k + 1] = minY; bbox[4 * k + 2] = maxX; bbox[4 * k + 3] = maxY
            }
            starts[n] = used / 2
            return Polylines(classes, starts, coords.copyOf(used), bbox)
        }

        private fun places(input: InputStream): List<MapPlace> {
            val d = Reader(inflated(input))
            require(d.magic() == "SCPT") { "not a place list" }
            require(d.int() == 1)
            val n = d.int()
            return List(n) {
                val lon = d.int() / 1e5
                val lat = d.int() / 1e5
                val value = d.int()
                val len = ((d.byte() and 0xff) shl 8) or (d.byte() and 0xff)
                val name = String(d.b, d.pos, len, Charsets.UTF_8)
                d.pos += len
                MapPlace(lat, lon, value, name)
            }
        }
    }
}

/**
 * Sky quality from the model's all-sky ALR: the zenith brightness (mag/arcsec2, an SQM reading)
 * and the Bortle class. The zenith fit, m = 22.0 - 2.5 log10(1 + 0.5 ALR), came out of the 656
 * National Park Service nights (k 0.44, 0.36 mag rms) and 1848 Globe at Night readings (about
 * 0.57); city centres read brighter than the model gives.
 */
object SkyQuality {
    const val NATURAL_SQM = 22.0
    const val ZENITH_PER_ALR = 0.5

    fun sqm(alr: Double): Double = NATURAL_SQM - 2.5 * log10(1 + ZENITH_PER_ALR * alr.coerceAtLeast(0.0))

    /** The usual SQM bounds of the Bortle classes. */
    fun bortle(sqm: Double): Int = when {
        sqm >= 21.99 -> 1
        sqm >= 21.89 -> 2
        sqm >= 21.69 -> 3
        sqm >= 20.49 -> 4
        sqm >= 19.50 -> 5
        sqm >= 18.94 -> 6
        sqm >= 18.38 -> 7
        sqm >= 17.80 -> 8
        else -> 9
    }

    fun name(bortle: Int): String = when (bortle) {
        1 -> "Pristine dark sky"
        2 -> "Truly dark sky"
        3 -> "Rural sky"
        4 -> "Rural to suburban sky"
        5 -> "Suburban sky"
        6 -> "Bright suburban sky"
        7 -> "Suburban to city sky"
        8 -> "City sky"
        else -> "Inner-city sky"
    }

    /** What the Milky Way looks like under it, to the eye, and what the camera can still do. */
    fun milkyWay(bortle: Int): String = when (bortle) {
        1, 2 -> "The Milky Way is full of detail, dust lanes down to the horizon."
        3 -> "The Milky Way is clearly structured; only faint light domes on the horizon."
        4 -> "The Milky Way is plain overhead but fades towards the horizon."
        5 -> "The Milky Way is faint and gone near the horizon; stacking still brings it out."
        6 -> "The Milky Way is barely there overhead; stacking pulls out a little."
        else -> "No Milky Way to the eye: the Moon, planets and star trails work here."
    }
}
