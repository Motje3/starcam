package com.wm.starcam.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

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
    fun oddTurnsSnapToAQuarter() {
        // The engine rounds a turn to the nearest quarter; the preview must be sized the same.
        assertEquals(90, EditParams.fromJson(JSONObject("""{"rotation":45}""")).rotation)
        assertEquals(270, EditParams.fromJson(JSONObject("""{"rotation":-90}""")).rotation)
        assertEquals(0, EditParams.fromJson(JSONObject("""{"rotation":350}""")).rotation)
        assertEquals(180, EditParams.fromJson(JSONObject("""{"rotation":540}""")).rotation)
    }
}
