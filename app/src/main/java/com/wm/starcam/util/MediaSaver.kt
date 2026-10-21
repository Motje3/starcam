package com.wm.starcam.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.nio.ByteBuffer

/** Converts the engine's final_rgb.raw into a JPEG (session copy + gallery via MediaStore). */
object MediaSaver {

    /**
     * [rgbFile]: width*height*3 bytes, interleaved RGB (engine output). Writes [jpegOut], sets
     * EXIF, inserts a copy into Pictures/StarCam. Returns the MediaStore Uri (null on failure —
     * the session copy may still exist).
     */
    fun saveFinal(
        context: Context,
        rgbFile: File,
        width: Int,
        height: Int,
        jpegOut: File,
        totalExposureSec: Double,
        iso: Int,
        focalMm: Float = 0f,
        fNumber: Float = 0f,
        recipe: String = "",     // e.g. "20x8s ISO800, winsorized sigma-clip stack"
    ): Uri? {
        val bitmap = rgbToBitmap(rgbFile, width, height) ?: return null
        try {
            jpegOut.parentFile?.mkdirs()
            jpegOut.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            runCatching {
                val exif = ExifInterface(jpegOut.absolutePath)
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "StarCam")
                exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, totalExposureSec.toString())
                exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, iso.toString())
                exif.setAttribute(ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER)
                exif.setAttribute(ExifInterface.TAG_MODEL, android.os.Build.MODEL)
                if (focalMm > 0f) {
                    // Rational format: value/divisor.
                    exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH,
                        "${(focalMm * 100).toInt()}/100")
                }
                if (fNumber > 0f) {
                    exif.setAttribute(ExifInterface.TAG_F_NUMBER, fNumber.toString())
                }
                val now = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
                exif.setAttribute(ExifInterface.TAG_DATETIME, now)
                if (recipe.isNotEmpty()) {
                    exif.setAttribute(ExifInterface.TAG_USER_COMMENT, recipe)
                    exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, recipe)
                }
                exif.saveAttributes()
            }
            // Gallery copy through MediaStore (no storage permission needed for own inserts).
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, jpegOut.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StarCam")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    jpegOut.inputStream().use { it.copyTo(out) }
                }
            }
            return uri
        } catch (t: Throwable) {
            AppLog.e("saveFinal failed", t)
            return null
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Streams the engine's interleaved-RGB file into a Bitmap row block by row block. Reading the
     * whole file plus a full IntArray plus the Bitmap at once needs ~11 bytes/px, which blows up on
     * a full-res (50 MP) stack; this holds the Bitmap plus one small row-block buffer.
     */
    fun rgbToBitmap(rgbFile: File, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val expected = width.toLong() * height.toLong() * 3L
        if (rgbFile.length() < expected) {
            AppLog.e("final_rgb.raw too small: ${rgbFile.length()} for ${width}x$height")
            return null
        }
        var bitmap: Bitmap? = null
        return try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // Process in blocks of at most ~4M pixels so the transient buffers stay bounded.
            val rowsPerBlock = (4_000_000 / width).coerceIn(1, height)
            val rowBytes = ByteArray(width * 3 * rowsPerBlock)
            val pixels = IntArray(width * rowsPerBlock)
            rgbFile.inputStream().buffered().use { input ->
                var y = 0
                while (y < height) {
                    val rows = minOf(rowsPerBlock, height - y)
                    val need = width * 3 * rows
                    var read = 0
                    while (read < need) {
                        val n = input.read(rowBytes, read, need - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read < need) {
                        AppLog.e("final_rgb.raw truncated at row $y")
                        return null
                    }
                    var src = 0
                    for (i in 0 until width * rows) {
                        val r = rowBytes[src].toInt() and 0xFF
                        val g = rowBytes[src + 1].toInt() and 0xFF
                        val b = rowBytes[src + 2].toInt() and 0xFF
                        pixels[i] = -0x1000000 or (r shl 16) or (g shl 8) or b
                        src += 3
                    }
                    bitmap.setPixels(pixels, 0, width, 0, y, width, rows)
                    y += rows
                }
            }
            bitmap
        } catch (t: Throwable) {
            AppLog.e("rgbToBitmap failed", t)
            bitmap?.recycle()
            null
        }
    }
}
