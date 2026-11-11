package com.wm.starcam.sky

/**
 * The stack's stars put to work, between the stack and the look: the photo solved from the stars
 * that followed the sky, the catalogue's stars found on them, the white balance their colours
 * give and the sky's brightness their light gives. Pure: the hook around it (StackCallback) adds
 * the remembered balance and the files.
 */
object StackSky {

    class Measured(
        val sky: SkyStars,
        /** Stars given to the solver. */
        val solving: Int,
        val solution: PlateSolution?,
        /** The focal lengths tried, for the log. */
        val tried: String,
        /** No solution, and the search was cut short by its deadline. */
        val outOfTime: Boolean,
        val matched: List<MatchedStar>,
        val colour: StarColour.Fit?,
        val photometry: SkyPhotometry.Result?,
        val solveMs: Long,
    )

    /**
     * [sky] solved at its own size ([focalRawPx] the camera's focal length at a RAW frame [rawLong]
     * px on its long side, or null to try a phone's usual fields; no mirrored search), and when it
     * solves, matched, its colour fitted and its sky measured. A guess is not started after
     * [giveUpMs], and the search ends at [deadlineMs] (a System.currentTimeMillis time) whatever
     * it is doing: the photo waits for this.
     */
    fun measure(
        sky: SkyStars, catalog: StarCatalog, focalRawPx: Double?, rawLong: Int, giveUpMs: Long,
        deadlineMs: Long = Long.MAX_VALUE,
    ): Measured {
        val t0 = System.currentTimeMillis()
        val solving = sky.forSolving()
        val guesses = SkySolve.focalGuesses(focalRawPx, rawLong, sky.width, sky.height)
        val out = SkySolve.solve(sky.imageStars(solving), sky.width, sky.height, catalog, guesses,
            mirrored = false, giveUpMs = giveUpMs, t0Ms = t0, deadlineMs = deadlineMs)
        val ms = System.currentTimeMillis() - t0
        val sol = out.solution
            ?: return Measured(sky, solving.size, null, out.tried, out.outOfTime, emptyList(), null, null, ms)
        val matched = SkyStars.match(sol, catalog, solving, sky.width, sky.height)
        return Measured(sky, solving.size, sol, out.tried, false, matched,
            StarColour.fit(StarColour.samples(matched)), SkyPhotometry.measure(matched, sky, sol.scale), ms)
    }

    /** The look applies the stars' gains only with this many sky points in the picture (post2.cpp). */
    const val MIN_SKY_POINTS = 10

    /**
     * The white balance the photo is developed with: the stars' own when they gave one ([fit]),
     * else what this camera's earlier sessions measured ([remembered]: the sensor's own balance,
     * turned into gains for frames shot at the camera's [cameraR], [cameraB]). Null for none -
     * and none whatever the stars said when fewer than [MIN_SKY_POINTS] stars mark the sky
     * ([skyPoints], out/stars.json's "pt"): the look knows the sky from a lit landscape by them
     * and applies nothing without (a session whose sky hardly moved has none).
     */
    fun balance(
        fit: StarColour.Fit?, remembered: Pair<Double, Double>?, cameraR: Double, cameraB: Double, skyPoints: Int,
    ): SkyRecord.Colour? {
        if (skyPoints < MIN_SKY_POINTS) return null
        if (fit != null) {
            return SkyRecord.Colour(fit.wbR, fit.wbB, fit.stars, fit.scatterR, fit.scatterB, SkyRecord.SOURCE_STARS)
        }
        if (remembered == null || !(cameraR > 0) || !(cameraB > 0)) return null
        val (r, b) = StarColour.fromRemembered(remembered.first, remembered.second, cameraR, cameraB)
        return SkyRecord.Colour(r, b, 0, null, null, SkyRecord.SOURCE_REMEMBERED)
    }

    /** What [m] found as meta/sky.json, with the [colour] the photo was developed with (or none). */
    fun record(m: Measured, colour: SkyRecord.Colour?): SkyRecord = SkyRecord(
        solution = m.solution,
        width = m.sky.width,
        height = m.sky.height,
        stars = m.solving,
        matched = m.matched.size,
        colour = colour,
        sky = m.photometry?.let { SkyRecord.Sky(it.zp, it.magArcsec2, it.bortle, it.stars) },
    )
}
