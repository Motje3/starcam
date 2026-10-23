package com.wm.starcam.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How many live-stack pictures a session keeps for its replay video, and how long it runs. */
class StackReplayTest {

    /** Runs a session of [frames] stacked frames through the keeping rule; the indices kept. */
    private fun session(frames: Int): List<Int> {
        val kept = mutableListOf<Int>()
        for (i in 1..frames) {
            val drop = StackReplay.toDrop(kept, i) ?: continue
            kept.removeAll(drop.toSet())
            kept += i
        }
        return kept
    }

    @Test
    fun aShortSessionKeepsEveryFrame() {
        assertEquals((1..40).toList(), session(40))
        assertEquals(StackReplay.MAX_FRAMES, session(StackReplay.MAX_FRAMES).size)
    }

    @Test
    fun aLongSessionStaysBoundedAndEvenlySpread() {
        val m = StackReplay.MAX_FRAMES
        for (n in listOf(m + 1, 2 * m, 2 * m + 1, 777, 2000)) {
            val kept = session(n)
            assertTrue("$n frames kept ${kept.size}", kept.size <= StackReplay.MAX_FRAMES)
            assertTrue("$n frames kept only ${kept.size}", kept.size >= StackReplay.MAX_FRAMES / 2)
            // One spacing throughout, from the start of the session to its end.
            val gaps = kept.zipWithNext { a, b -> b - a }.toSet()
            assertEquals("$n frames: gaps $gaps", 1, gaps.size)
            assertTrue(kept.first() <= gaps.first())
            assertTrue(n - kept.last() < StackReplay.keepStep(n))
        }
    }

    @Test
    fun framesBetweenTheSpacingAreSkipped() {
        assertNull(StackReplay.toDrop(emptyList(), StackReplay.MAX_FRAMES + 1))
        assertEquals(2, StackReplay.keepStep(StackReplay.MAX_FRAMES + 1))
        assertEquals(4, StackReplay.keepStep(2 * StackReplay.MAX_FRAMES + 1))
    }

    @Test
    fun theVideoIsShort() {
        // A few frames still get a few seconds; a night of them is capped.
        assertEquals(7.2, StackReplay.durationSec(2), 1e-9)
        assertEquals(12.2, StackReplay.durationSec(5000), 1e-9)
        assertTrue(StackReplay.durationSec(30) > StackReplay.durationSec(10))
    }
}
