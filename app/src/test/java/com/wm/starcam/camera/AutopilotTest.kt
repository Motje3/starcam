package com.wm.starcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The autopilot's decisions, frame by frame, for the nights it has to handle. */
class AutopilotTest {

    private val minute = 60_000L

    private fun pilot(planned: Int = 10, config: Autopilot.Config = Autopilot.Config()) = Autopilot(planned, config)

    /** A clear frame: as many stars as the reference, as many matched, as sharp, the same sky. */
    private fun Autopilot.clear(frame: Int, t: Long = frame * 20_000L) = onFrame(frame, 400, 150, 2.0f, 0.04f, t)

    @Test
    fun aClearNightNeedsNothing() {
        val p = pilot()
        for (f in 0 until 10) assertEquals(Autopilot.Action.Keep, p.clear(f))
        assertEquals(10, p.goodFrames)
        assertEquals(10, p.plannedFrames)
        assertTrue(p.report().isEmpty())
    }

    @Test
    fun cloudIsLeftOutReplacedAndWaitedOut() {
        val p = pilot()
        p.clear(0); p.clear(1); p.clear(2)
        // The first cloudy frame is replaced, the second starts the wait.
        assertEquals(Autopilot.Action.Replace(Autopilot.Reason.CLOUD, extra = true), p.onFrame(3, 60, 20, 2.1f, 0.05f, 60_000L))
        assertEquals(Autopilot.Action.Wait(extra = true), p.onFrame(4, 40, 10, 2.2f, 0.05f, 80_000L))
        assertTrue(p.waiting)
        assertEquals(12, p.plannedFrames)
        // Checks: still cloudy, then clear.
        assertEquals(Autopilot.Check.CLOUDY, p.onCheck(50, 15, 0.05f, 100_000L))
        assertEquals(Autopilot.Check.CLOUDY, p.onCheck(90, 40, 0.05f, 120_000L))
        assertEquals(Autopilot.Check.CLEAR, p.onCheck(330, 140, 0.04f, 200_000L))
        assertFalse(p.waiting)
        // The clear frame is stacked and judged like any other.
        assertEquals(Autopilot.Action.Keep, p.onFrame(5, 330, 140, 2.0f, 0.04f, 200_000L))
        assertEquals(120_000L, p.waitedMs)
        val report = p.report()
        assertTrue(report.toString(), report.any { "2 frames with cloud" in it && "2 extra" in it })
        assertTrue(report.toString(), report.any { it.startsWith("Waited 2 min") && "3 checks" in it })
    }

    @Test
    fun hotPixelsKeepTheCountUpButTheMatchesFall() {
        // The detector keeps the brightest 300; under thick cloud hot pixels fill the list, but
        // they match nothing in the sky: the match count gives the cloud away.
        val p = pilot()
        p.onFrame(0, 300, -1, 1.7f, 0.035f, 0L)   // the engine's reference: no match count
        p.onFrame(1, 300, 149, 1.7f, 0.035f, 1000L)
        p.onFrame(2, 300, 149, 1.7f, 0.035f, 2000L)
        assertEquals(Autopilot.Action.Replace(Autopilot.Reason.CLOUD, true), p.onFrame(3, 300, 22, 1.45f, 0.084f, 3000L))
        assertTrue(p.onFrame(4, 300, 0, 1.42f, 0.084f, 4000L) is Autopilot.Action.Wait)
        // A check with the same false count is still cloud; one that matches again is clear.
        assertEquals(Autopilot.Check.CLOUDY, p.onCheck(300, 23, 0.084f, 5000L))
        assertEquals(Autopilot.Check.CLEAR, p.onCheck(300, 140, 0.035f, 6000L))
    }

    @Test
    fun cloudThatStaysEndsTheSessionAfterTheWait() {
        val p = pilot(config = Autopilot.Config(maxWaitMs = 10 * minute))
        p.clear(0); p.clear(1); p.clear(2)
        p.onFrame(3, 30, 5, 2.0f, 0.04f, minute)
        assertTrue(p.onFrame(4, 30, 5, 2.0f, 0.04f, 2 * minute) is Autopilot.Action.Wait)
        assertEquals(Autopilot.Check.CLOUDY, p.onCheck(20, 3, 0.04f, 7 * minute))
        assertEquals(Autopilot.Check.GIVE_UP, p.onCheck(20, 3, 0.04f, 12 * minute))
        assertNotNull(p.finished)
        assertTrue(p.finished!!.contains("did not clear in 10 min"))
        assertTrue(p.finished!!.contains("3 good frames"))
    }

