package com.wm.starcam.camera

/**
 * Watches the frames as they come in and says when the sky stops cooperating: far fewer stars
 * than the first frames had (cloud or haze passing), or stars growing soft (dew on the lens, the
 * focus drifting). It only warns - the stacker already leaves such frames out - so the
 * photographer can wipe the lens or wait instead of finding out at the end. Two frames in a row
 * raise a warning and two good ones clear it, so a single odd frame says nothing.
 */
class SkyWatch {
    private val refStars = ArrayList<Int>()
    private val refFwhm = ArrayList<Float>()
    private var cloudyRun = 0
    private var softRun = 0
    private var goodRun = 0

    /** The warning to show, null while all is well. */
    var note: String? = null
        private set

    /** Feeds one kept (not bumped) frame's stars and FWHM; returns [note]. */
    fun add(starCount: Int, fwhm: Float): String? {
        if (refStars.size < REFERENCE_FRAMES) {
            // The first frames with a real star field set the bar.
            if (starCount >= MIN_REFERENCE_STARS) {
                refStars += starCount
                if (fwhm > 0f) refFwhm += fwhm
            }
            return note
        }
        val stars = refStars.average()
        val soft = refFwhm.isNotEmpty() && fwhm > SOFT_RATIO * refFwhm.average()
        val cloudy = starCount < CLOUD_RATIO * stars
        cloudyRun = if (cloudy) cloudyRun + 1 else 0
        softRun = if (soft && !cloudy) softRun + 1 else 0
        goodRun = if (!cloudy && !soft) goodRun + 1 else 0
        when {
            cloudyRun >= 2 -> note = CLOUDS
            softRun >= 2 -> note = SOFT
            goodRun >= 2 -> note = null
        }
        return note
    }

    companion object {
        const val REFERENCE_FRAMES = 2
        const val MIN_REFERENCE_STARS = 20
        /** Fewer than this share of the reference stars: something is in the way. */
        const val CLOUD_RATIO = 0.4
        /** Stars this much wider than at the start: dew or focus. */
        const val SOFT_RATIO = 1.6f

        const val CLOUDS = "Far fewer stars in the last frames: cloud or haze is passing. Frames " +
            "like these count for less in the stack, or are left out; the session carries on."
        const val SOFT = "The stars are getting soft: dew on the lens, or the focus has drifted. " +
            "Wipe the lens gently between frames; soft frames count for less, or are left out."
    }
}
