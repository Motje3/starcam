package com.wm.starcam.camera

import kotlin.math.roundToInt

/**
 * The capture's autopilot (ASTRO): watches every light frame as it comes in and does what a
 * photographer standing next to the tripod would do, instead of leaving them to find out at the
 * end that half the frames were cloud.
 *
 * - A frame spoiled by cloud or soft stars - one the stacker would throw out anyway - is left out
 *   at once and replaced by an extra frame at the end, within a budget, so the session ends with
 *   the number of good frames it set out to get.
 * - Two cloudy frames in a row and it waits: the next exposures are only checks, measured and
 *   dropped, until the stars are back; then it carries on. If the sky has not cleared after
 *   [Config.maxWaitMs] it finishes with what it has.
 * - Stars going soft while there are still as many of them (cloud would take them away) is the
 *   focus drifting as the lens cools: it re-runs the star focus around the current position, once
 *   or twice. Still soft right after is dew on the lens, which no focus fixes: it says so.
 * - The sky brightening for good (dawn, moonrise) and it finishes with the good frames it has:
 *   brighter frames only add noise.
 * - With a time budget it finishes when the time is up.
 *
 * Pure logic with no Android in it, so the decisions are checked frame by frame in unit tests;
 * CaptureEngine does the capturing, rejecting and refocusing it asks for.
 */
class Autopilot(planned: Int, private val config: Config) {

    class Config(
        /** Wait this long for clouds to clear; 0 = never wait, only replace. */
        val maxWaitMs: Long = 15 * 60_000L,
        /** May re-run the star focus (star autofocus mode, a lens that focuses). */
        val refocus: Boolean = true,
        /** Finish when the sky brightens for good. */
        val finishWhenBright: Boolean = true,
        /** Finish after this long shooting lights; 0 = no limit. */
        val timeBudgetMs: Long = 0L,
        /** Replacement frames on top of the plan. */
        val maxExtraFrames: Int = 4,
    )

    enum class Reason(val label: String) { CLOUD("cloud"), SOFT("soft stars") }

    sealed class Action {
        /** Use the frame. */
        data object Keep : Action()
        /** Leave the frame out ([extra]: a frame was added to the plan to replace it). */
        data class Replace(val reason: Reason, val extra: Boolean) : Action()
        /** Leave the frame out and wait for the cloud to pass: the next exposures are checks. */
        data class Wait(val extra: Boolean) : Action()
        /** Keep or drop the frame as [replace] says, then refocus before the next one. */
        data class Refocus(val replace: Replace?) : Action()
        /** Keep the frame and end the burst. */
        data class Finish(val message: String) : Action()
    }

    enum class Check { CLEAR, CLOUDY, GIVE_UP }

    private var budget = planned

    private val refStars = ArrayList<Int>()
    private val refFwhm = ArrayList<Float>()
    private val refBg = ArrayList<Float>()
    /**
     * Reference stars each frame matched (alignment inliers). Cloud dims the stars below the
     * alignment's flux gate, so this falls even when the detector's count does not: it keeps
     * only the brightest few hundred, and hot pixels fill the list under cloud.
     */
    private val refMatched = ArrayList<Int>()

    /** The first good frames set the bar every later frame is judged against. */
    val hasReference: Boolean get() = refStars.size >= REFERENCE_FRAMES

    var goodFrames = 0
        private set
    var extraFrames = 0
        private set

    /** Frames left out, by reason. */
    val replaced = LinkedHashMap<Reason, Int>()

    var waiting = false
        private set
    /** When the current wait began, in the same clock as the elapsed times passed in. */
    var waitStartedAtMs = 0L
        private set
    var waitedMs = 0L
        private set
    var checks = 0
        private set

    private var cloudRun = 0
    private var softRun = 0
    private var brightRun = 0
    private var hazeNoted = false

    var refocusCount = 0
        private set
    /** Frames to watch after a refocus before deciding it did not help. */
    private var afterRefocus = 0
    private var dew = false

    /** Set when it decided to end the burst; the report's last line. */
    var finished: String? = null
        private set

    /** What it did and saw, in order, for the session report. */
    val events = ArrayList<String>()

    /** One line for the capture screen: what it is doing now, or the last thing it did. */
    var note: String? = null
        private set

