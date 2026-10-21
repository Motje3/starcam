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

    /** What [saveFinal] managed: the session's JPEG, and the gallery copy (null when that failed). */
    data class Saved(val jpegWritten: Boolean, val galleryUri: Uri?)

    /**
     * [rgbFile]: width*height*3 bytes, interleaved RGB (engine output). Writes [jpegOut], sets
     * EXIF, inserts a copy into Pictures/StarCam. A JPEG that cannot be written completely leaves
     * any earlier [jpegOut] as it was.
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
    ): Saved {
        val bitmap = rgbToBitmap(rgbFile, width, height) ?: return Saved(false, null)
        var jpegWritten = false
        try {
            jpegOut.parentFile?.mkdirs()
            // Written beside the target and moved over it only when complete: a full disk used to
            // leave a truncated photo in the session (over the previous one, on a re-stack).
            val part = File(jpegOut.parentFile, jpegOut.name + ".part")
            val encoded = runCatching {
                part.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            }.getOrDefault(false)
            if (!encoded) {
                part.delete()
                error("could not write ${jpegOut.name}")
            }
            if (!part.renameTo(jpegOut)) {
                // A storage layer that will not rename over an existing file.
                part.copyTo(jpegOut, overwrite = true)
                part.delete()
            }
            jpegWritten = true
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
            // Pending until the copy is complete: a copy that failed half-way (a full disk) used
            // to leave a broken photo in the gallery.
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, jpegOut.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StarCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("the gallery refused the photo")
            try {
                val out = resolver.openOutputStream(uri) ?: error("no output stream for $uri")
                out.use { o -> jpegOut.inputStream().use { it.copyTo(o) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null, null)
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
            return Saved(true, uri)
        } catch (t: Throwable) {
            AppLog.e("saveFinal failed", t)
            return Saved(jpegWritten, null)
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
