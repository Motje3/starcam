package com.wm.starcam.engine

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EditParamsTest {

    @Test
    fun defaultsGiveTheEnginesDefaultLook() {
        val o = JSONObject(EditParams().toOptionsJson(masterUpright = true))
        assertEquals(2, o.getInt("version"))
        assertEquals(0.16, o.getDouble("skyLevel"), 1e-6)
        // contrast 0.6 maps to the engine's default contrastGap of 0.25 (within rounding).
        assertEquals(0.25, o.getDouble("contrastGap"), 0.005)
        assertEquals(0, o.getInt("rotation"))
        assertEquals(0, o.getInt("downEdge"))
        // post.h: all of the light pollution removed, no extra detail, stars as they are.
        assertEquals(1.0, o.getDouble("gradientStrength"), 1e-6)
        assertEquals(0.0, o.getDouble("detail"), 1e-6)
        assertEquals(0.0, o.getDouble("starReduction"), 1e-6)
        assertEquals(0.0, o.getDouble("vignette"), 1e-6)
    }

    @Test
    fun groundEdgeFollowsTheTurn() {
        // The user turns the photo until it is upright, so the turn says where the ground is.
        assertEquals(3, JSONObject(EditParams(rotation = 90).toOptionsJson(true)).getInt("downEdge"))
        // An old, sideways master: unknown until the user turns it upright.
        assertEquals(-1, JSONObject(EditParams().toOptionsJson(false)).getInt("downEdge"))
        assertEquals(1, JSONObject(EditParams(rotation = 270).toOptionsJson(false)).getInt("downEdge"))
        assertEquals(2, JSONObject(EditParams(rotation = 180).toOptionsJson(false)).getInt("downEdge"))
    }

    @Test
    fun savedSettingsRoundTrip() {
        val p = EditParams(sky = 0.2f, contrast = 0.8f, ground = 0.03f, saturation = 1.6f, starColor = 0.9f,
            warmth = -0.3f, tint = 0.2f, gradient = 0.5f, noise = 0.1f, sharpen = true, detail = 0.7f,
            smallerStars = 0.4f, vignette = 0.3f, style = 1, rotation = 270)
        assertEquals(p, EditParams.fromJson(JSONObject(p.toJson())))
    }

    @Test
    fun settingsSavedBeforeDetailKeepTheirLook() {
        // An edit.json from before the detail and star sliders: neither is applied.
        val p = EditParams.fromJson(JSONObject("""{"sky":0.2,"gradient":0.9}"""))
        assertEquals(0f, p.detail)
        assertEquals(0f, p.smallerStars)
        assertEquals(0f, p.vignette)
        assertEquals(0.9f, p.gradient)
    }

    @Test
    fun theStarsBalanceGoesWithEveryDevelop() {
        // Without one, nothing about it is said: the engine keeps the camera's balance.
        val plain = JSONObject(EditParams().toOptionsJson(true))
        assertFalse(plain.has("wbR") || plain.has("wbB") || plain.has("skyStars"))
        // With one, the gains and the sky's points as a flat array; Warmth and Tint as they are.
        val b = StarBalance(1.35, 0.78, doubleArrayOf(10.0, 20.0, 0.013, 30.5, 40.5, 0.014))
        val o = JSONObject(EditParams(warmth = 0.3f, tint = -0.1f).toOptionsJson(true, b))
        assertEquals(1.35, o.getDouble("wbR"), 1e-12)
        assertEquals(0.78, o.getDouble("wbB"), 1e-12)
        val pts = o.getJSONArray("skyStars")
        assertEquals(6, pts.length())
        assertEquals(30.5, pts.getDouble(3), 1e-12)
        assertEquals(0.014, pts.getDouble(5), 1e-12)
        assertEquals(0.3, o.getDouble("warmth"), 1e-6)
        assertEquals(-0.1, o.getDouble("tint"), 1e-6)
    }

    @Test
    fun theBalanceIsReadFromTheSession() {
        val dir = Files.createTempDirectory("stelli_balance").toFile()
        try {
            // No sky.json: a session as before, nothing to keep.
            assertNull(StarBalance.read(dir))
            File(dir, "out").mkdirs()
            File(dir, "meta").mkdirs()
            File(dir, "out/stars.json").writeText(
                """{"version":1,"width":30,"height":40,"skyBg":[0.01,0.01,0.01],"stars":[""" +
                    """{"x":1,"y":2,"flux":3,"bg":0.01,"pt":true},{"x":5,"y":6,"flux":2,"bg":0.02,"pt":false}]}""")
            // The photo did not take the stars' balance (Star colours off): nothing to keep either.
            File(dir, "meta/sky.json").writeText("""{"stars":20,"matched":15}""")
            assertNull(StarBalance.read(dir))
            File(dir, "meta/sky.json").writeText(
                """{"stars":20,"matched":15,"colour":{"wbR":1.3,"wbB":0.75,"stars":14,"source":"stars"}}""")
            val b = StarBalance.read(dir)!!
            assertEquals(1.3, b.wbR, 1e-12)
            assertEquals(0.75, b.wbB, 1e-12)
            assertArrayEquals(doubleArrayOf(1.0, 2.0, 0.01), b.skyStars, 1e-12)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theCamerasBalanceFromItsRasterGains() {
        // This phone is BGGR: blue first in the raster. 31 Oct: R 1.58, B 2.295 over green 1.
        val (r, b) = StackCallback.cameraBalance(floatArrayOf(2.295f, 1f, 1f, 1.58f), 3)
        assertEquals(1.58, r, 1e-6)
        assertEquals(2.295, b, 1e-6)
        val (r2, b2) = StackCallback.cameraBalance(floatArrayOf(2f, 1.02f, 0.98f, 1.5f), 0)
        assertEquals(2.0, r2, 1e-6)
        assertEquals(1.5, b2, 1e-6)
        assertEquals(1.0 to 1.0, StackCallback.cameraBalance(floatArrayOf(0f, 0f, 0f, 0f), 0))
    }

    @Test
    fun theStarColourSettingIsKeptWithTheSession() {
        // What the photo was made with (a re-stack takes the setting of the day, like the look).
        val off = StackConfig(starColour = false)
        assertFalse(StackConfig.fromJson(JSONObject(off.toJson())).starColour)
        assertTrue(StackConfig.fromJson(JSONObject(StackConfig().toJson())).starColour)
        // A header from before the setting: its default.
        assertTrue(StackConfig.fromJson(JSONObject("""{"version":2}""")).starColour)
    }

    @Test
    fun oddTurnsSnapToAQuarter() {
        // The engine rounds a turn to the nearest quarter; the preview must be sized the same.
        assertEquals(90, EditParams.fromJson(JSONObject("""{"rotation":45}""")).rotation)
        assertEquals(270, EditParams.fromJson(JSONObject("""{"rotation":-90}""")).rotation)
        assertEquals(0, EditParams.fromJson(JSONObject("""{"rotation":350}""")).rotation)
        assertEquals(180, EditParams.fromJson(JSONObject("""{"rotation":540}""")).rotation)
    }
}
