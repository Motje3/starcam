package com.wm.starcam.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Which white balance a photo is developed with, from what its stars and the camera's past say. */
class StackSkyTest {

    private val fit = StarColour.Fit(1.353, 0.778, 56, 0.039, 0.063, 0.005, 0.008, 0.32, -0.39, true, 1.81, 30.0)

    /** The sensor's own balance as two earlier sessions measured it, and the camera's guess of a later night. */
    private val remembered = 2.138 to 1.786
    private val cameraR = 1.57
    private val cameraB = 2.259

    @Test
    fun theStarsOwnBalanceComesFirst() {
        val c = StackSky.balance(fit, remembered, cameraR, cameraB, skyPoints = 157)
        assertNotNull(c)
        assertEquals(SkyRecord.SOURCE_STARS, c!!.source)
        assertEquals(1.353, c.wbR, 0.0)
        assertEquals(0.778, c.wbB, 0.0)
        assertEquals(56, c.stars)
        assertEquals(0.063, c.scatterB!!, 0.0)
    }

    @Test
    fun aNightThatGivesNoneTakesWhatTheCameraLearnt() {
        // Not solved, or its stars too poor: the sensor is the same, only the camera's guess moved.
        val c = StackSky.balance(null, remembered, cameraR, cameraB, skyPoints = 40)
        assertNotNull(c)
        assertEquals(SkyRecord.SOURCE_REMEMBERED, c!!.source)
        assertEquals(2.138 / 1.57, c.wbR, 1e-12)
        assertEquals(1.786 / 2.259, c.wbB, 1e-12)
        assertEquals(0, c.stars)
        assertNull(c.scatterR)
        // Nothing learnt yet, or frames whose own balance is not known: nothing to apply.
        assertNull(StackSky.balance(null, null, cameraR, cameraB, skyPoints = 40))
        assertNull(StackSky.balance(null, remembered, 0.0, cameraB, skyPoints = 40))
    }

    @Test
    fun withoutStarsToMarkTheSkyNothingIsApplied() {
        // The look holds the gains to the sky's own light by its sky points: with fewer than ten
        // (the sky hardly moved, and a lamp follows it as well as a star) it applies none, so
        // none is claimed - whatever the stars' colours said.
        assertNull(StackSky.balance(fit, remembered, cameraR, cameraB, skyPoints = StackSky.MIN_SKY_POINTS - 1))
        assertNull(StackSky.balance(null, remembered, cameraR, cameraB, skyPoints = 0))
        assertNotNull(StackSky.balance(fit, null, cameraR, cameraB, skyPoints = StackSky.MIN_SKY_POINTS))
    }
}
