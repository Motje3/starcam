package com.wm.starcam.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LooksTest {

    @Test
    fun naturalIsTheLookThePhotoWasMadeWith() {
        assertTrue(Look.NATURAL.matches(EditParams()))
        Look.entries.filter { it != Look.NATURAL }.forEach { assertFalse(it.name, it.matches(EditParams())) }
    }

    @Test
    fun aLookLeavesTheCleanUpAndTheTurnAlone() {
        val p = EditParams(ground = 0.1f, gradient = 0.4f, noise = 0.2f, sharpen = true, smallerStars = 0.6f,
            rotation = 90)
        for (look in Look.entries) {
            val t = look.applyTo(p)
            assertEquals(p.ground, t.ground)
            assertEquals(p.gradient, t.gradient)
            assertEquals(p.noise, t.noise)
            assertEquals(p.sharpen, t.sharpen)
            assertEquals(p.smallerStars, t.smallerStars)
            assertEquals(p.rotation, t.rotation)
            assertTrue(look.name, look.matches(t))
        }
    }

    @Test
    fun movingASliderLeavesTheLook() {
        val vivid = Look.VIVID.applyTo(EditParams())
        assertFalse(Look.VIVID.matches(vivid.copy(saturation = vivid.saturation + 0.1f)))
    }

    @Test
    fun monoHasNoColour() {
        val m = Look.MONO.applyTo(EditParams())
        assertEquals(0f, m.saturation)
        assertEquals(0f, m.starColor)
    }
}