    private fun median(v: List<Float>): Float = v.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }

    private fun addExtra(): Boolean {
        if (extraFrames >= config.maxExtraFrames) return false
        extraFrames++
        budget++
        return true
    }

    private fun countReplaced(reason: Reason) {
        replaced[reason] = (replaced[reason] ?: 0) + 1
    }

    private fun refMatchedOrZero(): Float = if (refMatched.isEmpty()) 0f else median(refMatched.map { it.toFloat() })

    /** Fewer stars, or fewer of the reference's stars matched, than cloud-free sky gives. */
    private fun cloudy(stars: Int, matched: Int, share: Float): Boolean {
        val rm = refMatchedOrZero()
        return stars < share * median(refStars.map { it.toFloat() }) || (rm > 0f && matched >= 0 && matched < share * rm)
    }

    /**
     * A light frame was taken and stacked as frame number [frame] (0-based): what to do with it.
     * [matched] is how many reference stars it matched (-1: unknown, the engine's own reference
     * frame); [elapsedMs] the time since the burst started.
     */
    fun onFrame(frame: Int, stars: Int, matched: Int, fwhm: Float, bg: Float, elapsedMs: Long): Action {
        if (!hasReference) {
            // Until the bar is set every frame counts; the stacker judges these itself.
            if (stars >= MIN_REFERENCE_STARS) {
                refStars += stars
                if (fwhm > 0f) refFwhm += fwhm
                refBg += bg
                if (matched >= MIN_REFERENCE_MATCHED) refMatched += matched
            }
            goodFrames++
            return timeUp(elapsedMs) ?: Action.Keep
        }
        val rf = median(refFwhm)
        val cloudy = cloudy(stars, matched, CLOUD_SPOIL)
        // The engine's reference frame has no match count of its own: the next clear frames give it.
        if (!cloudy && refMatched.size < REFERENCE_FRAMES && matched >= MIN_REFERENCE_MATCHED) refMatched += matched
        val soft = !cloudy && rf > 0f && fwhm > SOFT * rf
        val spoiledSoft = soft && fwhm > SOFT_SPOIL * rf

        // A sky that stays much brighter is dawn or the Moon (or lights), not cloud, even though
        // the faint stars vanish into it as they would behind cloud: waiting will not bring them
        // back.
        brightRun = if (isBright(bg)) brightRun + 1 else 0
        if (brightRun >= BRIGHT_FRAMES && config.finishWhenBright && goodFrames >= MIN_GOOD_TO_FINISH) {
            goodFrames++  // this frame is kept too
            val msg = "The sky got %.0f× brighter (dawn, the Moon or lights): finished with %d good frames"
                .format(bg / median(refBg), goodFrames)
            finished = msg
            events += msg
            return Action.Finish(msg)
        }
        if (cloudy) {
            cloudRun++
            softRun = 0
            countReplaced(Reason.CLOUD)
            val extra = addExtra()
            if (cloudRun >= 2 && config.maxWaitMs > 0L) {
                waiting = true
                waitStartedAtMs = elapsedMs
                events += "Frame ${frame + 1}: cloud — waiting for it to pass"
                note = "Cloud is passing: waiting for the stars to come back"
                return Action.Wait(extra)
            }
            note = "Cloud in frame ${frame + 1}: left out" + if (extra) ", an extra frame added" else ""
            return Action.Replace(Reason.CLOUD, extra)
        }
        cloudRun = 0
        if (!hazeNoted && cloudy(stars, matched, HAZE)) {
            hazeNoted = true
            events += "Frame ${frame + 1}: thin cloud or haze — such frames count for less"
        }

        if (soft) {
            softRun++
            var replace: Action.Replace? = null
            if (spoiledSoft) {
                countReplaced(Reason.SOFT)
                replace = Action.Replace(Reason.SOFT, addExtra())
            }
            if (afterRefocus > 0) {
                afterRefocus--
                if (afterRefocus == 0 || softRun >= 2) {
                    // Refocused and still soft: not the focus. Dew (or frost) on the lens.
                    afterRefocus = 0
                    if (!dew) {
                        dew = true
                        events += "Frame ${frame + 1}: still soft after refocusing — dew on the lens, or wind?"
                    }
                    note = "Stars still soft after refocusing: dew on the lens (wipe it gently) or wind"
                    return replace ?: Action.Keep
                }
            } else if (softRun >= 2 && config.refocus && !dew && refocusCount < MAX_REFOCUS) {
                refocusCount++
                softRun = 0
                afterRefocus = 2
                events += "Frame ${frame + 1}: stars softening (%.1f px against %.1f) — refocusing".format(fwhm, rf)
                note = "Stars are softening: refocusing on them"
                return Action.Refocus(replace)
            }
            if (replace != null) {
                note = "Soft stars in frame ${frame + 1}: left out" + if (replace.extra) ", an extra frame added" else ""
                return replace
            }
        } else {
            if (afterRefocus > 0) {
                afterRefocus = 0
                events += "Frame ${frame + 1}: sharp again after refocusing (%.1f px)".format(fwhm)
                note = "Refocused: the stars are sharp again"
            }
            softRun = 0
        }
        goodFrames++
        if (brightRun == 1) note = "The sky is getting brighter"
        return timeUp(elapsedMs) ?: Action.Keep
    }

    private fun isBright(bg: Float): Boolean {
        val rb = median(refBg)
        return rb > 0f && bg > BRIGHT * rb && bg - rb > MIN_BRIGHT_STEP
    }

    private fun timeUp(elapsedMs: Long): Action.Finish? {
        if (config.timeBudgetMs <= 0L || elapsedMs < config.timeBudgetMs || goodFrames < MIN_GOOD_TO_FINISH) return null
        val msg = "Time's up (${(config.timeBudgetMs / 60_000L).toInt()} min): finished with $goodFrames good frames"
        finished = msg
        events += msg
        return Action.Finish(msg)
    }

    /** While [waiting]: an exposure measured but not kept. CLEAR: stack this very frame. */
    fun onCheck(stars: Int, matched: Int, bg: Float, elapsedMs: Long): Check {
        checks++
        brightRun = if (isBright(bg)) brightRun + 1 else 0
        if (brightRun >= BRIGHT_FRAMES && config.finishWhenBright) {
            // Waiting for cloud to pass while dawn comes up: the stars are not coming back.
            waiting = false
            waitedMs += elapsedMs - waitStartedAtMs
            val msg = "The sky got brighter while waiting for the cloud (dawn or the Moon): finished with $goodFrames good frames"
            finished = msg
            events += msg
            return Check.GIVE_UP
        }
        if (!cloudy(stars, matched, CLEAR)) {
            waiting = false
            cloudRun = 0
            waitedMs += elapsedMs - waitStartedAtMs
            events += "Clear again after %s".format(minutesText(elapsedMs - waitStartedAtMs))
            note = "Clear again: carrying on"
            return Check.CLEAR
        }
        if (config.timeBudgetMs > 0L && elapsedMs >= config.timeBudgetMs && goodFrames >= MIN_GOOD_TO_FINISH) {
            waiting = false
            waitedMs += elapsedMs - waitStartedAtMs
            timeUp(elapsedMs)
            return Check.GIVE_UP
        }
        if (elapsedMs - waitStartedAtMs >= config.maxWaitMs) {
            waiting = false
            waitedMs += elapsedMs - waitStartedAtMs
            val msg = "The cloud did not clear in ${minutesText(config.maxWaitMs)}: finished with $goodFrames good frames"
            finished = msg
            events += msg
            return Check.GIVE_UP
        }
        return Check.CLOUDY
    }

    /** The star focus it asked for has run: [ok] false when it found too few stars to judge. */
    fun onRefocused(ok: Boolean, fromDpt: Float, toDpt: Float) {
        if (ok) {
            events += "Refocused from %.3f to %.3f dpt".format(fromDpt, toDpt)
        } else {
            // Nothing changed, so the next soft frames say nothing about dew.
            afterRefocus = 0
            events += "Refocus found too few stars; the focus was kept"
            note = "Refocus found too few stars: focus kept"
        }
    }

    /** The plan with the replacements added. */
    val plannedFrames: Int get() = budget

    /** The report for the result screen and the session file; empty when nothing happened. */
    fun report(): List<String> = buildList {
        val cloud = replaced[Reason.CLOUD] ?: 0
        val soft = replaced[Reason.SOFT] ?: 0
        if (cloud + soft > 0) {
            add(buildString {
                append("Left out ")
                append(listOfNotNull(
                    if (cloud > 0) "$cloud frame${if (cloud == 1) "" else "s"} with cloud" else null,
                    if (soft > 0) "$soft with soft stars" else null,
                ).joinToString(" and "))
                append(if (extraFrames > 0) " and shot $extraFrames extra" else "")
            })
        }
        if (waitedMs > 0L) add("Waited ${minutesText(waitedMs)} for cloud to pass ($checks check${if (checks == 1) "" else "s"})")
        events.filter { it.contains("refocus", ignoreCase = true) || "haze" in it }.forEach { add(it) }
        finished?.let { add(it) }
    }

    companion object {
        const val REFERENCE_FRAMES = 2
        const val MIN_REFERENCE_STARS = 20
        const val MIN_REFERENCE_MATCHED = 10
        /** Fewer stars than this share of the reference: spoiled by cloud (the stacker drops < 25%). */
        const val CLOUD_SPOIL = 0.3f
        /** Under this share: thin cloud or haze, kept but counting for less. */
        const val HAZE = 0.6f
        /** A check with this share of the reference stars is clear sky again. */
        const val CLEAR = 0.6f
        /** Stars this much wider than the reference are soft; past [SOFT_SPOIL] the frame goes. */
        const val SOFT = 1.45f
        const val SOFT_SPOIL = 1.7f
        const val MAX_REFOCUS = 2
        /** Background this many times the reference's, for [BRIGHT_FRAMES] frames running. */
        const val BRIGHT = 2.5f
        const val BRIGHT_FRAMES = 3
        /** ...and at least this much brighter in absolute terms (share of white). */
        const val MIN_BRIGHT_STEP = 0.01f
        /** It never ends a burst with fewer good frames than this. */
        const val MIN_GOOD_TO_FINISH = 3

        fun minutesText(ms: Long): String {
            val min = (ms / 60_000.0).roundToInt()
            return if (ms < 60_000L) "${(ms / 1000L).coerceAtLeast(1L)} s" else "$min min"
        }
    }
}
