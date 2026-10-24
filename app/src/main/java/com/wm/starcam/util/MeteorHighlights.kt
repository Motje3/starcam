package com.wm.starcam.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.wm.starcam.engine.NativeEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Meteor highlights: every meteor a METEOR session caught, cut out of the frame it flashed
 * through - developed like the live preview, upright like the photo, the streak in the middle -
 * with the time it fell. Made right after the stack, before the frames may go, and kept in
 * out/meteors/ with an index of what is there.
 */
object MeteorHighlights {
    private const val DIR = "out/meteors"
    private const val INDEX = "index.json"

    /** A meteor: the frame it was in, and its ends in that frame's half-size pixels (sensor way up). */
    data class Meteor(val frame: Int, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    /** One cut-out, and when its meteor fell (null when the frame's time is not known). */
    data class Cut(val frame: Int, val file: File, val atMs: Long?)

    /** The meteors in a stacked session's report (out/stack_report.json), in the order they fell. */
    fun meteors(session: File): List<Meteor> = runCatching {
        val streaks = JSONObject(File(session, "out/stack_report.json").readText()).optJSONArray("streaks")
            ?: return emptyList()
        (0 until streaks.length()).mapNotNull { i ->
            val s = streaks.optJSONObject(i) ?: return@mapNotNull null
            // 0: a meteor; satellites (1) and planes (2) came back frame after frame.
            if (s.optInt("cls", -1) != 0) return@mapNotNull null
            Meteor(s.optInt("frameIndex", -1), s.optDouble("x0").toFloat(), s.optDouble("y0").toFloat(),
                s.optDouble("x1").toFloat(), s.optDouble("y1").toFloat())
        }.filter { it.frame >= 0 }.sortedBy { it.frame }
    }.getOrDefault(emptyList())

    /** The cut-outs already made for the session's current report, or null when there are none yet. */
    fun kept(session: File): List<Cut>? = runCatching {
        val index = File(session, "$DIR/$INDEX")
        val report = File(session, "out/stack_report.json")
        if (!index.isFile || index.lastModified() < report.lastModified()) return null
        val a = JSONArray(index.readText())
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val f = File(session, "$DIR/${o.optString("file")}").takeIf { it.isFile } ?: return@mapNotNull null
            Cut(o.optInt("frame"), f, o.optLong("atMs").takeIf { it > 0 })
        }
    }.getOrNull()

    /**
     * Cuts out every meteor of the session whose frame is still kept, and writes the index.
     * Blocking: it develops frames. The way up and the start time come from the session itself.
     */
    fun make(session: File): List<Cut> {
        val list = meteors(session)
        if (list.isEmpty()) return emptyList()
        val header = runCatching { JSONObject(File(session, "meta/session.json").readText()) }.getOrNull()
        val result = header?.optJSONObject("result")
        val head = header?.optJSONObject("header")
        val rotation = (result?.optJSONObject("stackConfig") ?: head?.optJSONObject("stackConfig"))
            ?.optInt("rotation", 0)?.coerceAtLeast(0) ?: 0
        val startedAt = head?.optLong("startedAt") ?: 0L
        val dir = File(session, DIR).apply { mkdirs() }
        val dark = File(session, "cache/master_dark.sraw").takeIf { it.isFile }
        // The clock: the first kept frame's sensor time is the session's start.
        val firstNs = File(session, "cache").listFiles { f -> f.name.startsWith("frame_") && f.extension == "sraw" }
            ?.minByOrNull { it.name }?.let { Timelapse.header(it)?.third } ?: 0L
        val cuts = ArrayList<Cut>()
        try {
            for ((k, m) in list.withIndex()) {
                val sraw = File(session, String.format(Locale.US, "cache/frame_%04d.sraw", m.frame))
                val head3 = Timelapse.header(sraw) ?: continue
                val out = File(dir, String.format(Locale.US, "meteor_%04d_%d.jpg", m.frame, k))
                if (!cut(sraw, head3.first, head3.second, dark, rotation, m, out)) continue
                val at = if (startedAt > 0 && firstNs > 0 && head3.third >= firstNs) startedAt + (head3.third - firstNs) / 1_000_000L else null
                cuts += Cut(m.frame, out, at)
            }
        } finally {
            NativeEngine.developFrameRelease()
        }
        val index = JSONArray()
        for (c in cuts) index.put(JSONObject().apply {
            put("file", c.file.name)
            put("frame", c.frame)
            c.atMs?.let { put("atMs", it) }
        })
        runCatching { File(dir, INDEX).writeText(index.toString()) }
        AppLog.i("meteors", "${session.name}: ${list.size} meteor(s), ${cuts.size} cut out")
        return cuts
    }

