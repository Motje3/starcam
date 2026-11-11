package com.wm.starcam.sky

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Straight lines through measurements that have outliers in them: a star blended with its
 * neighbour, a variable star, a catalogue colour that does not suit this sensor. The answer comes
 * from the bulk of the points and a few wild ones cannot pull it away.
 */
internal object RobustFit {

    /**
     * y = a + s (x - x0), how far the points sit from it ([scatter], a robust sigma), how many it
     * rests on, and how well [a] itself is known ([error], its standard error: the scatter over
     * the root of the points, and for a fitted slope what the slope's own doubt adds at x0 when
     * the points lie to one side of it).
     */
    class Line(val a: Double, val s: Double, val scatter: Double, val used: Int, val error: Double)

    fun median(v: DoubleArray): Double {
        if (v.isEmpty()) return Double.NaN
        val s = v.sortedArray()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else 0.5 * (s[m - 1] + s[m])
    }

    /** The robust sigma: 1.4826 x the median absolute deviation (the sigma of a normal spread). */
    fun sigma(v: DoubleArray): Double {
        if (v.isEmpty()) return Double.NaN
        val m = median(v)
        return 1.4826 * median(DoubleArray(v.size) { abs(v[it] - m) })
    }

    /**
     * Slope and offset, iteratively reweighted least squares with Tukey's biweight, started from
     * Theil-Sen (the median of the slopes between every pair). Null with fewer than 3 points or no
     * spread in x.
     */
    fun line(x: DoubleArray, y: DoubleArray, x0: Double): Line? {
        val n = x.size
        if (n < 3 || y.size != n) return null
        val slopes = ArrayList<Double>(n * (n - 1) / 2)
        for (i in 0 until n) for (j in i + 1 until n) {
            val dx = x[j] - x[i]
            if (abs(dx) > 1e-9) slopes += (y[j] - y[i]) / dx
        }
        if (slopes.isEmpty()) return null
        var s = median(slopes.toDoubleArray())
        var a = median(DoubleArray(n) { y[it] - s * (x[it] - x0) })
        repeat(ITERATIONS) {
            val w = weights(DoubleArray(n) { y[it] - a - s * (x[it] - x0) }) ?: return@repeat
            var sw = 0.0
            var sx = 0.0
            var sy = 0.0
            for (i in 0 until n) {
                sw += w[i]
                sx += w[i] * (x[i] - x0)
                sy += w[i] * y[i]
            }
            if (sw <= 0.0) return@repeat
            val mx = sx / sw
            val my = sy / sw
            var sxx = 0.0
            var sxy = 0.0
            for (i in 0 until n) {
                val dx = x[i] - x0 - mx
                sxx += w[i] * dx * dx
                sxy += w[i] * dx * (y[i] - my)
            }
            // The points left with weight have no spread in x: keep the line as it was.
            if (sxx <= 1e-12) return@repeat
            s = sxy / sxx
            a = my - s * mx
        }
        return finish(x, y, x0, a, s, slopeFitted = true)
    }

    /** The offset alone, the slope held at [s]: the same biweight, started from the median. */
    fun offset(x: DoubleArray, y: DoubleArray, x0: Double, s: Double): Line? {
        val n = x.size
        if (n < 1 || y.size != n) return null
        val d = DoubleArray(n) { y[it] - s * (x[it] - x0) }
        var a = median(d)
        repeat(ITERATIONS) {
            val w = weights(DoubleArray(n) { d[it] - a }) ?: return@repeat
            val sw = w.sum()
            if (sw <= 0.0) return@repeat
            a = (0 until n).sumOf { w[it] * d[it] } / sw
        }
        return finish(x, y, x0, a, s, slopeFitted = false)
    }

    private const val ITERATIONS = 20

    /** Tukey's biweight at 4.685 robust sigmas; null when the residuals have no spread at all. */
    private fun weights(r: DoubleArray): DoubleArray? {
        val sig = sigma(r)
        if (!(sig > 1e-12)) return null
        val c = 4.685 * sig
        return DoubleArray(r.size) {
            val u = r[it] / c
            if (abs(u) >= 1) 0.0 else (1 - u * u) * (1 - u * u)
        }
    }

    private fun finish(x: DoubleArray, y: DoubleArray, x0: Double, a: Double, s: Double, slopeFitted: Boolean): Line {
        val r = DoubleArray(x.size) { y[it] - a - s * (x[it] - x0) }
        val sig = sigma(r)
        // The points the biweight did not throw out.
        val kept = x.indices.filter { !(sig > 1e-12) || abs(r[it]) < 4.685 * sig }
        val n = kept.size
        if (n == 0) return Line(a, s, sig, 0, Double.POSITIVE_INFINITY)
        // Var(a) = sigma^2 (1/n + mean(x - x0)^2 / Sxx) for a fitted line, sigma^2 / n for an offset.
        var lever = 0.0
        if (slopeFitted) {
            val mx = kept.sumOf { x[it] - x0 } / n
            val sxx = kept.sumOf { (x[it] - x0 - mx) * (x[it] - x0 - mx) }
            lever = if (sxx > 1e-12) mx * mx / sxx else Double.POSITIVE_INFINITY
        }
        return Line(a, s, sig, n, sig * sqrt(1.0 / n + lever))
    }
}
