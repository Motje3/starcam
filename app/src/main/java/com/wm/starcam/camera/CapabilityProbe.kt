package com.wm.starcam.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build

/**
 * Dumps every Camera2 capability StarCam cares about, for every camera, as a human-readable
 * report (PLAN.md §3.1). This tells us exactly what OnePlus exposes to third-party apps.
 */
object CapabilityProbe {

    fun buildReport(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("StarCam capability probe")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("=".repeat(46))
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = runCatching { manager.cameraIdList }.getOrElse {
            sb.appendLine("!! cameraIdList failed: $it")
            return sb.toString()
        }
        for (id in ids) {
            sb.appendLine()
            sb.appendLine("--- Camera $id ---")
            // Not `continue` inside getOrElse: a non-local jump out of an inline lambda is still
            // an experimental Kotlin feature and fails the build.
            val ch = runCatching { manager.getCameraCharacteristics(id) }
                .onFailure { sb.appendLine("  !! characteristics failed: $it") }
                .getOrNull()
            if (ch == null) continue
            probeOne(sb, ch)
            probeExtensions(sb, manager, id)
        }
        val main = CamMeta.pickMainCamera(context)
        sb.appendLine()
        sb.appendLine("=".repeat(46))
        sb.appendLine(
            if (main != null)
                "StarCam will use camera ${main.cameraId}: RAW ${main.rawSize(false)} " +
                    "(full ${main.fullRawSize}), max exposure ${"%.2f".format(main.maxExposureSeconds)}s, " +
                    "ISO ${main.isoRange.lower}-${main.isoRange.upper}"
            else
                "!! No back camera with RAW support found — StarCam cannot capture on this device."
        )
        return sb.toString()
    }

    private fun probeOne(sb: StringBuilder, ch: CameraCharacteristics) {
        fun <T> get(key: CameraCharacteristics.Key<T>): T? = runCatching { ch.get(key) }.getOrNull()

        val facing = when (get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_BACK -> "BACK"
            CameraMetadata.LENS_FACING_FRONT -> "FRONT"
            else -> "EXTERNAL"
        }
        val level = when (get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            else -> "?"
        }
        sb.appendLine("  facing=$facing  hwLevel=$level")

        val caps = get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        sb.appendLine("  MANUAL_SENSOR: ${caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)}")
        sb.appendLine("  RAW:           ${caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)}")
        sb.appendLine("  MANUAL_POST:   ${caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)}")

        get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
            sb.appendLine("  Exposure: ${it.lower / 1e6} ms .. ${"%.2f".format(it.upper / 1e9)} s   <-- KEY VALUE")
        } ?: sb.appendLine("  Exposure range: not exposed")
        get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
            sb.appendLine("  ISO: ${it.lower} .. ${it.upper}" +
                (get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)?.let { a -> " (analog to $a)" } ?: ""))
        } ?: sb.appendLine("  ISO range: not exposed")

        val map = get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
        sb.appendLine("  RAW sizes: ${rawSizes?.joinToString { "${it.width}x${it.height}" } ?: "none"}")
        get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { sb.appendLine("  Pixel array: $it") }

        val cfaNames = arrayOf("RGGB", "GRBG", "GBRG", "BGGR", "RGB", "MONO", "NIR")
        get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)?.let {
            sb.appendLine("  CFA: ${cfaNames.getOrElse(it) { "?($it)" }}")
        }
        get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.let { sb.appendLine("  White level: $it") }
        get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { sb.appendLine("  Black level: $it") }

        get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let {
            val cal = when (get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)) {
                CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "CALIBRATED"
                CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "APPROXIMATE"
                else -> "UNCALIBRATED"
            }
            sb.appendLine("  Focus: minDistance=$it diopters, calibration=$cal")
        }
        get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.let {
            sb.appendLine("  Focal lengths: ${it.joinToString()} mm")
        }
        get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.let {
            sb.appendLine("  Apertures: f/${it.joinToString()}")
        }
        get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { size ->
            sb.appendLine("  Sensor: ${size.width} x ${size.height} mm")
            val px = rawSizes?.maxByOrNull { it.width.toLong() * it.height }
            val focal = get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val aperture = get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
            if (px != null && focal != null && aperture != null && size.width > 0f) {
                val pitch = size.width * 1000f / px.width
                val npf = (35.0 * aperture + 30.0 * pitch) / focal
                sb.appendLine("  Pixel pitch: ${"%.2f".format(pitch)} um")
                sb.appendLine("  NPF trail limit: ${"%.1f".format(npf)} s  <-- max sub-exposure")
            }
        }
        get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)?.let {
            sb.appendLine("  Hot pixel modes: ${it.joinToString()}")
        }
        get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.let {
            sb.appendLine("  NR modes: ${it.joinToString()}")
        }
        get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)?.let {
            sb.appendLine("  Lens shading map modes: ${it.joinToString()}")
        }
    }

    private fun probeExtensions(sb: StringBuilder, manager: CameraManager, id: String) {
        runCatching {
            val ext = manager.getCameraExtensionCharacteristics(id)
            val supported = ext.supportedExtensions
            val night = supported.contains(CameraExtensionCharacteristics.EXTENSION_NIGHT)
            sb.appendLine("  OEM Extensions: ${supported.joinToString().ifEmpty { "none" }}" +
                if (night) "  (NIGHT available!)" else "")
        }.onFailure {
            sb.appendLine("  OEM Extensions: query failed (${it.message})")
        }
    }
}
