package com.wm.starcam.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import com.wm.starcam.engine.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The timelapse: a session's kept RAW frames (cache/) one after the other - the sky turning over
 * the landscape, cloud coming and going - each developed like the live preview, cross-faded so
 * that even a short session plays smoothly, the time of night in the corner. Made on the phone
 * with [VideoEncoder]; saved to the gallery.
 */
object Timelapse {
    const val VIDEO = "timelapse.mp4"

    /** Fewer frames than this make a slideshow, not a timelapse. */
    const val MIN_FRAMES = 4

    private const val FPS = 30

    /** The video's long side: 1600 x 1200 keeps within what every phone's H.264 encoder takes. */
    private const val LONG_EDGE = 1600
    private const val HOLD_SEC = 1.5

    /** The kept RAW frames in order, less any the phone was bumped in (the view would jump). */
    fun frames(session: File): List<File> {
        val bumped = HashSet<Int>()
        runCatching {
            val frames = JSONObject(File(session, "meta/session.json").readText()).optJSONArray("frames")
            if (frames != null) for (i in 0 until frames.length()) {
                val f = frames.optJSONObject(i) ?: continue
                if (f.optBoolean("bumped")) bumped += f.optInt("index", -1)
            }
        }
        return File(session, "cache").listFiles { f -> f.extension == "sraw" && f.name.startsWith("frame_") }
            ?.sortedBy { it.name }
            ?.filter { (it.nameWithoutExtension.substringAfter("frame_").toIntOrNull() ?: -1) !in bumped }
            ?: emptyList()
    }

    /** Seconds the frames play for: a few for a short session, up to 20 for a whole night. */
    internal fun playSec(n: Int): Double = (n / 8.0).coerceIn(3.0, 20.0)

    // Made outside any screen, like the replay video: leaving the page does not stop it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _making = MutableStateFlow<Map<String, StackReplay.Making>>(emptyMap())
    val making: StateFlow<Map<String, StackReplay.Making>> = _making

    /**
     * Starts making [session]'s timelapse, unless it is already being made. [rotation]: the
     * clockwise turn that makes the photo upright; [startedAt]: when the session began.
     */
    fun make(context: Context, session: File, rotation: Int, startedAt: Long) {
        val key = session.absolutePath
        synchronized(this) {
            if (_making.value[key]?.progress != null) return
            _making.update { it + (key to StackReplay.Making(0f)) }
        }
        val app = context.applicationContext
        scope.launch {
            // Nothing may escape this coroutine: it would take the app down.
            val result = try {
                val t0 = SystemClock.elapsedRealtime()
                val video = File(session, "out/$VIDEO")
                val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                val keep = if (debuggable) File(app.cacheDir, "timelapse_check") else null
                val frames = frames(session)
                val dark = File(session, "cache/master_dark.sraw").takeIf { it.isFile }
                var shown = -1
                val ok = frames.size >= MIN_FRAMES && encode(frames, dark, rotation, startedAt, video, keep) { p ->
                    val pct = (p * 100).toInt()
                    if (pct != shown) {
                        shown = pct
                        _making.update { it + (key to StackReplay.Making(p)) }
                    }
                }
                if (!ok) {
                    StackReplay.Making(null, "The timelapse could not be made on this phone.")
                } else {
                    val uri = VideoEncoder.saveToGallery(app, video, "starcam_${session.name}_timelapse.mp4")
                    AppLog.i("timelapse", "made ${video.name}: ${frames.size} frames${if (dark != null) ", dark taken off" else ""}, " +
                        "${video.length() / 1024} KB in ${SystemClock.elapsedRealtime() - t0} ms, gallery: ${uri ?: "FAILED"}")
                    if (uri != null) StackReplay.Making(null, "Saved to the gallery (Movies › StarCam).", good = true)
                    else StackReplay.Making(null, "Made, but the gallery copy failed: share it from here.")
                }
            } catch (t: Throwable) {
                AppLog.e("timelapse failed", t)
                StackReplay.Making(null, "The timelapse could not be made on this phone.")
            }
            _making.update { it + (key to result) }
        }
    }