    @Test
    fun withoutWaitingCloudIsOnlyReplacedWithinTheBudget() {
        val p = pilot(config = Autopilot.Config(maxWaitMs = 0L, maxExtraFrames = 2))
        p.clear(0); p.clear(1)
        val actions = (2..5).map { p.onFrame(it, 20, 3, 2.0f, 0.04f, it * 20_000L) }
        assertEquals(
            listOf(true, true, false, false),
            actions.map { (it as Autopilot.Action.Replace).extra },
        )
        assertFalse(p.waiting)
        assertEquals(12, p.plannedFrames)
        assertEquals(4, p.replaced[Autopilot.Reason.CLOUD])
    }

    @Test
    fun softStarsBringARefocusAndItHelps() {
        val p = pilot()
        p.clear(0); p.clear(1); p.clear(2)
        // Softer but usable: kept. Twice in a row: refocus.
        assertEquals(Autopilot.Action.Keep, p.onFrame(3, 380, 145, 3.0f, 0.04f, 60_000L))
        val a = p.onFrame(4, 370, 140, 3.2f, 0.04f, 80_000L)
        assertEquals(Autopilot.Action.Refocus(null), a)
        assertEquals(1, p.refocusCount)
        p.onRefocused(true, 0.10f, 0.16f)
        // Sharp again: noted, and no further refocus.
        assertEquals(Autopilot.Action.Keep, p.clear(5))
        val report = p.report()
        assertTrue(report.toString(), report.any { "Refocused from 0.100 to 0.160 dpt" in it })
        assertTrue(report.toString(), report.any { "sharp again" in it })
    }

    @Test
    fun verySoftFramesAreReplacedEvenWhileRefocusing() {
        val p = pilot()
        p.clear(0); p.clear(1)
        assertEquals(Autopilot.Action.Replace(Autopilot.Reason.SOFT, true), p.onFrame(2, 300, 120, 4.0f, 0.04f, 40_000L))
        val a = p.onFrame(3, 300, 120, 4.0f, 0.04f, 60_000L)
        assertEquals(Autopilot.Action.Refocus(Autopilot.Action.Replace(Autopilot.Reason.SOFT, true)), a)
        assertEquals(2, p.replaced[Autopilot.Reason.SOFT])
    }

    @Test
    fun stillSoftAfterRefocusingIsDewAndItStopsRefocusing() {
        val p = pilot(planned = 30)
        p.clear(0); p.clear(1)
        p.onFrame(2, 380, 145, 3.0f, 0.04f, 0L)
        assertTrue(p.onFrame(3, 380, 145, 3.0f, 0.04f, 0L) is Autopilot.Action.Refocus)
        p.onRefocused(true, 0.10f, 0.12f)
        p.onFrame(4, 380, 145, 3.0f, 0.04f, 0L)
        p.onFrame(5, 380, 145, 3.0f, 0.04f, 0L)
        assertTrue(p.note!!.contains("dew"))
        // More soft frames: never another refocus.
        for (f in 6..12) assertFalse(p.onFrame(f, 380, 145, 3.0f, 0.04f, 0L) is Autopilot.Action.Refocus)
        assertEquals(1, p.refocusCount)
        assertTrue(p.report().any { "dew" in it })
    }

    @Test
    fun aRefocusThatFoundNoStarsDoesNotBlameDew() {
        val p = pilot(planned = 30)
        p.clear(0); p.clear(1)
        p.onFrame(2, 380, 145, 3.0f, 0.04f, 0L)
        assertTrue(p.onFrame(3, 380, 145, 3.0f, 0.04f, 0L) is Autopilot.Action.Refocus)
        p.onRefocused(false, 0.10f, 0.10f)
        p.onFrame(4, 380, 145, 3.0f, 0.04f, 0L)
        // Soft again: a second (last) refocus is allowed, no dew verdict.
        assertTrue(p.onFrame(5, 380, 145, 3.0f, 0.04f, 0L) is Autopilot.Action.Refocus)
        assertFalse(p.report().any { "dew" in it })
    }

