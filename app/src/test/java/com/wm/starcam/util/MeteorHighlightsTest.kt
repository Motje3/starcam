package com.wm.starcam.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MeteorHighlightsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun onlyMeteorsFromTheReportInTheOrderTheyFell() {
        val session = tmp.newFolder("s")
        File(session, "out").mkdirs()
        File(session, "out/stack_report.json").writeText(
            """{"streaks":[
                {"frameIndex":9,"x0":10,"y0":20,"x1":110,"y1":60,"cls":0},
                {"frameIndex":4,"x0":1,"y0":2,"x1":3,"y1":40,"cls":1},
                {"frameIndex":2,"x0":5,"y0":6,"x1":70,"y1":8,"cls":0},
                {"frameIndex":5,"x0":5,"y0":6,"x1":70,"y1":8,"cls":2}]}"""
        )
        val m = MeteorHighlights.meteors(session)
        assertEquals(listOf(2, 9), m.map { it.frame })
        assertEquals(110f, m[1].x1)
        // No report, no meteors.
        assertTrue(MeteorHighlights.meteors(tmp.newFolder("empty")).isEmpty())
    }

    @Test
    fun turnedLikeThePhoto() {
        // A 10 x 6 frame: its corners after each turn (clockwise).
        assertEquals(5f to 0f, MeteorHighlights.turn(0f, 0f, 90, 10, 6))
        assertEquals(0f to 9f, MeteorHighlights.turn(9f, 5f, 90, 10, 6))
        assertEquals(9f to 5f, MeteorHighlights.turn(0f, 0f, 180, 10, 6))
        assertEquals(0f to 9f, MeteorHighlights.turn(0f, 0f, 270, 10, 6))
        assertEquals(3f to 4f, MeteorHighlights.turn(3f, 4f, 0, 10, 6))
    }

    @Test
    fun theCropHoldsTheStreakWithRoomAndStaysInside() {
        // A level streak in the middle: a 4:3 crop round it.
        val c = MeteorHighlights.cropAround(400f, 300f, 600f, 320f, 1000, 800)
        assertTrue(c[0] < 400 && c[2] > 600 && c[1] < 300 && c[3] > 320)
        assertEquals(4.0 / 3.0, (c[2] - c[0]).toDouble() / (c[3] - c[1]), 0.02)
        // A steep one near the corner: 3:4, moved inside, not squeezed.
        val d = MeteorHighlights.cropAround(5f, 5f, 30f, 200f, 1000, 800)
        assertTrue(d[0] >= 0 && d[1] >= 0)
        assertEquals(3.0 / 4.0, (d[2] - d[0]).toDouble() / (d[3] - d[1]), 0.02)
        // Longer than the picture: the whole picture.
        val e = MeteorHighlights.cropAround(0f, 400f, 999f, 410f, 1000, 800)
        assertTrue(e[0] == 0 && e[2] == 1000)
    }
}
