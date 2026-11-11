package com.wm.starcam.sky

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** meta/sky.json written and read back, and the session page's lines from it. */
class SkyRecordTest {

    private val sol = PlateSolution(285.7, 68.69, 2809.4, 1.2, false, 1530.0, 2050.0, 57, 1.11, 0.0197, 0.31)

    @Test
    fun keepsWhatTheStarsSaid() {
        val r = SkyRecord(sol, 3072, 4096, 158, 57,
            SkyRecord.Colour(1.353, 0.778, 56, 0.039, 0.063, SkyRecord.SOURCE_STARS),
            SkyRecord.Sky(3.465, 17.42, 9, 56))
        val o = JSONObject(r.toJson())
        // The solution as whats_in.json keeps one, with its picture's size.
        val s = o.getJSONObject("solution")
        assertEquals(3072, s.getInt("width"))
        assertEquals(57, s.getInt("matched"))
        assertEquals("stars", o.getJSONObject("colour").getString("source"))
        val back = SkyRecord.parse(r.toJson())!!
        assertEquals(sol.raDeg, back.solution!!.raDeg, 1e-12)
        assertEquals(sol.kReach2, back.solution!!.kReach2, 1e-12)
        assertEquals(4096, back.height)
        assertEquals(158, back.stars)
        assertEquals(1.353, back.colour!!.wbR, 1e-12)
        assertEquals(0.063, back.colour!!.scatterB!!, 1e-12)
        assertEquals(17.42, back.sky!!.magArcsec2, 1e-12)
        assertEquals(56, back.sky!!.stars)
        assertEquals(listOf(
            "Star colours" to "Set by 56 stars",
            "Sky brightness" to "17.4 mag/arcsec², like Bortle 9, in this direction",
        ), back.detailRows())
    }

    @Test
    fun aRecordWithMoreInItIsStillRead() {
        // What an earlier build kept beside these (a guess at the faintest stars) is passed over.
        val back = SkyRecord.parse(
            """{"stars":104,"matched":88,"sky":{"zp":3.07,"magArcsec2":17.0,"bortle":9,"limitingMag":4.6,"stars":83}}""")!!
        assertEquals(17.0, back.sky!!.magArcsec2, 1e-12)
        assertEquals(listOf("Sky brightness" to "17.0 mag/arcsec², like Bortle 9, in this direction"), back.detailRows())
    }

    @Test
    fun aNightThatDidNotSolve() {
        // The camera's remembered balance, nothing else.
        val r = SkyRecord(width = 3072, height = 4096,
            colour = SkyRecord.Colour(1.34, 0.75, 0, null, null, SkyRecord.SOURCE_REMEMBERED))
        val back = SkyRecord.parse(r.toJson())!!
        assertNull(back.solution)
        assertNull(back.sky)
        assertNull(back.colour!!.scatterR)
        assertEquals(listOf("Star colours" to "From this camera's earlier photos"), back.detailRows())
        // Nothing known: no lines.
        assertTrue(SkyRecord().detailRows().isEmpty())
        assertNull(SkyRecord.parse("{"))
        assertNotNull(SkyRecord.parse("{}"))
    }
}
