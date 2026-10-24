package com.wm.starcam.sky

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A star found in a photo: pixel position (y down) and brightness. */
class ImageStar(val x: Float, val y: Float, val flux: Float)

/**
 * Where a photo points and how it lies: the photo's centre on the sky, and a similarity from the
 * tangent plane there to the photo's pixels, with the lens's barrel or pincushion [k] on top.
 * [project] puts any J2000 place on the photo.
 */
class PlateSolution(
    val raDeg: Double,
    val decDeg: Double,
    /** Pixels per radian of the tangent plane (the focal length in pixels). */
    val scale: Double,
    /** Turn of the tangent plane on the photo (rad). */
    val angle: Double,
    /** Whether the tangent plane's east is mirrored (it never is for a camera; tried anyway). */
    val flipped: Boolean,
    val cx: Double,
    val cy: Double,
    val matched: Int,
    val rmsPx: Double,
    /** Radial distortion: a place r from the centre on the plane lands at r (1 + k r^2). */
    val k: Double = 0.0,
    /** How far out (r^2 on the plane) [k] was measured; beyond, the distortion is held there. */
    val kReach2: Double = Double.MAX_VALUE,
) {
    /** Pixel position of a J2000 place, or null when it lies behind the tangent point. */
    fun project(raDeg: Double, decDeg: Double): Pair<Double, Double>? {
        val (xi, eta) = PlateSolver.tangent(raDeg, decDeg, this.raDeg, this.decDeg) ?: return null
        return PlateSolver.toPixel(xi, eta, scale, angle, flipped, cx, cy, k, kReach2)
    }

    /** The field of view across [width] pixels (deg). */
    fun fovDeg(width: Int): Double = Math.toDegrees(2 * atan(width / 2.0 / scale))
}

/**
 * Plate solving: which part of the sky a photo shows, from the pattern of its stars.
 *
 * The photo's brightest stars become directions out of the camera (the focal length turns pixels
 * into angles). Any two of them, and a pair of bright catalogue stars the same angle apart, fix a
 * rotation from the sky to the camera; the rotation that puts the most bright catalogue stars on
 * the photo's stars wins. Least squares on every catalogue star in the field then finish it: the
 * exact centre, turn and scale, and the lens's distortion. Without any idea where the camera
 * pointed, all of the sky is searched: about a second.
 */
object PlateSolver {

    /** Gnomonic projection of a place around a tangent point (rad on the plane), null behind it. */
    fun tangent(raDeg: Double, decDeg: Double, ra0Deg: Double, dec0Deg: Double): Pair<Double, Double>? {
        val ra = Math.toRadians(raDeg)
        val dec = Math.toRadians(decDeg)
        val ra0 = Math.toRadians(ra0Deg)
        val dec0 = Math.toRadians(dec0Deg)
        val cosC = sin(dec0) * sin(dec) + cos(dec0) * cos(dec) * cos(ra - ra0)
        if (cosC < 0.2) return null  // more than ~78 deg away: no lens sees that far off-axis
        val xi = cos(dec) * sin(ra - ra0) / cosC
        val eta = (cos(dec0) * sin(dec) - sin(dec0) * cos(dec) * cos(ra - ra0)) / cosC
        return xi to eta
    }

    /** The place at plane coordinates [xi], [eta] around a tangent point (deg). */
    fun untangent(xi: Double, eta: Double, ra0Deg: Double, dec0Deg: Double): Pair<Double, Double> {
        val ra0 = Math.toRadians(ra0Deg)
        val dec0 = Math.toRadians(dec0Deg)
        val rho = hypot(xi, eta)
        val c = atan(rho)
        val dec = if (rho == 0.0) dec0 else asin(cos(c) * sin(dec0) + eta * sin(c) * cos(dec0) / rho)
        val ra = ra0 + atan2(xi * sin(c), rho * cos(dec0) * cos(c) - eta * sin(dec0) * sin(c))
        return SkyMath.norm360(Math.toDegrees(ra)) to Math.toDegrees(dec)
    }

