package com.wm.starcam.sky

import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * One entry of the stack's out/stars.json (skystars.h): a star of the reference frame, measured on
 * the linear stack. [x], [y]: pixels of the finished upright picture; [r], [g], [b], [flux]: the
 * aperture's sums, background taken off, in (linear value, white = 1) x pixels; [bg] the luma of
 * its surroundings. An entry the aperture could not measure is all zeros.
 */
class StackStar(
    val x: Double,
    val y: Double,
    val flux: Double,
    val r: Double,
    val g: Double,
    val b: Double,
    val peak: Double,
    val snr: Double,
    val fwhm: Double,
    val bg: Double,
    val seen: Int,
    val tried: Int,
    /** In how many frames the sky had carried it more than 3 px (0: the test could not tell). */
    val moved: Int,
    val sat: Boolean,
    /** It followed the sky's motion through the session; a lit window does not. */
    val sky: Boolean,
    /** The engine's own choice of the stars that mark the sky for the white balance. */
    val pt: Boolean,
) {
    val measured: Boolean get() = peak != 0.0 || flux != 0.0 || bg != 0.0
}

/** A catalogue star and the stack's star it was found on, [distPx] apart. */
class MatchedStar(val catalog: Star, val star: StackStar, val distPx: Double)

/**
 * The stars the engine found in a stacked session (out/stars.json): which of them followed the
 * sky from frame to frame and how bright each is. The plate solver gets these instead of the
 * finished JPEG's blobs, which in a town are mostly lit windows and lamps; the colours and the
 * sky's brightness are measured on them too.
 */
