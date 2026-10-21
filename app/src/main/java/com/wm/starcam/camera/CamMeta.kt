package com.wm.starcam.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.util.Range
import android.util.Size
import com.wm.starcam.util.AppLog
import kotlin.math.abs

/**
 * Lens vignetting profile, ready for the native engine: RGB gains on a low-resolution grid,
 * row-major and channel-interleaved, normalized so the brightest node is exactly 1.0.
 *
 * Its existence means "worth correcting" — every path that produces one has already decided the
 * map is real and usable, so consumers never have to re-validate.
 */
data class ShadingMapData(
    val cols: Int,
    val rows: Int,
    val gains: FloatArray,
    val maxGain: Float,
)

/**
 * Snapshot of everything StarCam needs to know about the chosen (main) camera. Built once at
 * startup; every capture decision adapts to these values at runtime (PLAN.md §3.1-§3.2).
 */
data class CamInfo(
    val cameraId: String,
    val characteristics: CameraCharacteristics,
    val hasRaw: Boolean,
    val hasManualSensor: Boolean,
    val fullRawSize: Size,
    val binnedRawSize: Size?,           // smallest RAW size >= 9MP if the sensor offers several
    val isoRange: Range<Int>,
    val exposureRangeNs: Range<Long>,
    val minFocusDistance: Float,        // diopters; 0 = fixed focus
    val focusCalibrated: Boolean,
    val cfa: Int,                       // 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR (matches native CFA enum)
    val whiteLevel: Int,
    val blackLevelRaster: FloatArray,   // 2x2 raster order: idx = (row%2)*2 + (col%2)
    val focalLengthMm: Float,
    val apertureF: Float,
    val sensorWidthMm: Float,           // physical active-area width; 0 if not reported
) {
    /** Micrometres per pixel at full RAW width; 0 when the sensor size isn't reported. */
    val pixelPitchUm: Float
        get() = if (sensorWidthMm > 0f && fullRawSize.width > 0)
            sensorWidthMm * 1000f / fullRawSize.width else 0f

    /**
     * Longest exposure that keeps stars point-like, via the NPF rule:
     *   t = (35 * N + 30 * p) / f   — N = f-number, p = pixel pitch in µm, f = focal length in mm.
     * Stricter and far more honest than the old "500 rule", and it accounts for this sensor's
     * pixel size. Falls back to 8 s when the lens data is missing, and is clamped to something
     * sane so a wide lens can't ask for a 2-minute sub-exposure.
     */
    fun trailLimitSeconds(): Double {
        if (focalLengthMm <= 0f || apertureF <= 0f || pixelPitchUm <= 0f) return 8.0
        val t = (35.0 * apertureF + 30.0 * pixelPitchUm) / focalLengthMm
        return t.coerceIn(2.0, 30.0)
    }

    val maxExposureSeconds: Double get() = exposureRangeNs.upper / 1e9

    fun rawSize(fullRes: Boolean): Size = if (fullRes || binnedRawSize == null) fullRawSize else binnedRawSize

    /**
     * A camera-supported preview size matching the sensor's aspect ratio, at most ~1080p. The
     * SurfaceView buffer must be set to this: left at the view's own size the preview is stretched
     * (sensor is 4:3, screen is ~20:9) and shows a different field of view than what is captured.
     */
    fun previewSize(): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            ?: return Size(1440, 1080)
        val target = fullRawSize.width.toDouble() / fullRawSize.height
        return sizes
            .filter { it.width <= 1920 && it.height <= 1920 }
            .minByOrNull {
                val ar = it.width.toDouble() / it.height
                // Prefer matching aspect ratio, then the largest area within the cap.
                kotlin.math.abs(ar - target) * 1000.0 - (it.width.toLong() * it.height) / 1e6
            } ?: sizes.minByOrNull { it.width.toLong() * it.height } ?: Size(1440, 1080)
    }
}

object CamMeta {

