package com.wm.starcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkyWatchTest {

    private fun watch(): SkyWatch = SkyWatch().apply {
        add(300, 2.4f)
        add(320, 2.5f)
    }

    @Test
    fun steadySkySaysNothing() {
        val w = watch()
        repeat(10) { assertNull(w.add(290 + it, 2.45f)) }
    }

    @Test
    fun oneOddFrameSaysNothing() {
        val w = watch()
        assertNull(w.add(40, 2.4f))
        assertNull(w.add(300, 2.4f))
    }

    @Test
    fun cloudsAfterTwoFramesAndClearAfterTwoGood() {
        val w = watch()
        assertNull(w.add(60, 2.4f))
        assertEquals(SkyWatch.CLOUDS, w.add(50, 2.4f))
        assertEquals(SkyWatch.CLOUDS, w.add(300, 2.4f))  // one good frame: not yet
        assertNull(w.add(310, 2.4f))
    }

    @Test
    fun softStarsAreDewOrFocus() {
        val w = watch()
        w.add(280, 4.2f)
        assertEquals(SkyWatch.SOFT, w.add(270, 4.4f))
    }

    @Test
    fun noReferenceWithoutAStarField() {
        // Frames too poor to set a reference never raise a warning.
        val w = SkyWatch()
        repeat(6) { assertNull(w.add(5, 2.4f)) }
    }
}
