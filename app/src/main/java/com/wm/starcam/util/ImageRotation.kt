package com.wm.starcam.util

import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Which way up a photo is. Frames are stacked as the sensor delivers them; the finished photo,
 * the master TIFF and the DNGs are turned upright from the phone's attitude when the burst
 * started. Before this, a photo shot with the phone upright came out lying on its side.
 */
object ImageRotation {

    /**
     * The phone's rotation from an accelerometer reading, the way OrientationEventListener reports
     * it: 0 upright in portrait, 90 with its left side up, 180 upside down, 270 with its right
     * side up. Null when the phone lies too flat to tell (pointing at the zenith).
     */
    fun deviceOrientationDeg(ax: Float, ay: Float, az: Float): Int? {
        val x = -ax
        val y = -ay
        val z = -az
        // OrientationEventListener's own test: trust the angle only when the screen-plane part
        // of gravity is at least half the part along the screen's normal (about 27 degrees off flat).
        if ((x * x + y * y) * 4f < z * z) return null
        val angle = Math.toDegrees(atan2(-y, x).toDouble()).roundToInt()
        return ((90 - angle) % 360 + 360) % 360
    }

    /**
     * Clockwise turn (0, 90, 180, 270) that makes a back-camera frame upright. [sensorOrientation]
     * is CameraCharacteristics.SENSOR_ORIENTATION. A phone held flat counts as upright in
     * portrait: the photo then looks like the (portrait) viewfinder did.
     */
    fun forBackCamera(sensorOrientation: Int, deviceOrientationDeg: Int?): Int {
        val device = ((deviceOrientationDeg ?: 0) + 45) / 90 * 90
        return ((sensorOrientation + device) % 360 + 360) % 360
    }

    /** EXIF orientation (ExifInterface.ORIENTATION_*) for a clockwise turn, for the DNGs. */
    fun exifOrientation(rotation: Int): Int = when (rotation) {
        90 -> 6    // ORIENTATION_ROTATE_90
        180 -> 3   // ORIENTATION_ROTATE_180
        270 -> 8   // ORIENTATION_ROTATE_270
        else -> 1  // ORIENTATION_NORMAL
    }
}
