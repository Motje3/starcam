package com.wm.starcam.sky

import kotlin.math.max
import kotlin.math.min

/**
 * The stars in a finished photo, for plate solving: points well above the sky around them, their
 * centres and brightness. Works on the picture as saved - 8 bits, stretched, perhaps edited - so
 * it serves any session, old ones too. Lit windows, street lamps and the Moon are left out by
 * their size; what is left is mostly stars, which is all the solver needs.
 */
object PhotoStars {

    /** Sky cells for the background and its noise (px). */
    private const val CELL = 32

    /** How far round a peak its light is summed (px). */
    private const val R = 3

    /** Wider than this at half its height (pixels counted) it is not a star. */
    private const val MAX_AREA = 150

    /**
     * Stars in an ARGB photo [argb] of [width] x [height] pixels, brightest first, at most [max].
     */
    fun find(argb: IntArray, width: Int, height: Int, max: Int = 400): List<ImageStar> {
        if (width < 64 || height < 64 || argb.size < width * height) return emptyList()
        val lum = FloatArray(width * height)
        for (i in lum.indices) {
            val p = argb[i]
            lum[i] = (2 * ((p shr 16) and 255) + 5 * ((p shr 8) and 255) + (p and 255)) / 8f
        }
        val gw = (width + CELL - 1) / CELL
        val gh = (height + CELL - 1) / CELL
        val (bg, noise) = background(lum, width, height, gw, gh)

        // Peaks: above the sky by a margin, and the highest of their neighbours (on a flat top,
        // the first pixel of it in reading order).
        class Peak(val x: Int, val y: Int, val v: Float, val sky: Float, val noise: Float)
        val peaks = ArrayList<Peak>()
        for (y in RING + 1 until height - RING - 1) {
            val row = y * width
            val gy = min(gh - 1, y / CELL)
            for (x in RING + 1 until width - RING - 1) {
                val v = lum[row + x]
                val g = gy * gw + min(gw - 1, x / CELL)
                if (v < bg[g] + max(5 * noise[g], 8f)) continue
                if (v <= lum[row + x - 1] || v <= lum[row - width + x - 1] || v <= lum[row - width + x] ||
                    v <= lum[row - width + x + 1] || v < lum[row + x + 1] || v < lum[row + width + x - 1] ||
                    v < lum[row + width + x] || v < lum[row + width + x + 1]) continue
                peaks += Peak(x, y, v, skyAt(bg, gw, gh, x, y), noise[g])
            }
        }
        peaks.sortByDescending { it.v - it.sky }

        val out = ArrayList<ImageStar>()
        // One star per few pixels: the brightest peak of a blob wins, its neighbours go.
        val taken = Occupancy(width, height, 5)
        val ring = FloatArray(8 * RING)
        for (p in peaks) {
            if (taken.near(p.x, p.y)) continue
            val half = p.sky + 0.5f * (p.v - p.sky)
            if (area(lum, width, height, p.x, p.y, half) > MAX_AREA) continue
            // The sky right round it: a bump on a lamp's glow or the Moon's is no star.
            var n = 0
            for (d in -RING until RING) {
                ring[n++] = lum[(p.y - RING) * width + p.x + d]
                ring[n++] = lum[(p.y + RING) * width + p.x - d]
                ring[n++] = lum[(p.y - d) * width + p.x - RING]
                ring[n++] = lum[(p.y + d) * width + p.x + RING]
            }
            ring.sort()
            val sky = ring[n / 2]
            if (p.v - sky < max(5 * p.noise, 8f)) continue
            var sum = 0.0
            var sx = 0.0
            var sy = 0.0
            for (dy in -R..R) for (dx in -R..R) {
                val w = lum[(p.y + dy) * width + p.x + dx] - sky
                if (w <= 0f) continue
                sum += w
                sx += w * dx
                sy += w * dy
            }
            if (sum <= 0.0) continue
            taken.add(p.x, p.y)
            out += ImageStar((p.x + sx / sum).toFloat(), (p.y + sy / sum).toFloat(), sum.toFloat())
        }
        out.sortByDescending { it.flux }
        return if (out.size > max) out.subList(0, max).toList() else out
    }