    /** A .sraw header's width, height and sensor timestamp (ns), or null when unreadable. */
    internal fun header(f: File): Triple<Int, Int, Long>? = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val b = ByteArray(160)
            raf.readFully(b)
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            if (b[0] != 'S'.code.toByte() || b[1] != 'R'.code.toByte()) return null
            Triple(buf.getInt(8), buf.getInt(12), buf.getLong(104))
        }
    }.getOrNull()

    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /**
     * Encodes [frames] (kept RAW frames, in order) to [out], [dark] taken off each. Blocking;
     * [progress] gets 0..1. The video is read back before it counts; [keep] gets frames of it.
     */
    fun encode(
        frames: List<File>,
        dark: File?,
        rotation: Int,
        startedAt: Long,
        out: File,
        keep: File? = null,
        progress: (Float) -> Unit = {},
    ): Boolean {
        val n = frames.size
        if (n < 2) return false
        val first = header(frames[0]) ?: return false
        // Half the RAW (2 x 2 superpixels), turned upright, at most LONG_EDGE.
        val hw = first.first / 2
        val hh = first.second / 2
        val turned = (rotation.coerceAtLeast(0) / 90) % 2 == 1
        val rw = if (turned) hh else hw
        val rh = if (turned) hw else hh
        val s = min(1.0, LONG_EDGE / max(rw, rh).toDouble())
        // Developed at the frame's own shape; the video, in multiples of 16, crops the few pixels
        // over from the middle rather than fitting it with black bars.
        val dw = (rw * s).roundToInt()
        val dh = (rh * s).roundToInt()
        val w = dw / 16 * 16
        val h = dh / 16 * 16
        if (w < 160 || h < 160) return false
        val crop = android.graphics.Rect((dw - w) / 2, (dh - h) / 2, (dw - w) / 2 + w, (dh - h) / 2 + h)
        val whole = android.graphics.Rect(0, 0, w, h)

        // The time of night of each frame: the session's start plus the sensor's clock since frame 1.
        val stamps = frames.map { header(it)?.third ?: 0L }
        val clocked = stamps.zipWithNext().all { (a, b) -> b > a } && stamps[0] > 0
        val zone = ZoneId.systemDefault()
        fun clock(i: Int): String? = if (!clocked) null else
            Instant.ofEpochMilli(startedAt + (stamps[i] - stamps[0]) / 1_000_000L).atZone(zone).format(CLOCK)
        val minutes = if (clocked) ((stamps[n - 1] - stamps[0]) / 60_000_000_000.0).roundToInt() else 0
        val caption = if (minutes >= 2) "$n frames · $minutes minutes of sky" else "$n frames"

        val developed = HashMap<Int, Bitmap>()
        fun frame(i: Int): Bitmap? {
            developed[i]?.let { return it }
            // Two are needed at a time; older ones go.
            developed.keys.filter { it < i - 1 }.forEach { k -> developed.remove(k)?.recycle() }
            val b = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
            if (!NativeEngine.developFrame(frames[i].absolutePath, dark?.absolutePath ?: "", rotation.coerceAtLeast(0), b)) {
                b.recycle()
                AppLog.e("timelapse", "frame ${frames[i].name} could not be developed")
                return null
            }
            developed[i] = b
            return b
        }
        val fade = Paint(Paint.FILTER_BITMAP_FLAG)
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
        val play = playSec(n)
        val total = ((play + HOLD_SEC) * FPS).roundToInt()
        try {
            return VideoEncoder.encode(out, w, h, FPS, total, keep, progress) { q, canvas ->
                val t = q / FPS.toDouble()
                val pos = min(1.0, t / play) * (n - 1)
                val a = floor(pos).toInt().coerceIn(0, n - 1)
                val f = (pos - a).toFloat()
                frame(a)?.let { canvas.drawBitmap(it, crop, whole, null) }
                if (a + 1 < n && f > 0f) frame(a + 1)?.let {
                    fade.alpha = (f * 255).roundToInt()
                    canvas.drawBitmap(it, crop, whole, fade)
                }
                val pad = h * 0.045f
                canvas.drawText("STARCAM", pad, pad + brand.textSize, brand)
                val label = if (t >= play) caption else clock((pos + 0.5).toInt().coerceIn(0, n - 1))
                if (label != null) canvas.drawText(label, pad, h - pad, text)
            }
        } finally {
            developed.values.forEach { it.recycle() }
            NativeEngine.developFrameRelease()
        }
    }
}