    /**
     * Plane to pixel: east to the left and north up when [angle] is 0 (a camera seen from inside
     * the sky), turned by [angle], at [scale] px/rad around ([cx], [cy]), distorted by [k].
     */
    fun toPixel(
        xi: Double, eta: Double, scale: Double, angle: Double, flipped: Boolean, cx: Double, cy: Double, k: Double = 0.0,
        kReach2: Double = Double.MAX_VALUE,
    ): Pair<Double, Double> {
        val d = 1 + k * min(xi * xi + eta * eta, kReach2)
        val u = (if (flipped) xi else -xi) * d
        val v = -eta * d
        val c = cos(angle)
        val s = sin(angle)
        return (cx + scale * (c * u - s * v)) to (cy + scale * (s * u + c * v))
    }

    private class Point(val x: Double, val y: Double, val mag: Double = 0.0)

    /**
     * Solves [stars] (photo pixels; any order) of a [width] x [height] photo. [focalPx]: the focal
     * length in pixels as far as it is known, [scaleTolerance] how far it may be off. [hint]: the
     * centre's rough place (ra, dec deg) from the phone's sensors, or null to search the whole
     * sky. A mirrored photo is only looked for when a normal one is not found. Returns null when
     * nothing convincing is found.
     */
    fun solve(
        stars: List<ImageStar>,
        width: Int,
        height: Int,
        catalog: StarCatalog,
        focalPx: Double,
        scaleTolerance: Double = 0.06,
        hint: Pair<Double, Double>? = null,
        hintRadiusDeg: Double = 30.0,
        tryMirrored: Boolean = true,
    ): PlateSolution? {
        if (stars.size < 6) return null
        val search = Search(stars, width, height, catalog, focalPx, scaleTolerance, hint, hintRadiusDeg)
        return search.run(false) ?: if (tryMirrored) search.run(true) else null
    }

    /** How many of the photo's brightest stars the rotations are built from... */
    private const val PAIRS_FROM = 18

    /** ...and how many of them each rotation is scored against (a bit mask holds them). */
    private const val SCORED = 48

    /** The best rotations kept for the full check against every star. */
    private const val KEEP = 12

    private class Hypothesis(val m: DoubleArray, val flipped: Boolean, val focal: Double, val matched: Int)

    private class Search(
        val stars: List<ImageStar>, val width: Int, val height: Int, val catalog: StarCatalog,
        val focalPx: Double, val scaleTolerance: Double, hint: Pair<Double, Double>?, hintRadiusDeg: Double,
    ) {
        val cx = width / 2.0
        val cy = height / 2.0
        val halfDiagPx = hypot(cx, cy)
        val sorted = stars.sortedByDescending { it.flux }
        val bright = sorted.take(PAIRS_FROM)
        val tolPx = max(4.0, 0.012 * halfDiagPx)
        val scored = PhotoGrid(sorted.take(SCORED), tolPx, width, height)
        val halfField = atan(halfDiagPx / (focalPx * (1 - scaleTolerance)))
        val index = pairIndex(catalog, starsFor(width, height, focalPx), 2 * halfField)
        val hintVec = hint?.let { unit(it.first, it.second) }
        val cosHint = cos(Math.toRadians(hintRadiusDeg))

        fun run(flip: Boolean): PlateSolution? {
            val kept = ArrayList<Hypothesis>()
            // Pairs closer than this fix the turn too loosely to be worth trying.
            val minPairPx = 0.24 * halfDiagPx
            for (i in bright.indices) for (j in i + 1 until bright.size) {
                val a = bright[i]
                val b = bright[j]
                if (hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) < minPairPx) continue
                val sepLo = separation(a, b, cx, cy, focalPx * (1 + scaleTolerance))
                val sepHi = separation(a, b, cx, cy, focalPx * (1 - scaleTolerance))
                var p = lowerBound(index.seps, sepLo * 0.995)
                while (p < index.seps.size && index.seps[p] <= sepHi * 1.005) {
                    val f = focalFor(a, b, cx, cy, focalPx, index.seps[p])
                    val pa = index.a[p]
                    val pb = index.b[p]
                    p++
                    if (abs(f / focalPx - 1) > scaleTolerance) continue
                    val da = direction(a, cx, cy, f, flip)
                    val db = direction(b, cx, cy, f, flip)
                    for (order in 0..1) {
                        val m = if (order == 0) triad(index.vec[pa], index.vec[pb], da, db)
                        else triad(index.vec[pb], index.vec[pa], da, db)
                        // The camera's axis on the sky: m maps sky to camera, so its last row.
                        if (hintVec != null && m[6] * hintVec[0] + m[7] * hintVec[1] + m[8] * hintVec[2] < cosHint) continue
                        val (n, inFrame) = countMatches(m, f, flip)
                        if (n < 5 || (kept.size == KEEP && n <= kept.last().matched)) continue
                        val h = Hypothesis(m, flip, f, n)
                        // A clear win - most of the bright stars the field should show are there - is
                        // checked at once; it nearly always holds.
                        if (n >= 10 && n >= 0.6 * inFrame) verify(h)?.let { return it }
                        keep(kept, h)
                    }
                }
            }
            return kept.mapNotNull { verify(it) }.maxWithOrNull(compareBy({ it.matched }, { -it.rmsPx }))
        }

