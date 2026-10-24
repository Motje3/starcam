package com.wm.starcam.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import com.wm.starcam.session.stackPhoto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * "Watch it build up": the live stack as it grew during the session, kept as one small JPEG per
 * frame (replay/0001.jpg, ...), and a short video made from them that dissolves from the first
 * noisy frame through the growing stack into the finished photo. Encoded on the phone with the
 * platform's H.264 encoder ([VideoEncoder]); nothing leaves the device unless the user shares it.
 */
object StackReplay {
    const val DIR = "replay"
    const val VIDEO = "stack_replay.mp4"

    /**
     * A long session keeps every frame up to this many, then every second one, and so on: at
     * 100-400 KB a picture (noise compresses badly), 6-24 MB at most, and still enough pictures
     * for an even dissolve through the video's few seconds.
     */
    internal const val MAX_FRAMES = 60
    private const val FPS = 30
    private const val BUILD_SEC_PER_FRAME = 0.25
    private const val BUILD_MIN_SEC = 3.0
    private const val BUILD_MAX_SEC = 8.0
    private const val HOLD_SEC = 0.4
    private const val DISSOLVE_SEC = 1.2
    private const val END_SEC = 2.6

    /** In frame order: past frame 9999 the names no longer sort by themselves. */
    fun frames(session: File): List<File> =
        File(session, DIR).listFiles { f -> f.extension == "jpg" }
            ?.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE } ?: emptyList()

    /** A session's video being made ([progress] 0..1), or how the last attempt ended. */
    data class Making(val progress: Float?, val message: String? = null, val good: Boolean = false)

    // The video is made outside any screen: leaving the result for the session page, or the
    // page itself, neither stops it nor lets a second one write over the same files.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _making = MutableStateFlow<Map<String, Making>>(emptyMap())
    val making: StateFlow<Map<String, Making>> = _making

    /** Starts making [session]'s video, unless it is already being made. */
    fun make(context: Context, session: File, caption: String) {
        val key = session.absolutePath
        synchronized(this) {
            if (_making.value[key]?.progress != null) return
            _making.update { it + (key to Making(0f)) }
        }
        val app = context.applicationContext
        scope.launch {
            // Nothing may escape this coroutine: it would take the app down, mid-session even.
            val result = try {
                val t0 = SystemClock.elapsedRealtime()
                val video = File(session, "out/$VIDEO")
                // Debug builds keep the frames read back from the video, for a look from the PC.
                val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                val keep = if (debuggable) File(app.cacheDir, "replay_check") else null
                val n = frames(session)
                var shown = -1
                val ok = encode(n, stackPhoto(session), caption, video, keep) { p ->
                    val pct = (p * 100).toInt()
                    if (pct != shown) {
                        shown = pct
                        _making.update { it + (key to Making(p)) }
                    }
                }
                if (!ok) {
                    Making(null, "The video could not be made on this phone.")
                } else {
                    val uri = VideoEncoder.saveToGallery(app, video, "starcam_${session.name}_replay.mp4")
                    AppLog.i("replay", "made ${video.name}: ${n.size} frames, ${video.length() / 1024} KB in " +
                        "${SystemClock.elapsedRealtime() - t0} ms, gallery: ${uri ?: "FAILED"}")
                    if (uri != null) Making(null, "Saved to the gallery (Movies › StarCam).", good = true)
                    else Making(null, "Made, but the gallery copy failed: share it from here.")
                }
            } catch (t: Throwable) {
                AppLog.e("stack replay failed", t)
                Making(null, "The video could not be made on this phone.")
            }
            _making.update { it + (key to result) }
        }
    }

    /**
     * Keeps the live stack after frame [index] (from 1). Blocking: call it off the capture path.
     * Past [MAX_FRAMES] only every second frame is kept from then on, and every second one of
     * those already kept goes, so a night-long trails session stays small.
     */
    fun saveFrame(session: File, index: Int, bitmap: Bitmap): Boolean = try {
        val dir = File(session, DIR).apply { mkdirs() }
        val kept = frames(session).associateBy { it.nameWithoutExtension.toIntOrNull() ?: 0 }
        val drop = toDrop(kept.keys.toList(), index)
        if (drop == null) {
            false
        } else {
            drop.forEach { kept[it]?.delete() }
            val out = File(dir, String.format(Locale.US, "%04d.jpg", index))
            val part = File(dir, out.name + ".part")
            val ok = part.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            ok && part.renameTo(out)
        }
    } catch (t: Throwable) {
        AppLog.e("replay frame $index not kept", t)
        false
    }

    /**
     * Which of the [kept] pictures go when the one after frame [index] comes; null when that one
     * is not kept itself. Full, what is there is thinned to the new spacing.
     */
    internal fun toDrop(kept: List<Int>, index: Int): List<Int>? {
        val step = keepStep(index)
        if (index % step != 0) return null
        return if (kept.size >= MAX_FRAMES) kept.filter { it % step != 0 } else emptyList()
    }

    /** 1 while the session is short, then 2, 4, ... so no more than [MAX_FRAMES] are kept. */
    internal fun keepStep(index: Int): Int {
        var step = 1
        while (index > MAX_FRAMES * step) step *= 2
        return step
    }

    /** Seconds the video lasts for [n] replay frames (with the closing photo). */
    internal fun durationSec(n: Int): Double =
        buildSec(n) + HOLD_SEC + DISSOLVE_SEC + END_SEC

    private fun buildSec(n: Int): Double =
        (n * BUILD_SEC_PER_FRAME).coerceIn(BUILD_MIN_SEC, BUILD_MAX_SEC)

    /**
     * Encodes the replay to [out] (MP4, H.264, the frames' size). [photo] is the closing shot,
     * [caption] its line of text (frames, exposure). Blocking; [progress] gets 0..1. The video is
     * read back before it counts; [keep] receives the frames read, for a look from the PC.
     */
    fun encode(
        frames: List<File>,
        photo: File?,
        caption: String,
        out: File,
        keep: File? = null,
        progress: (Float) -> Unit = {},
    ): Boolean {
        if (frames.size < 2) return false
        // The size of the first picture that can be read.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        for (f in frames) {
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) break
        }
        // Encoders want even sizes, most of them multiples of 16.
        val w = bounds.outWidth / 16 * 16
        val h = bounds.outHeight / 16 * 16
        if (w < 160 || h < 160) {
            AppLog.e("stack replay: no readable pictures (${frames.size} listed)")
            return false
        }

        val loaded = HashMap<Int, Bitmap>()
        val finalShot = photo?.takeIf { it.isFile }?.let { decodeFit(it, w, h) }
        try {
            val n = frames.size
            val build = buildSec(n)
            val videoFrames = (durationSec(n) * FPS).roundToInt()
            val draw = Paint(Paint.FILTER_BITMAP_FLAG)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                textSize = h * 0.032f
                setShadowLayer(h * 0.006f, 0f, 0f, Color.argb(160, 0, 0, 0))
            }
            val brand = Paint(text).apply {
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                letterSpacing = 0.12f
                alpha = 200
            }
            fun frame(i: Int): Bitmap? = loaded.getOrPut(i) {
                // Two are needed at a time; older ones go.
                loaded.keys.filter { it < i - 1 }.forEach { k -> loaded.remove(k)?.recycle() }
                decodeFit(frames[i], w, h) ?: return null
            }
            return VideoEncoder.encode(out, w, h, FPS, videoFrames, keep, progress) { queued, canvas ->
                fun drawFit(b: Bitmap, alpha: Int) {
                    draw.alpha = alpha
                    canvas.drawBitmap(b, null, fitRect(b.width, b.height, w, h), draw)
                }
                val t = queued / FPS.toDouble()
                val label: String?
                if (t < build) {
                    val pos = t / build * (n - 1)
                    val a = floor(pos).toInt().coerceIn(0, n - 1)
                    val f = (pos - a).toFloat()
                    frame(a)?.let { drawFit(it, 255) }
                    if (a + 1 < n && f > 0f) frame(a + 1)?.let { drawFit(it, (f * 255).roundToInt()) }
                    label = frameLabel(frames[(pos + 0.5).toInt().coerceIn(0, n - 1)])
                } else {
                    frame(n - 1)?.let { drawFit(it, 255) }
                    val d = ((t - build - HOLD_SEC) / DISSOLVE_SEC).coerceIn(0.0, 1.0)
                    if (d > 0.0 && finalShot != null) drawFit(finalShot, (d * 255).roundToInt())
                    label = if (d >= 1.0 || finalShot == null) caption else frameLabel(frames[n - 1])
                }
                val pad = h * 0.045f
                canvas.drawText("STARCAM", pad, pad + brand.textSize, brand)
                if (!label.isNullOrEmpty()) canvas.drawText(label, pad, h - pad, text)
            }
        } finally {
            finalShot?.recycle()
            loaded.values.forEach { it.recycle() }
        }
    }

    private fun frameLabel(f: File): String {
        val n = f.nameWithoutExtension.toIntOrNull() ?: return ""
        return if (n == 1) "1 frame" else "$n frames stacked"
    }

    /** [srcW] x [srcH] fitted into [w] x [h], centred. */
    private fun fitRect(srcW: Int, srcH: Int, w: Int, h: Int): RectF {
        val s = minOf(w / srcW.toFloat(), h / srcH.toFloat())
        val dw = srcW * s
        val dh = srcH * s
        return RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
    }

    /** Decodes [f] no larger than needed for [w] x [h]. */
    private fun decodeFit(f: File, w: Int, h: Int): Bitmap? = runCatching {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) return null
        var sample = 1
        while (b.outWidth / (sample * 2) >= w && b.outHeight / (sample * 2) >= h) sample *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