    @Test
    fun refocusIsNotTriedWhenSwitchedOff() {
        val p = pilot(config = Autopilot.Config(refocus = false))
        p.clear(0); p.clear(1)
        for (f in 2..8) assertFalse(p.onFrame(f, 380, 145, 3.0f, 0.04f, 0L) is Autopilot.Action.Refocus)
    }

    @Test
    fun dawnEndsTheSessionWithTheGoodFrames() {
        val p = pilot(planned = 20)
        for (f in 0..5) p.clear(f)
        // Brightening, stars fading into it: dawn, not cloud.
        assertEquals(Autopilot.Action.Keep, p.onFrame(6, 300, 120, 2.0f, 0.11f, 0L))
        assertEquals(Autopilot.Action.Keep, p.onFrame(7, 200, 80, 2.0f, 0.15f, 0L))
        val a = p.onFrame(8, 80, 30, 2.0f, 0.22f, 0L)
        assertTrue(a.toString(), a is Autopilot.Action.Finish)
        // The frame that ends it is kept: 9 frames go into the stack.
        assertTrue(p.finished!!, p.finished!!.contains("9 good frames"))
        assertFalse(p.waiting)
    }

    @Test
    fun aBriefBrightSpellIsNotDawn() {
        val p = pilot(planned = 20)
        for (f in 0..3) p.clear(f)
        p.onFrame(4, 380, 145, 2.0f, 0.12f, 0L)
        p.onFrame(5, 380, 145, 2.0f, 0.12f, 0L)
        assertEquals(Autopilot.Action.Keep, p.clear(6))
        assertEquals(Autopilot.Action.Keep, p.onFrame(7, 380, 145, 2.0f, 0.12f, 0L))
        assertNull(p.finished)
    }

    @Test
    fun dawnWhileWaitingForCloudGivesUp() {
        val p = pilot()
        for (f in 0..3) p.clear(f)
        p.onFrame(4, 20, 3, 2.0f, 0.04f, 0L)
        assertTrue(p.onFrame(5, 20, 3, 2.0f, 0.04f, 0L) is Autopilot.Action.Wait)
        assertEquals(Autopilot.Check.CLOUDY, p.onCheck(10, 2, 0.12f, 1000L))
        assertEquals(Autopilot.Check.CLOUDY, p.onCheck(10, 2, 0.14f, 2000L))
        assertEquals(Autopilot.Check.GIVE_UP, p.onCheck(10, 2, 0.16f, 3000L))
        assertTrue(p.finished!!.contains("brighter while waiting"))
    }

    @Test
    fun theTimeBudgetEndsTheBurst() {
        val p = pilot(planned = 100, config = Autopilot.Config(timeBudgetMs = 10 * minute))
        var finishedAt = -1
        for (f in 0 until 100) {
            if (p.clear(f, f * 30_000L) is Autopilot.Action.Finish) { finishedAt = f; break }
        }
        assertEquals(20, finishedAt)
        assertTrue(p.finished!!.startsWith("Time's up (10 min)"))
    }

    @Test
    fun theTimeBudgetAlsoEndsAWait() {
        val p = pilot(planned = 100, config = Autopilot.Config(timeBudgetMs = 10 * minute))
        for (f in 0..4) p.clear(f, f * 30_000L)
        p.onFrame(5, 20, 3, 2.0f, 0.04f, 3 * minute)
        assertTrue(p.onFrame(6, 20, 3, 2.0f, 0.04f, 4 * minute) is Autopilot.Action.Wait)
        assertEquals(Autopilot.Check.CLOUDY, p.onCheck(20, 3, 0.04f, 8 * minute))
        assertEquals(Autopilot.Check.GIVE_UP, p.onCheck(20, 3, 0.04f, 10 * minute))
        assertTrue(p.finished!!.startsWith("Time's up"))
    }

    @Test
    fun aStarlessStartSetsNoBar() {
        val p = pilot()
        // Twilight frames with a handful of stars: no reference yet, so nothing is judged.
        for (f in 0..3) assertEquals(Autopilot.Action.Keep, p.onFrame(f, 8, 0, 2.0f, 0.2f, 0L))
        assertFalse(p.hasReference)
        p.clear(4); p.clear(5)
        assertTrue(p.hasReference)
        assertTrue(p.onFrame(6, 30, 5, 2.0f, 0.04f, 0L) is Autopilot.Action.Replace)
    }
}