        /** Into the kept list, best first, with a near twin (same axis within 2 deg) replaced. */
        private fun keep(kept: MutableList<Hypothesis>, h: Hypothesis) {
            val twin = kept.indexOfFirst { axisDot(it.m, h.m) > COS_TWIN }
            if (twin >= 0) {
                if (kept[twin].matched >= h.matched) return
                kept.removeAt(twin)
            }
            val at = kept.indexOfFirst { it.matched < h.matched }.let { if (it < 0) kept.size else it }
            kept.add(at, h)
            if (kept.size > KEEP) kept.removeAt(kept.size - 1)
        }

        private fun axisDot(a: DoubleArray, b: DoubleArray) = a[6] * b[6] + a[7] * b[7] + a[8] * b[8]

        /**
         * The rotation's bright catalogue stars on the photo's stars: how many land on one, and how
         * many land in the frame at all.
         */
        private fun countMatches(m: DoubleArray, f: Double, flip: Boolean): Pair<Int, Int> {
            val cosField = cos(halfField)
            var used = 0L
            var n = 0
            var inFrame = 0
            for (k in index.near(m[6], m[7], m[8])) {
                val s = index.vec[k]
                val dz = m[6] * s[0] + m[7] * s[1] + m[8] * s[2]
                if (dz < cosField) continue
                val dx = m[0] * s[0] + m[1] * s[1] + m[2] * s[2]
                val dy = m[3] * s[0] + m[4] * s[1] + m[5] * s[2]
                val x = cx + f * (if (flip) -dx else dx) / dz
                val y = cy + f * dy / dz
                if (x < 0 || y < 0 || x >= width || y >= height) continue
                inFrame++
                val j = scored.find(x, y, used)
                if (j >= 0) {
                    used = used or (1L shl j)
                    n++
                }
            }
            return n to inFrame
        }

