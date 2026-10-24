package com.wm.starcam.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import com.wm.starcam.sky.PhotoStars
import com.wm.starcam.sky.PlateSolution
import com.wm.starcam.sky.PlateSolver
import com.wm.starcam.sky.SkyLabels
import com.wm.starcam.sky.StarCatalog
import com.wm.starcam.ui.SkyLabelPainter
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.tan

/**
 * "What's in this photo": the session's photo plate-solved from its own stars, and what that
 * puts in it - constellations, named stars, planets, nebulae and galaxies. Worked out on the
 * phone in a second or two; the answer is kept beside the session (meta/whats_in.json) for the
 * photo it was found for, so it comes back at once and goes when the photo is re-stacked or edited.
 */
object WhatsInPhoto {

    private const val FILE = "whats_in.json"

    /** The photo is solved at about this size: stars are a few pixels, the maths quick. */
    private const val SOLVE_EDGE = 2048

    /** What was found for one photo: the solution and its labels, or nothing ([labels] null). */
    class Found(val labels: SkyLabels?, val solution: PlateSolution?, val stars: Int, val ms: Long)

    /** The answer kept for [photo], if it was worked out for this very file. */
    fun cached(dir: File, photo: File, catalog: StarCatalog, epochMs: Long?): Found? = runCatching {
        val f = File(dir, "meta/$FILE")
        if (!f.isFile) return null
        val o = JSONObject(f.readText())
        if (o.optString("photo") != photo.name || o.optLong("modified") != photo.lastModified()) return null
        val s = o.optJSONObject("solution") ?: return Found(null, null, o.optInt("stars"), o.optLong("ms"))
        val sol = PlateSolution(
            s.getDouble("ra"), s.getDouble("dec"), s.getDouble("scale"), s.getDouble("angle"),
            s.getBoolean("flipped"), s.getDouble("cx"), s.getDouble("cy"), s.getInt("matched"), s.getDouble("rms"),
            s.optDouble("k", 0.0), s.optDouble("kReach2", Double.MAX_VALUE),
        )
        val labels = SkyLabels.build(sol, o.getInt("width"), o.getInt("height"), catalog, epochMs)
        Found(labels, sol, o.optInt("stars"), o.optLong("ms"))
    }.getOrNull()

    /**
     * Solves [photo] of the session in [dir]; [header] is its session.json header (the focal
     * length, the time). Blocking: run it off the main thread.
     */
    fun identify(dir: File, photo: File, header: JSONObject?, catalog: StarCatalog): Found {
        val t0 = System.currentTimeMillis()
        val bmp = decode(photo, SOLVE_EDGE) ?: return Found(null, null, 0, 0)
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        bmp.recycle()
        val stars = PhotoStars.find(argb, w, h)
        val epochMs = header?.optLong("startedAt")?.takeIf { it > 0 }
        var sol: PlateSolution? = null
        var tried = ""
        // A photo of cloud costs every guess in full: the later guesses skip the mirrored search,
        // and after ~12 s the answer is no.
        for ((i, guess) in focalGuesses(header, w, h).withIndex()) {
            if (i > 0 && System.currentTimeMillis() - t0 > 12_000L) break
            val (focal, tolerance) = guess
            tried += String.format(java.util.Locale.US, " %.0f±%.0f%%", focal, tolerance * 100)
            sol = PlateSolver.solve(stars, w, h, catalog, focal, tolerance, tryMirrored = i == 0)
            if (sol != null) break
        }
        val ms = System.currentTimeMillis() - t0
        AppLog.i("whatsin", "${photo.name}: ${stars.size} stars in ${w}x$h, focal tried$tried -> " +
            (sol?.let {
                String.format(java.util.Locale.US, "ra %.2f dec %.2f, %.0f px focal, turn %.1f deg%s, %d matched, rms %.2f px, k %.3f",
                    it.raDeg, it.decDeg, it.scale, Math.toDegrees(it.angle), if (it.flipped) " MIRRORED" else "",
                    it.matched, it.rmsPx, it.k)
            } ?: "not recognised") + " in $ms ms")
        save(dir, photo, w, h, sol, stars.size, ms)
        return Found(sol?.let { SkyLabels.build(it, w, h, catalog, epochMs) }, sol, stars.size, ms)
    }

