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
import com.wm.starcam.sky.SkyLabels
import com.wm.starcam.sky.SkyRecord
import com.wm.starcam.sky.SkySolve
import com.wm.starcam.sky.SkyStars
import com.wm.starcam.sky.StarCatalog
import com.wm.starcam.ui.SkyLabelPainter
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/**
 * "What's in this photo": the session's photo plate-solved from its own stars, and what that
 * puts in it - constellations, named stars, planets, nebulae and galaxies. A stacked photo is
 * solved from the stars its stack verified against the sky's motion (out/stars.json) - usually
 * already while it was stacked (StackCallback) - and any other from the stars found in the JPEG.
 * Worked out on the phone in a second or two; the answer is kept beside the session
 * (meta/whats_in.json) for the photo it was found for, so it comes back at once and goes when the
 * photo is re-stacked or edited.
 */
object WhatsInPhoto {

    private const val FILE = "whats_in.json"

    /** whats_in.json's "source": solved from the stack's stars, or from the finished photo's. */
    const val SOURCE_STACK = "stack"
    const val SOURCE_PHOTO = "photo"

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
        val sol = SkyRecord.solutionFrom(s)
        val labels = SkyLabels.build(sol, o.getInt("width"), o.getInt("height"), catalog, epochMs)
        Found(labels, sol, o.optInt("stars"), o.optLong("ms"))
    }.getOrNull()

    /**
     * Solves [photo] of the session in [dir]; [header] is its session.json header (the focal
     * length, the time). Blocking: run it off the main thread.
     */
    fun identify(dir: File, photo: File, header: JSONObject?, catalog: StarCatalog): Found {
        val epochMs = header?.optLong("startedAt")?.takeIf { it > 0 }
        // Solved while it was stacked: the answer is there already.
        cached(dir, photo, catalog, epochMs)?.takeIf { it.labels != null }?.let { return it }
        val bounds = bounds(photo) ?: return Found(null, null, 0, 0)
        val sample = sampleFor(bounds.first, bounds.second, SOLVE_EDGE)
        val focalRaw = header?.optDouble("focalPx", Double.NaN)?.takeIf { it.isFinite() && it > 0 }
        val rawLong = header?.let { max(it.optInt("rawWidth"), it.optInt("rawHeight")) } ?: 0
        // The stack's own stars, when they belong to this very picture.
        stackStars(dir, photo, bounds.first, bounds.second)?.let { sky ->
            val t0 = System.currentTimeMillis()
            val w = (bounds.first + sample - 1) / sample
            val h = (bounds.second + sample - 1) / sample
            val stars = sky.imageStars(sky.forSolving(), w.toDouble() / sky.width)
            val out = SkySolve.solve(stars, w, h, catalog, SkySolve.focalGuesses(focalRaw, rawLong, w, h),
                mirrored = true, giveUpMs = 12_000L, t0Ms = t0)
            val ms = System.currentTimeMillis() - t0
            log(photo, "the stack's", stars.size, w, h, out, ms)
            val sol = out.solution
            if (sol != null) {
                save(dir, photo, w, h, sol, stars.size, ms, SOURCE_STACK)
                return Found(SkyLabels.build(sol, w, h, catalog, epochMs), sol, stars.size, ms)
            }
        }
        val t0 = System.currentTimeMillis()
        val bmp = decode(photo, SOLVE_EDGE) ?: return Found(null, null, 0, 0)
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        bmp.recycle()
        val stars = PhotoStars.find(argb, w, h)
        // A photo of cloud costs every guess in full: the later guesses skip the mirrored search,
        // and after ~12 s the answer is no.
        val out = SkySolve.solve(stars, w, h, catalog, SkySolve.focalGuesses(focalRaw, rawLong, w, h),
            mirrored = true, giveUpMs = 12_000L, t0Ms = t0)
        val sol = out.solution
        val ms = System.currentTimeMillis() - t0
        log(photo, "the photo's", stars.size, w, h, out, ms)
        save(dir, photo, w, h, sol, stars.size, ms, SOURCE_PHOTO)
        return Found(sol?.let { SkyLabels.build(it, w, h, catalog, epochMs) }, sol, stars.size, ms)
    }

    private fun log(photo: File, whose: String, stars: Int, w: Int, h: Int, out: SkySolve.Outcome, ms: Long) {
        AppLog.i("whatsin", "${photo.name}: $stars of $whose stars in ${w}x$h, focal tried${out.tried} -> " +
            (out.solution?.let {
                String.format(java.util.Locale.US, "ra %.2f dec %.2f, %.0f px focal, turn %.1f deg%s, %d matched, rms %.2f px, k %.3f",
                    it.raDeg, it.decDeg, it.scale, Math.toDegrees(it.angle), if (it.flipped) " MIRRORED" else "",
                    it.matched, it.rmsPx, it.k)
            } ?: "not recognised") + " in $ms ms")
    }

    /**
     * The session's out/stars.json, when it describes [photo] ([w] x [h]): the stack's own JPEG,
     * or a studio edit of it that kept its turn - the same picture at one scale. Null for anything
     * else (an edit turned in the studio, a session stacked before the stars were written, one not
     * stacked at all).
     */
    private fun stackStars(dir: File, photo: File, w: Int, h: Int): SkyStars? {
        val sky = SkyStars.read(File(dir, "out/stars.json")) ?: return null
        if (sky.scaleTo(w, h) == null) return null
        if (photo.name.endsWith("_edit.jpg")) {
            // Turned half round, it is the same size but not the same picture.
            val turn = runCatching { JSONObject(File(dir, "out/edit.json").readText()).optInt("rotation", 0) }.getOrNull()
            if (turn != 0) return null
        }
        return sky
    }

    /**
     * Keeps an answer found for [photo] elsewhere - while its stack was developed, from the
     * stack's stars ([sol] at [w] x [h], from [stars] stars in [ms]) - as if it had been asked for.
     */
    fun keep(dir: File, photo: File, w: Int, h: Int, sol: PlateSolution, stars: Int, ms: Long, source: String) =
        save(dir, photo, w, h, sol, stars, ms, source)

    private fun save(dir: File, photo: File, w: Int, h: Int, sol: PlateSolution?, stars: Int, ms: Long, source: String) {
        runCatching {
            val o = JSONObject().apply {
                put("photo", photo.name)
                put("modified", photo.lastModified())
                put("width", w)
                put("height", h)
                put("stars", stars)
                put("ms", ms)
                put("source", source)
                if (sol != null) put("solution", SkyRecord.solutionJson(sol))
            }
            File(dir, "meta").mkdirs()
            File(dir, "meta/$FILE").writeText(o.toString())
        }.onFailure { AppLog.e("whatsin", "could not keep the answer", it) }
    }

    private fun bounds(file: File): Pair<Int, Int>? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) null else o.outWidth to o.outHeight
    }.getOrNull()

    /** The power of two that brings a [w] x [h] picture to about [maxEdge] on its long side. */
    private fun sampleFor(w: Int, h: Int, maxEdge: Int): Int {
        var sample = 1
        while (max(w, h) / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /** [file] decoded at about [maxEdge] px on its long side (halved until it fits), ARGB. */
    private fun decode(file: File, maxEdge: Int): Bitmap? = runCatching {
        val (w, h) = bounds(file) ?: return null
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sampleFor(w, h, maxEdge)
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
            canvas.drawText("Stelli", 14f * unit, bmp.height - 14f * unit, brand)
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val out = File(dir, "stelli_${sessionName}_labelled.jpg")
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
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Stelli")
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