    /** The square ring round a peak the local sky is read from (px out). */
    private const val RING = 6

    /**
     * Per cell the sky's level (the median) and its noise (median less the 16th percentile), each
     * then the median of its 3 x 3 cells, so a bright star or a lamp in a cell does not lift it.
     */
    private fun background(lum: FloatArray, width: Int, height: Int, gw: Int, gh: Int): Pair<FloatArray, FloatArray> {
        val med = FloatArray(gw * gh)
        val sig = FloatArray(gw * gh)
        val hist = IntArray(256)
        for (cy in 0 until gh) for (cx in 0 until gw) {
            hist.fill(0)
            var n = 0
            for (y in cy * CELL until min(height, cy * CELL + CELL)) {
                val row = y * width
                for (x in cx * CELL until min(width, cx * CELL + CELL)) {
                    hist[lum[row + x].toInt().coerceIn(0, 255)]++
                    n++
                }
            }
            val p16 = percentile(hist, n * 0.16)
            val p50 = percentile(hist, n * 0.5)
            med[cy * gw + cx] = p50
            sig[cy * gw + cx] = max(1f, p50 - p16)
        }
        return smooth(med, gw, gh) to smooth(sig, gw, gh)
    }

    private fun percentile(hist: IntArray, rank: Double): Float {
        var acc = 0
        for (v in hist.indices) {
            acc += hist[v]
            if (acc >= rank) return v.toFloat()
        }
        return 255f
    }

    private fun smooth(a: FloatArray, gw: Int, gh: Int): FloatArray {
        val out = FloatArray(a.size)
        val window = FloatArray(9)
        for (y in 0 until gh) for (x in 0 until gw) {
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy
                val xx = x + dx
                if (yy in 0 until gh && xx in 0 until gw) window[n++] = a[yy * gw + xx]
            }
            window.sort(0, n)
            out[y * gw + x] = window[n / 2]
        }
        return out
    }

    /** The sky at a pixel, between the four nearest cell centres. */
    private fun skyAt(bg: FloatArray, gw: Int, gh: Int, x: Int, y: Int): Float {
        val fx = ((x + 0.5f) / CELL - 0.5f).coerceIn(0f, (gw - 1).toFloat())
        val fy = ((y + 0.5f) / CELL - 0.5f).coerceIn(0f, (gh - 1).toFloat())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(gw - 1, x0 + 1)
        val y1 = min(gh - 1, y0 + 1)
        val ax = fx - x0
        val ay = fy - y0
        val top = bg[y0 * gw + x0] * (1 - ax) + bg[y0 * gw + x1] * ax
        val bottom = bg[y1 * gw + x0] * (1 - ax) + bg[y1 * gw + x1] * ax
        return top * (1 - ay) + bottom * ay
    }

    /** Pixels above [level] in the 25 x 25 square around a peak: a star's core is a few dozen. */
    private fun area(lum: FloatArray, width: Int, height: Int, x: Int, y: Int, level: Float): Int {
        var n = 0
        for (yy in max(0, y - 12)..min(height - 1, y + 12)) {
            val row = yy * width
            for (xx in max(0, x - 12)..min(width - 1, x + 12)) if (lum[row + xx] > level) n++
        }
        return n
    }

    /** Which [cell]-px squares already hold a star. */
    private class Occupancy(width: Int, height: Int, private val cell: Int) {
        private val cols = width / cell + 1
        private val rows = height / cell + 1
        private val grid = BooleanArray(cols * rows)

        fun near(x: Int, y: Int): Boolean {
            val c = x / cell
            val r = y / cell
            for (rr in max(0, r - 1)..min(rows - 1, r + 1)) for (cc in max(0, c - 1)..min(cols - 1, c + 1)) {
                if (grid[rr * cols + cc]) return true
            }
            return false
        }

        fun add(x: Int, y: Int) {
            grid[(y / cell) * cols + x / cell] = true
        }
    }
}