        /** The full check: refined against every star in the photo and the catalogue. */
        private fun verify(h: Hypothesis): PlateSolution? {
            var sol = toSolution(h, cx, cy)
            val diag = 2 * halfDiagPx
            var tol = 0.006 * diag
            repeat(4) {
                sol = refine(sol, stars, catalog, width, height, tol) ?: return null
                tol = max(0.002 * diag, min(tol, 4 * sol.rmsPx))
            }
            return sol.takeIf { convincing(it, stars.size, width, height, tol) }
        }
    }

    private val COS_TWIN = cos(Math.toRadians(2.0))

    /** Up to 64 photo stars in cells [tol] px wide, to find the one near a pixel quickly. */
    private class PhotoGrid(val stars: List<ImageStar>, val tol: Double, width: Int, height: Int) {
        private val cols = (width / tol).toInt() + 1
        private val rows = (height / tol).toInt() + 1
        private val head = IntArray(cols * rows) { -1 }
        private val next = IntArray(stars.size)

        init {
            for ((i, s) in stars.withIndex()) {
                val c = (s.y / tol).toInt().coerceIn(0, rows - 1) * cols + (s.x / tol).toInt().coerceIn(0, cols - 1)
                next[i] = head[c]
                head[c] = i
            }
        }

        /** A star within [tol] of (x, y) on both axes that [used] does not have, or -1. */
        fun find(x: Double, y: Double, used: Long): Int {
            val c0 = (x / tol).toInt()
            val r0 = (y / tol).toInt()
            for (r in max(0, r0 - 1)..min(rows - 1, r0 + 1)) for (c in max(0, c0 - 1)..min(cols - 1, c0 + 1)) {
                var i = head[r * cols + c]
                while (i >= 0) {
                    val s = stars[i]
                    if (used and (1L shl i) == 0L && abs(s.x - x) < tol && abs(s.y - y) < tol) return i
                    i = next[i]
                }
            }
            return -1
        }
    }

    /**
     * Enough matches, close enough, and many more than chance would give: with a few hundred
     * photo stars and a hundred catalogue stars some always fall near each other.
     */
    private fun convincing(sol: PlateSolution, photoStars: Int, width: Int, height: Int, tol: Double): Boolean {
        val chance = 120.0 * min(photoStars, 300) * Math.PI * tol * tol / (width.toDouble() * height)
        return sol.matched >= 10 && sol.matched >= 3 * chance + 6 && sol.rmsPx < 0.4 * tol
    }

    /**
     * Bright stars, their unit vectors and every pair among them by angular distance; and, for
     * cells of the sky 4 deg across, the stars a field centred in the cell can hold.
     */
    private class PairIndex(
        val vec: List<DoubleArray>, val seps: DoubleArray, val a: IntArray, val b: IntArray, halfField: Double,
    ) {
        private val bands = 45
        private val bandRad = Math.PI / bands
        private val bandStart = IntArray(bands)
        private val bandCells = IntArray(bands)
        private val cells: Array<IntArray>

        init {
            var total = 0
            for (band in 0 until bands) {
                val dec = -Math.PI / 2 + (band + 0.5) * bandRad
                bandCells[band] = max(1, (2 * Math.PI * cos(dec) / bandRad).roundToInt())
                bandStart[band] = total
                total += bandCells[band]
            }
            // A cell reaches at most ~5 deg from its centre; 6 to be sure.
            val cosReach = cos(halfField + Math.toRadians(6.0))
            cells = Array(total) { IntArray(0) }
            for (band in 0 until bands) {
                val dec = -Math.PI / 2 + (band + 0.5) * bandRad
                for (c in 0 until bandCells[band]) {
                    val ra = 2 * Math.PI * (c + 0.5) / bandCells[band]
                    val centre = doubleArrayOf(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec))
                    cells[bandStart[band] + c] = vec.indices.filter { dot(vec[it], centre) >= cosReach }.toIntArray()
                }
            }
        }

        /** The stars a field around the axis (x, y, z) can hold. */
        fun near(x: Double, y: Double, z: Double): IntArray {
            val dec = asin(z.coerceIn(-1.0, 1.0))
            var ra = atan2(y, x)
            if (ra < 0) ra += 2 * Math.PI
            val band = ((dec + Math.PI / 2) / bandRad).toInt().coerceIn(0, bands - 1)
            val c = (ra / (2 * Math.PI) * bandCells[band]).toInt().coerceIn(0, bandCells[band] - 1)
            return cells[bandStart[band] + c]
        }
    }

    @Volatile private var cachedIndex: Triple<StarCatalog, String, PairIndex>? = null

    /**
     * How many of the brightest catalogue stars to pair up: about twenty in the photo's field, so
     * a wide field pairs the brightest few hundred, a narrow one goes fainter.
     */
    private fun starsFor(width: Int, height: Int, focalPx: Double): Int {
        val area = 4 * atan(width / 2.0 / focalPx) * atan(height / 2.0 / focalPx)
        return (22 * 4 * Math.PI / area).roundToInt().coerceIn(150, 600)
    }

    private fun pairIndex(catalog: StarCatalog, count: Int, maxSep: Double): PairIndex {
        // Rounded so the next photo from the same camera finds the index already made.
        val maxDeg = (Math.toDegrees(maxSep) / 5).toInt() * 5 + 5
        val key = "$count/$maxDeg"
        cachedIndex?.let { if (it.first === catalog && it.second == key) return it.third }
        val bright = catalog.stars.sortedBy { it.mag }.take(count)
        val vec = bright.map { unit(it.raDeg, it.decDeg) }
        val cosMax = cos(Math.toRadians(maxDeg.toDouble()))
        val list = ArrayList<Triple<Double, Int, Int>>()
        for (i in vec.indices) for (j in i + 1 until vec.size) {
            if (dot(vec[i], vec[j]) >= cosMax) list += Triple(angle(vec[i], vec[j]), i, j)
        }
        list.sortBy { it.first }
        val idx = PairIndex(vec, DoubleArray(list.size) { list[it].first }, IntArray(list.size) { list[it].second },
            IntArray(list.size) { list[it].third }, Math.toRadians(maxDeg / 2.0))
        cachedIndex = Triple(catalog, key, idx)
        return idx
    }

    private fun unit(raDeg: Double, decDeg: Double): DoubleArray {
        val ra = Math.toRadians(raDeg)
        val dec = Math.toRadians(decDeg)
        return doubleArrayOf(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec))
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun normalize(a: DoubleArray): DoubleArray {
        val n = sqrt(dot(a, a))
        return doubleArrayOf(a[0] / n, a[1] / n, a[2] / n)
    }

    private fun angle(a: DoubleArray, b: DoubleArray): Double {
        val c = cross(a, b)
        return atan2(sqrt(dot(c, c)), dot(a, b))
    }

    /** A photo star as a direction in the camera's frame: x right, y down, z out of the lens. */
    private fun direction(s: ImageStar, cx: Double, cy: Double, f: Double, flip: Boolean): DoubleArray {
        val u = (s.x - cx) / f
        return normalize(doubleArrayOf(if (flip) -u else u, (s.y - cy) / f, 1.0))
    }

    /** The angle between two photo stars at focal length [f]. */
    private fun separation(a: ImageStar, b: ImageStar, cx: Double, cy: Double, f: Double): Double =
        angle(direction(a, cx, cy, f, false), direction(b, cx, cy, f, false))

    /** The focal length that puts photo stars [a] and [b] [sep] apart, from [f0]. */
    private fun focalFor(a: ImageStar, b: ImageStar, cx: Double, cy: Double, f0: Double, sep: Double): Double {
        var f = f0
        repeat(3) { f *= separation(a, b, cx, cy, f) / sep }
        return f
    }

    /**
     * TRIAD: the rotation (row-major) taking sky directions [s1], [s2] onto camera directions
     * [d1], [d2], the first exactly and the second as near as the angles allow.
     */
    private fun triad(s1: DoubleArray, s2: DoubleArray, d1: DoubleArray, d2: DoubleArray): DoubleArray {
        val t2 = normalize(cross(s1, s2))
        val t3 = cross(s1, t2)
        val r2 = normalize(cross(d1, d2))
        val r3 = cross(d1, r2)
        // m = [r1 r2 r3] [t1 t2 t3]^T
        val m = DoubleArray(9)
        for (row in 0..2) for (col in 0..2) {
            m[row * 3 + col] = d1[row] * s1[col] + r2[row] * t2[col] + r3[row] * t3[col]
        }
        return m
    }

    /** A rotation as a [PlateSolution]: the camera axis's place, and the turn of east on the photo. */
    private fun toSolution(h: Hypothesis, cx: Double, cy: Double): PlateSolution {
        val m = h.m
        val ra0 = SkyMath.norm360(Math.toDegrees(atan2(m[7], m[6])))
        val dec0 = Math.toDegrees(asin(m[8].coerceIn(-1.0, 1.0)))
        // East at the axis, carried into the camera's frame: its x, y are its direction on the photo.
        val r = Math.toRadians(ra0)
        var ex = -m[0] * sin(r) + m[1] * cos(r)
        val ey = -m[3] * sin(r) + m[4] * cos(r)
        if (h.flipped) ex = -ex
        // toPixel sends east along -(cos a, sin a), or +(cos a, sin a) when mirrored.
        val angle = if (h.flipped) atan2(ey, ex) else atan2(-ey, -ex)
        return PlateSolution(ra0, dec0, h.focal, angle, h.flipped, cx, cy, h.matched, 0.0)
    }

    /** The catalogue's brightest [max] stars within [radiusDeg] of the centre, on its tangent plane. */
    private fun catalogOnPlane(catalog: StarCatalog, ra0: Double, dec0: Double, radiusDeg: Double, max: Int): List<Point> {
        val cosR = cos(Math.toRadians(radiusDeg))
        val d0 = Math.toRadians(dec0)
        val out = ArrayList<Point>()
        for (s in catalog.stars) {
            val d = Math.toRadians(s.decDeg)
            val c = sin(d0) * sin(d) + cos(d0) * cos(d) * cos(Math.toRadians(s.raDeg - ra0))
            if (c < cosR) continue
            val (xi, eta) = tangent(s.raDeg, s.decDeg, ra0, dec0) ?: continue
            out += Point(xi, eta, s.mag.toDouble())
        }
        return out.sortedBy { it.mag }.take(max)
    }

    private fun lowerBound(a: DoubleArray, v: Double): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (a[m] < v) lo = m + 1 else hi = m
        }
        return lo
    }

    /**
     * One round of refinement: the photo's centre as a new tangent point, every catalogue star in
     * the field predicted and matched to the nearest photo star within [tol] px, and least squares
     * for the centre, turn and scale, and the distortion once enough stars reach the corners.
     */
    private fun refine(
        sol: PlateSolution, stars: List<ImageStar>, catalog: StarCatalog, width: Int, height: Int, tol: Double,
    ): PlateSolution? {
        val mid = pixelToPlane(width / 2.0, height / 2.0, sol)
        val (ra0, dec0) = untangent(mid.first, mid.second, sol.raDeg, sol.decDeg)
        val halfDiag = atan(hypot(width / 2.0, height / 2.0) / sol.scale)
        val cat = catalogOnPlane(catalog, ra0, dec0, Math.toDegrees(halfDiag) * 1.05, 400)
        val photo = stars.sortedByDescending { it.flux }.take(300)
        val src = ArrayList<Point>()
        val dst = ArrayList<Point>()
        val used = BooleanArray(photo.size)
        for (p in cat) {
            val (ra, dec) = untangent(p.x, p.y, ra0, dec0)
            val (x, y) = sol.project(ra, dec) ?: continue
            if (x < 0 || y < 0 || x > width || y > height) continue
            var bestJ = -1
            var bestD = tol
            for ((j, s) in photo.withIndex()) {
                if (used[j]) continue
                val d = hypot(s.x - x, s.y - y)
                if (d < bestD) { bestD = d; bestJ = j }
            }
            if (bestJ >= 0) {
                used[bestJ] = true
                src += p
                dst += Point(photo[bestJ].x.toDouble(), photo[bestJ].y.toDouble())
            }
        }
        if (src.size < 6) return null
        // (u, v): the plane in the photo's orientation convention, before the turn.
        val us = src.map { if (sol.flipped) it.x else -it.x }
        val vs = src.map { -it.y }
        val r2 = src.map { it.x * it.x + it.y * it.y }
        // The distortion only from stars spread to the field's edge, and never more than 8 %.
        val reach = r2.maxOrNull() ?: 0.0
        val k = if (src.size >= 25 && reach >= 0.35 * halfDiag * halfDiag) {
            radialTerm(us, vs, r2, dst)?.takeIf { abs(it) * reach < 0.08 } ?: sol.k
        } else {
            0.0
        }
        val (a, b, ccx, ccy) = similarity(us.indices.map { us[it] * (1 + k * r2[it]) },
            vs.indices.map { vs[it] * (1 + k * r2[it]) }, dst)
        val scale = hypot(a, b)
        val angle = atan2(b, a)
        var sum2 = 0.0
        for (i in src.indices) {
            val (x, y) = toPixel(src[i].x, src[i].y, scale, angle, sol.flipped, ccx, ccy, k)
            sum2 += (x - dst[i].x) * (x - dst[i].x) + (y - dst[i].y) * (y - dst[i].y)
        }
        val corner = 1.1 * hypot(width / 2.0, height / 2.0) / scale
        return PlateSolution(ra0, dec0, scale, angle, sol.flipped, ccx, ccy, src.size, sqrt(sum2 / src.size), k,
            corner * corner)
    }

    /**
     * Least squares for x = cx + a u - b v, y = cy + b u + a v: returns (a, b, cx, cy).
     */
    private fun similarity(us: List<Double>, vs: List<Double>, dst: List<Point>): List<Double> {
        val mu = us.average()
        val mv = vs.average()
        val mx = dst.sumOf { it.x } / dst.size
        val my = dst.sumOf { it.y } / dst.size
        var sa = 0.0
        var sb = 0.0
        var sd = 0.0
        for (i in us.indices) {
            val u = us[i] - mu
            val v = vs[i] - mv
            val x = dst[i].x - mx
            val y = dst[i].y - my
            sa += u * x + v * y
            sb += u * y - v * x
            sd += u * u + v * v
        }
        val a = sa / sd
        val b = sb / sd
        // The centre's pixel, where u = v = 0.
        return listOf(a, b, mx - a * mu + b * mv, my - b * mu - a * mv)
    }

    /**
     * The radial distortion: least squares for z = z0 + A w + C |w|^2 w (complex, w = u + iv),
     * and k the part of C / A that stretches along the radius.
     */
    private fun radialTerm(us: List<Double>, vs: List<Double>, r2: List<Double>, dst: List<Point>): Double? {
        // Unknowns cx, cy, a, b, c, d; rows x: [1 0 u -v r2u -r2v], y: [0 1 v u r2v r2u].
        val n = 6
        val ata = Array(n) { DoubleArray(n) }
        val atb = DoubleArray(n)
        fun add(row: DoubleArray, value: Double) {
            for (i in 0 until n) {
                atb[i] += row[i] * value
                for (j in 0 until n) ata[i][j] += row[i] * row[j]
            }
        }
        for (i in us.indices) {
            val u = us[i]
            val v = vs[i]
            val q = r2[i]
            add(doubleArrayOf(1.0, 0.0, u, -v, q * u, -q * v), dst[i].x)
            add(doubleArrayOf(0.0, 1.0, v, u, q * v, q * u), dst[i].y)
        }
        val x = solveLinear(ata, atb) ?: return null
        val a = x[2]
        val b = x[3]
        return (x[4] * a + x[5] * b) / (a * a + b * b)
    }

    /** Gaussian elimination with partial pivoting; null when singular. */
    private fun solveLinear(m: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val n = rhs.size
        val a = Array(n) { m[it].copyOf() }
        val b = rhs.copyOf()
        for (col in 0 until n) {
            val piv = (col until n).maxByOrNull { abs(a[it][col]) } ?: return null
            if (abs(a[piv][col]) < 1e-12) return null
            a[col] = a[piv].also { a[piv] = a[col] }
            b[col] = b[piv].also { b[piv] = b[col] }
            for (r in col + 1 until n) {
                val f = a[r][col] / a[col][col]
                for (c in col until n) a[r][c] -= f * a[col][c]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (c in r + 1 until n) s -= a[r][c] * x[c]
            x[r] = s / a[r][r]
        }
        return x
    }

    /** Pixel back to the tangent plane of [sol]. */
    fun pixelToPlane(x: Double, y: Double, sol: PlateSolution): Pair<Double, Double> {
        val c = cos(sol.angle)
        val s = sin(sol.angle)
        val dx = (x - sol.cx) / sol.scale
        val dy = (y - sol.cy) / sol.scale
        // Inverse turn, then the distortion undone a few times over (it depends on the answer).
        val ud = c * dx + s * dy
        val vd = -s * dx + c * dy
        var u = ud
        var v = vd
        repeat(4) {
            val d = 1 + sol.k * min(u * u + v * v, sol.kReach2)
            u = ud / d
            v = vd / d
        }
        val xi = if (sol.flipped) u else -u
        return xi to -v
    }

    /** The J2000 place at a pixel of a solved photo. */
    fun pixelToSky(x: Double, y: Double, sol: PlateSolution): Pair<Double, Double> {
        val (xi, eta) = pixelToPlane(x, y, sol)
        return untangent(xi, eta, sol.raDeg, sol.decDeg)
    }
}