class SkyStars(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val frames: Int,
    /** The median star width the aperture was sized by (RAW px). */
    val fwhm: Double,
    /** The sky's own level per pixel, r, g, b. */
    val skyBg: DoubleArray,
    /** Brightest first. */
    val stars: List<StackStar>,
) {
    /** The sky's level as one luma, (r + 2g + b) / 4: what an entry's [StackStar.bg] is measured in. */
    val skyBgLuma: Double get() = (skyBg[0] + 2 * skyBg[1] + skyBg[2]) / 4

    /**
     * The stars to solve with: those that followed the sky. A session in which the sky hardly
     * moved passes lamps too and may leave too few; then the ones with a star's own width and a
     * quiet neighbourhood (a window is wider and sits in a bright wall), and failing those every
     * entry that was measured. Only light above the background counts: a star of no flux helps
     * nothing.
     */
    fun forSolving(): List<StackStar> {
        val sky = stars.filter { it.sky && it.flux > 0 }
        if (sky.size >= MIN_TO_SOLVE) return sky
        val bgMax = 3 * skyBgLuma
        val shaped = if (fwhm > 0) stars.filter {
            it.flux > 0 && it.fwhm >= 0.6 * fwhm && it.fwhm <= 1.7 * fwhm && it.bg <= bgMax
        } else emptyList()
        if (shaped.size >= MIN_TO_SOLVE) return shaped
        return stars.filter { it.measured && it.flux > 0 }
    }

    /** The "pt" stars as the look's "skyStars": x, y, bg, x, y, bg, ... in this picture's pixels. */
    fun skyPoints(): DoubleArray {
        val pts = stars.filter { it.pt }
        val out = DoubleArray(pts.size * 3)
        for ((i, s) in pts.withIndex()) {
            out[3 * i] = s.x
            out[3 * i + 1] = s.y
            out[3 * i + 2] = s.bg
        }
        return out
    }

    /**
     * The scale from this picture to a [photoWidth] x [photoHeight] one, when that is the same
     * picture at another size (the JPEG the stack was saved as, decoded smaller); null when it
     * cannot be (another shape: turned, cropped).
     */
    fun scaleTo(photoWidth: Int, photoHeight: Int): Double? {
        if (width <= 0 || height <= 0 || photoWidth <= 0 || photoHeight <= 0) return null
        if (photoWidth == width && photoHeight == height) return 1.0
        val s = photoWidth.toDouble() / width
        // One scale for both sides, to the pixel the rounding of a smaller copy allows.
        return s.takeIf { abs(height * s - photoHeight) <= 1.0 + s }
    }

    /** [list] as the plate solver's stars, in a picture [scale] times this one's size. */
    fun imageStars(list: List<StackStar>, scale: Double = 1.0): List<ImageStar> = list.map {
        // Pixel centres at integers in both pictures.
        ImageStar(((it.x + 0.5) * scale - 0.5).toFloat(), ((it.y + 0.5) * scale - 0.5).toFloat(), it.flux.toFloat())
    }

    companion object {
        /** The solver asks for 6 and convinces itself with 10; a dozen is the least worth trying. */
        const val MIN_TO_SOLVE = 12

        fun read(file: File): SkyStars? = runCatching { if (file.isFile) parse(file.readText()) else null }.getOrNull()

        /** out/stars.json's text; null when it is not one. */
        fun parse(json: String): SkyStars? = runCatching {
            val o = JSONObject(json)
            val w = o.getInt("width")
            val h = o.getInt("height")
            if (w <= 0 || h <= 0) return null
            val bg = o.optJSONArray("skyBg")
            val skyBg = DoubleArray(3) { bg?.optDouble(it, 0.0) ?: 0.0 }
            val arr = o.optJSONArray("stars")
            val list = ArrayList<StackStar>(arr?.length() ?: 0)
            if (arr != null) for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val x = s.optDouble("x", Double.NaN)
                val y = s.optDouble("y", Double.NaN)
                if (!x.isFinite() || !y.isFinite()) continue
                fun d(k: String) = s.optDouble(k, 0.0).takeIf { it.isFinite() } ?: 0.0
                list += StackStar(
                    x, y, d("flux"), d("r"), d("g"), d("b"), d("peak"), d("snr"), d("fwhm"), d("bg"),
                    s.optInt("seen"), s.optInt("tried"), s.optInt("moved"),
                    s.optBoolean("sat"), s.optBoolean("sky"), s.optBoolean("pt"),
                )
            }
            SkyStars(w, h, o.optInt("rotation"), o.optInt("frames"), o.optDouble("fwhm", 0.0), skyBg, list)
        }.getOrNull()

        /**
         * The catalogue's stars on [stars] through [sol] (a solution of this [width] x [height]
         * picture): each catalogue star in the frame on the nearest star within max(3 px, 1.5 x
         * the solution's rms), and a star of the picture given to one catalogue star only, the
         * nearest (two catalogue stars on one blob would both read the pair's light).
         */
        fun match(sol: PlateSolution, catalog: StarCatalog, stars: List<StackStar>, width: Int, height: Int): List<MatchedStar> {
            if (stars.isEmpty()) return emptyList()
            val tol = max(3.0, 1.5 * sol.rmsPx)
            val cell = max(8.0, tol)
            val cols = (width / cell).toInt() + 1
            val rows = (height / cell).toInt() + 1
            val grid = HashMap<Int, MutableList<Int>>()
            for ((i, s) in stars.withIndex()) {
                val c = (s.x / cell).toInt().coerceIn(0, cols - 1)
                val r = (s.y / cell).toInt().coerceIn(0, rows - 1)
                grid.getOrPut(r * cols + c) { ArrayList() } += i
            }
            val best = HashMap<Int, MatchedStar>()
            for (cat in catalog.stars) {
                val (px, py) = sol.project(cat.raDeg, cat.decDeg) ?: continue
                if (px < -tol || py < -tol || px > width + tol || py > height + tol) continue
                val c0 = (px / cell).toInt()
                val r0 = (py / cell).toInt()
                var bestI = -1
                var bestD = tol
                for (r in r0 - 1..r0 + 1) for (c in c0 - 1..c0 + 1) {
                    if (r < 0 || c < 0 || r >= rows || c >= cols) continue
                    for (i in grid[r * cols + c] ?: continue) {
                        val d = hypot(stars[i].x - px, stars[i].y - py)
                        if (d <= bestD) {
                            bestD = d
                            bestI = i
                        }
                    }
                }
                if (bestI < 0) continue
                val prev = best[bestI]
                if (prev == null || bestD < prev.distPx) best[bestI] = MatchedStar(cat, stars[bestI], bestD)
            }
            return best.values.sortedByDescending { it.star.flux }
        }
    }
}