    /**
     * Focal lengths to try (px at the solving size) and how sure each is: the camera's own, kept
     * with the session since 0.5; for older sessions the fields a phone's main camera has.
     */
    private fun focalGuesses(header: JSONObject?, w: Int, h: Int): List<Pair<Double, Double>> {
        val long = max(w, h).toDouble()
        val rawLong = header?.let { max(it.optInt("rawWidth"), it.optInt("rawHeight")) } ?: 0
        val focalRaw = header?.optDouble("focalPx", Double.NaN) ?: Double.NaN
        // The camera's own: nothing else to try (a photo it does not solve has no sky in it to find).
        if (!focalRaw.isNaN() && focalRaw > 0 && rawLong > 0) return listOf(focalRaw * long / rawLong to 0.05)
        // Across the long side: a main camera's ~74 deg, then the neighbours either way.
        return listOf(74.0, 62.0, 86.0, 50.0).map { fov -> long / 2 / tan(Math.toRadians(fov / 2)) to 0.13 }
    }

    private fun save(dir: File, photo: File, w: Int, h: Int, sol: PlateSolution?, stars: Int, ms: Long) {
        runCatching {
            val o = JSONObject().apply {
                put("photo", photo.name)
                put("modified", photo.lastModified())
                put("width", w)
                put("height", h)
                put("stars", stars)
                put("ms", ms)
                if (sol != null) put("solution", JSONObject().apply {
                    put("ra", sol.raDeg)
                    put("dec", sol.decDeg)
                    put("scale", sol.scale)
                    put("angle", sol.angle)
                    put("flipped", sol.flipped)
                    put("cx", sol.cx)
                    put("cy", sol.cy)
                    put("matched", sol.matched)
                    put("rms", sol.rmsPx)
                    put("k", sol.k)
                    if (sol.kReach2 < Double.MAX_VALUE) put("kReach2", sol.kReach2)
                })
            }
            File(dir, "meta").mkdirs()
            File(dir, "meta/$FILE").writeText(o.toString())
        }.onFailure { AppLog.e("whatsin", "could not keep the answer", it) }
    }

    /** [file] decoded at about [maxEdge] px on its long side (halved until it fits), ARGB. */
    private fun decode(file: File, maxEdge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        })
    }.getOrNull()

    /**
     * The photo with its labels drawn in, at up to 4096 px: written to the share folder and, with
     * [gallery], added to the gallery. Returns the file (null on failure) and the gallery copy.
     */
    fun saveLabelled(
        context: Context, sessionName: String, photo: File, labels: SkyLabels, night: Boolean, gallery: Boolean,
    ): Pair<File?, Uri?> {
        val bmp = decode(photo, 4096) ?: return null to null
        try {
            val canvas = Canvas(bmp)
            val unit = max(bmp.width, bmp.height) / 1000f
            val mark = android.graphics.RectF(0f, bmp.height - 40f * unit, 120f * unit, bmp.height.toFloat())
            SkyLabelPainter(context, night).draw(canvas, labels, bmp.width.toFloat() / labels.width, 0f, 0f, unit,
                bmp.width.toFloat(), bmp.height.toFloat(), listOf(mark))
            // A small mark in the corner, as on the share card.
            val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 255, 255, 255)
                textSize = 13f * unit
                setShadowLayer(2f * unit, 0f, 0f, Color.BLACK)
            }
            canvas.drawText("StarCam", 14f * unit, bmp.height - 14f * unit, brand)
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val out = File(dir, "starcam_${sessionName}_labelled.jpg")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            return out to (if (gallery) toGallery(context, out) else null)
        } catch (t: Throwable) {
            AppLog.e("whatsin", "labelled copy failed", t)
            return null to null
        } finally {
            bmp.recycle()
        }
    }

    private fun toGallery(context: Context, file: File): Uri? = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StarCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
                ?: error("no output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }.onFailure { AppLog.e("whatsin", "gallery copy failed", it) }.getOrNull()
}
