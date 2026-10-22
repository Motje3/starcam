package com.wm.starcam.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The phone's attitude to the photo's turn. An accelerometer reads +9.8 along the axis that
 * points up; for the usual back camera mounted at SENSOR_ORIENTATION 90 the frame is upright
 * only when the phone is held in landscape with its top to the left.
 */
class ImageRotationTest {

    private val g = 9.81f

    @Test
    fun deviceOrientationFollowsOrientationEventListener() {
        assertEquals(0, ImageRotation.deviceOrientationDeg(0f, g, 0f))      // upright portrait
        assertEquals(270, ImageRotation.deviceOrientationDeg(g, 0f, 0f))    // top to the left
        assertEquals(90, ImageRotation.deviceOrientationDeg(-g, 0f, 0f))    // top to the right
        assertEquals(180, ImageRotation.deviceOrientationDeg(0f, -g, 0f))   // upside down
    }

    @Test
    fun tiltedPortraitStillCounts() {
        // Tilted back 60 degrees to aim high, still upright: gravity mostly through the screen.
        val up = g * kotlin.math.cos(Math.toRadians(60.0)).toFloat()
        val back = g * kotlin.math.sin(Math.toRadians(60.0)).toFloat()
        assertEquals(0, ImageRotation.deviceOrientationDeg(0f, up, back))
    }

    @Test
    fun flatOnItsBackIsUnknown() {
        assertNull(ImageRotation.deviceOrientationDeg(0f, 0f, g))
        assertNull(ImageRotation.deviceOrientationDeg(0.5f, 0.5f, g))
    }

    @Test
    fun backCameraTurn() {
        assertEquals(90, ImageRotation.forBackCamera(90, 0))     // portrait: turn a quarter
        assertEquals(0, ImageRotation.forBackCamera(90, 270))    // landscape, top left: as is
        assertEquals(180, ImageRotation.forBackCamera(90, 90))   // landscape, top right
        assertEquals(270, ImageRotation.forBackCamera(90, 180))  // upside down portrait
        assertEquals(90, ImageRotation.forBackCamera(90, null))  // flat: like the viewfinder
    }

    @Test
    fun nearlyLandscapeRoundsToTheNearestQuarter() {
        assertEquals(0, ImageRotation.forBackCamera(90, 280))
        assertEquals(90, ImageRotation.forBackCamera(90, 20))
        assertEquals(90, ImageRotation.forBackCamera(90, 340))
    }

    @Test
    fun exifOrientationTags() {
        assertEquals(1, ImageRotation.exifOrientation(0))
        assertEquals(6, ImageRotation.exifOrientation(90))
        assertEquals(3, ImageRotation.exifOrientation(180))
        assertEquals(8, ImageRotation.exifOrientation(270))
    }
}