    /**
     * Main sensor = back camera with RAW + MANUAL_SENSOR and the largest pixel array.
     * Falls back to any back camera with RAW, then null (UI explains the phone can't do this).
     */
    fun pickMainCamera(context: Context): CamInfo? {
        // getSystemService and cameraIdList both throw on some devices/states; this runs inside a
        // coroutine, where an escaping exception would kill the process instead of surfacing the
        // "no usable camera" message.
        val manager = runCatching {
            context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        }.getOrNull() ?: return null
        val ids = runCatching { manager.cameraIdList }.getOrNull() ?: return null
        var best: CamInfo? = null
        var bestScore = -1L
        for (id in ids) {
            val ch = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            val hasRaw = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            val hasManual = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            if (!hasRaw) continue
            val info = buildInfo(id, ch, hasRaw, hasManual) ?: continue
            val area = info.fullRawSize.width.toLong() * info.fullRawSize.height
            val score = area + if (hasManual) 1_000_000_000L else 0L
            if (score > bestScore) {
                bestScore = score
                best = info
            }
        }
        return best
    }

    /**
     * Every RAW-capable back camera, for the lens picker. Ordered: the [pickMainCamera] choice
     * first, then the rest by focal length ascending (ultrawide → tele).
     */
    fun listBackCameras(context: Context): List<CamInfo> {
        val main = pickMainCamera(context) ?: return emptyList()
        val manager = runCatching {
            context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        }.getOrNull() ?: return listOf(main)
        val ids = runCatching { manager.cameraIdList }.getOrNull() ?: return listOf(main)
        val others = mutableListOf<CamInfo>()
        for (id in ids) {
            if (id == main.cameraId) continue
            val ch = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) continue
            val hasManual = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val info = buildInfo(id, ch, hasRaw = true, hasManual = hasManual) ?: continue
            others += info
        }
        // OEMs expose the same physical sensor under multiple camera ids (this phone lists the
        // main sensor twice). Two ids with the same focal length and RAW size are the same glass —
        // keep the first, drop the ghost, or the lens picker shows a duplicate chip.
        val distinct = mutableListOf(main)
        for (cand in others.sortedBy { it.focalLengthMm }) {
            val dup = distinct.any { seen ->
                seen.fullRawSize == cand.fullRawSize &&
                    abs(seen.focalLengthMm - cand.focalLengthMm) <
                    0.02f * maxOf(seen.focalLengthMm, 0.1f)
            }
            if (!dup) distinct += cand
        }
        return distinct
    }

    /** Builds one specific camera id; null when the id is invalid, not back-facing, or lacks RAW. */
    fun pickCamera(context: Context, id: String): CamInfo? {
        val manager = runCatching {
            context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        }.getOrNull() ?: return null
        val ch = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return null
        if (ch.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) return null
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
        if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) return null
        val hasManual = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        return buildInfo(id, ch, hasRaw = true, hasManual = hasManual)
    }

    private fun buildInfo(
        id: String, ch: CameraCharacteristics, hasRaw: Boolean, hasManual: Boolean
    ): CamInfo? {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR) ?: return null
        if (rawSizes.isEmpty()) return null
        val full = rawSizes.maxByOrNull { it.width.toLong() * it.height } ?: return null
        val binned = rawSizes
            .filter { it.width.toLong() * it.height >= 9_000_000L && it != full }
            .minByOrNull { it.width.toLong() * it.height }
        val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?: Range(100, 3200)
        val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: Range(1_000_000L, 100_000_000L)
        val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val focusCal = ch.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION) ==
            CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED
        val cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        if (cfa > 3) return null  // RGB/MONO sensors unsupported
        val white = ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        return CamInfo(
            cameraId = id,
            characteristics = ch,
            hasRaw = hasRaw,
            hasManualSensor = hasManual,
            fullRawSize = full,
            binnedRawSize = binned,
            isoRange = isoRange,
            exposureRangeNs = expRange,
            minFocusDistance = minFocus,
            focusCalibrated = focusCal,
            cfa = cfa,
            whiteLevel = white,
            blackLevelRaster = rasterBlackLevel(ch, white),
            focalLengthMm = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull() ?: 0f,
            apertureF = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.firstOrNull() ?: 0f,
            sensorWidthMm = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 0f,
        )
    }

    /** Black level pattern in 2x2 raster order; falls back to 6% of white level. */
    fun rasterBlackLevel(ch: CameraCharacteristics, whiteLevel: Int): FloatArray {
        val pattern = ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        if (pattern != null) {
            val out = FloatArray(4)
            for (row in 0..1) for (col in 0..1) {
                out[row * 2 + col] = pattern.getOffsetForIndex(col, row).toFloat()
            }
            return out
        }
        val approx = whiteLevel * 0.06f
        return floatArrayOf(approx, approx, approx, approx)
    }

    /**
     * Camera2's RggbChannelVector (R, G-even = green in the red row, G-odd = green in the blue
     * row, B) converted to 2x2 raster-order gains for the given CFA.
     */
    fun rasterWbGains(gains: RggbChannelVector?, cfa: Int): FloatArray {
        if (gains == null) return floatArrayOf(1f, 1f, 1f, 1f)
        // Per-CFA raster color layout, and which row holds red (for G-even vs G-odd).
        val colors = when (cfa) {
            0 -> intArrayOf(0, 1, 1, 2)  // RGGB
            1 -> intArrayOf(1, 0, 2, 1)  // GRBG
            2 -> intArrayOf(1, 2, 0, 1)  // GBRG
            else -> intArrayOf(2, 1, 1, 0)  // BGGR
        }
        val redRow = if (cfa == 0 || cfa == 1) 0 else 1
        val out = FloatArray(4)
        for (idx in 0..3) {
            val row = idx / 2
            out[idx] = when (colors[idx]) {
                0 -> gains.red
                2 -> gains.blue
                else -> if (row == redRow) gains.greenEven else gains.greenOdd
            }
        }
        return out
    }

    /**
     * The lens's vignetting profile from a capture result, reduced to what the engine wants.
     *
     * Returns null in every case where the map must not be used, because "no map" is a silent
     * no-op everywhere downstream:
     *  - the device already applies shading to its RAW output (nothing is missing, and applying
     *    the map on top would brighten the corners twice)
     *  - no map was reported, or it is too small to interpolate
     *  - a value is not finite or is wildly out of range
     *  - the map is effectively flat, which some HALs return when they have nothing to say
     */
    fun shadingMap(cam: CamInfo, result: CaptureResult): ShadingMapData? {
        val applied = runCatching {
            cam.characteristics.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED)
        }.getOrNull()
        if (applied == true) {
            AppLog.d("lens shading: device already corrects RAW — map not used")
            return null
        }
        val map = runCatching {
            result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        }.getOrNull() ?: return null
        val cols = map.columnCount
        val rows = map.rowCount
        if (cols < 2 || rows < 2) return null

        val gains = FloatArray(cols * rows * 3)
        var lo = Float.MAX_VALUE
        var hi = 0f
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                // Channel indices follow the RggbChannelVector convention regardless of the
                // sensor's CFA layout — which is exactly why this is reduced to RGB here instead
                // of being carried through in raster order like the WB gains.
                val rgb = floatArrayOf(
                    map.getGainFactor(RggbChannelVector.RED, c, r),
                    (map.getGainFactor(RggbChannelVector.GREEN_EVEN, c, r) +
                        map.getGainFactor(RggbChannelVector.GREEN_ODD, c, r)) * 0.5f,
                    map.getGainFactor(RggbChannelVector.BLUE, c, r),
                )
                for (i in 0..2) {
                    val v = rgb[i]
                    if (!v.isFinite() || v <= 0f || v > 64f) {
                        AppLog.e("lens shading: gain $v out of range — map discarded")
                        return null
                    }
                    gains[(r * cols + c) * 3 + i] = v
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
            }
        }
        if (!lo.isFinite() || lo <= 0f) return null
        // Normalize to the brightest point so this only ever redistributes brightness across the
        // frame. The ratio is the vignetting; the absolute level is exposure, and not ours.
        for (i in gains.indices) gains[i] /= lo
        val maxGain = hi / lo
        if (maxGain < 1.02f) {
            AppLog.d("lens shading: map is flat (max %.3f) — nothing to correct".format(maxGain))
            return null
        }
        if (maxGain > 16f) {
            AppLog.e("lens shading: implausible max gain %.2f — map discarded".format(maxGain))
            return null
        }
        AppLog.d("lens shading: ${cols}x$rows map, max gain %.2f".format(maxGain))
        return ShadingMapData(cols, rows, gains, maxGain)
    }

    /** ColorSpaceTransform -> row-major float[9]; identity when absent. */
    fun ccmToFloatArray(t: ColorSpaceTransform?): FloatArray {
        if (t == null) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val out = FloatArray(9)
        for (row in 0..2) for (col in 0..2) {
            out[row * 3 + col] = t.getElement(col, row).toFloat()
        }
        return out
    }
}