    /** Where a half-size pixel of the sensor's frame lands once turned clockwise by [rotation]. */
    internal fun turn(x: Float, y: Float, rotation: Int, w: Int, h: Int): Pair<Float, Float> = when (rotation) {
        90 -> (h - 1 - y) to x
        180 -> (w - 1 - x) to (h - 1 - y)
        270 -> y to (w - 1 - x)
        else -> x to y
    }

    /**
     * The crop round a streak from (ax, ay) to (bx, by) in a [w] x [h] picture: room either side,
     * 4:3 the way the streak runs, moved (not squeezed) to stay inside: left, top, right, bottom.
     */
    internal fun cropAround(ax: Float, ay: Float, bx: Float, by: Float, w: Int, h: Int): IntArray {
        val pad = max(48f, 0.35f * hypot(bx - ax, by - ay))
        var left = min(ax, bx) - pad
        var top = min(ay, by) - pad
        var cw = max(ax, bx) + pad - left
        var ch = max(ay, by) + pad - top
        val aspect = if (cw >= ch) 4f / 3f else 3f / 4f
        if (cw / ch < aspect) {
            val nw = ch * aspect
            left -= (nw - cw) / 2
            cw = nw
        } else {
            val nh = cw / aspect
            top -= (nh - ch) / 2
            ch = nh
        }
        cw = min(cw, w.toFloat())
        ch = min(ch, h.toFloat())
        left = left.coerceIn(0f, w - cw)
        top = top.coerceIn(0f, h - ch)
        return intArrayOf(left.roundToInt(), top.roundToInt(), (left + cw).roundToInt(), (top + ch).roundToInt())
    }

    private fun cut(sraw: File, rawW: Int, rawH: Int, dark: File?, rotation: Int, m: Meteor, out: File): Boolean {
        val hw = rawW / 2
        val hh = rawH / 2
        val turned = rotation == 90 || rotation == 270
        val picture = runCatching {
            Bitmap.createBitmap(if (turned) hh else hw, if (turned) hw else hh, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return false
        try {
            if (!NativeEngine.developFrame(sraw.absolutePath, dark?.absolutePath ?: "", rotation, picture)) return false
            val (ax, ay) = turn(m.x0, m.y0, rotation, hw, hh)
            val (bx, by) = turn(m.x1, m.y1, rotation, hw, hh)
            val crop = cropAround(ax, ay, bx, by, picture.width, picture.height).let { Rect(it[0], it[1], it[2], it[3]) }
            val scale = min(1f, 960f / max(crop.width(), crop.height()))
            val cutW = (crop.width() * scale).roundToInt().coerceAtLeast(1)
            val cutH = (crop.height() * scale).roundToInt().coerceAtLeast(1)
            val piece = Bitmap.createBitmap(cutW, cutH, Bitmap.Config.ARGB_8888)
            try {
                Canvas(piece).drawBitmap(picture, crop, Rect(0, 0, cutW, cutH), Paint(Paint.FILTER_BITMAP_FLAG))
                return out.outputStream().use { piece.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            } finally {
                piece.recycle()
            }
        } catch (t: Throwable) {
            AppLog.e("meteors", "cut-out of frame ${m.frame} failed", t)
            return false
        } finally {
            picture.recycle()
        }
    }
}
