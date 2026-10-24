package com.wm.starcam.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TimelapseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun framesInOrderWithoutTheBumpedOnesOrTheDark() {
        val session = tmp.newFolder("20261024_030000_astro")
        val cache = File(session, "cache").apply { mkdirs() }
        for (i in listOf(10, 2, 0, 1, 3)) File(cache, String.format("frame_%04d.sraw", i)).writeText("x")
        File(cache, "master_dark.sraw").writeText("x")
        File(session, "meta").mkdirs()
        File(session, "meta/session.json").writeText(
            """{"frames":[{"index":0,"bumped":false},{"index":1,"bumped":true},{"index":2},{"index":3,"bumped":false}]}"""
        )
        assertEquals(listOf("frame_0000", "frame_0002", "frame_0003", "frame_0010"),
            Timelapse.frames(session).map { it.nameWithoutExtension })
    }

    @Test
    fun noSessionJsonKeepsEveryFrame() {
        val session = tmp.newFolder("old")
        val cache = File(session, "cache").apply { mkdirs() }
        repeat(5) { File(cache, String.format("frame_%04d.sraw", it)).writeText("x") }
        assertEquals(5, Timelapse.frames(session).size)
    }

    @Test
    fun aShortSessionStillPlaysAFewSeconds() {
        assertEquals(3.0, Timelapse.playSec(8), 1e-9)
        assertEquals(12.5, Timelapse.playSec(100), 1e-9)
        assertEquals(20.0, Timelapse.playSec(2000), 1e-9)
        assertTrue(Timelapse.playSec(60) > Timelapse.playSec(30))
    }
}
