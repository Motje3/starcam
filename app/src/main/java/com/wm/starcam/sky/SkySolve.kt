package com.wm.starcam.sky

import kotlin.math.max
import kotlin.math.tan

/**
 * The plate solver run as "What's in this photo" and the stack's own solve run it: the focal
 * lengths worth trying, one after another, until one solves or the time is up.
 */
object SkySolve {

    /**
     * Focal lengths to try (px at a [width] x [height] picture) and how sure each is: the
     * camera's own ([focalRawPx] at a RAW frame [rawLong] px on its long side), kept with the
     * session since 0.5; for older sessions the fields a phone's main camera has.
     */
    fun focalGuesses(focalRawPx: Double?, rawLong: Int, width: Int, height: Int): List<Pair<Double, Double>> {
        val long = max(width, height).toDouble()
        // The camera's own: nothing else to try (a photo it does not solve has no sky in it to find).
        if (focalRawPx != null && focalRawPx > 0 && rawLong > 0) return listOf(focalRawPx * long / rawLong to 0.05)
        // Across the long side: a main camera's ~74 deg, then the neighbours either way.
        return listOf(74.0, 62.0, 86.0, 50.0).map { fov -> long / 2 / tan(Math.toRadians(fov / 2)) to 0.13 }
    }

    /**
     * What a run of guesses gave: the solution or null, the guesses tried ("2795±5%"), and
     * whether it ended on the deadline with nothing found (it may not have looked everywhere).
     */
    class Outcome(val solution: PlateSolution?, val tried: String, val outOfTime: Boolean = false)

    /**
     * [stars] of a [width] x [height] picture solved with each of [guesses] in turn. [mirrored]:
     * whether the first guess also looks for a mirrored picture (a camera never makes one; the
     * later guesses never do). A guess is not started once [giveUpMs] have passed since [t0Ms];
     * [deadlineMs] (a System.currentTimeMillis time) ends even a guess under way, for a caller
     * that cannot wait (the photo being stacked does).
     */
    fun solve(
        stars: List<ImageStar>,
        width: Int,
        height: Int,
        catalog: StarCatalog,
        guesses: List<Pair<Double, Double>>,
        mirrored: Boolean,
        giveUpMs: Long,
        t0Ms: Long = System.currentTimeMillis(),
        deadlineMs: Long = Long.MAX_VALUE,
    ): Outcome {
        var tried = ""
        for ((i, guess) in guesses.withIndex()) {
            val now = System.currentTimeMillis()
            if (now > deadlineMs || (i > 0 && now - t0Ms > giveUpMs)) break
            val (focal, tolerance) = guess
            tried += String.format(java.util.Locale.US, " %.0f±%.0f%%", focal, tolerance * 100)
            val sol = PlateSolver.solve(stars, width, height, catalog, focal, tolerance,
                tryMirrored = mirrored && i == 0, deadlineMs = deadlineMs)
            if (sol != null) return Outcome(sol, tried)
        }
        return Outcome(null, tried, outOfTime = System.currentTimeMillis() > deadlineMs)
    }
}
