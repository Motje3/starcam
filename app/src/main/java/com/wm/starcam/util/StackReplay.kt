package com.wm.starcam.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * "Watch it build up": the live stack as it grew during the session, kept as one small JPEG per
 * frame (replay/0001.jpg, ...), and a short video made from them that dissolves from the first
 * noisy frame through the growing stack into the finished photo. Encoded on the phone with the
 * platform's H.264 encoder; nothing leaves the device unless the user shares it.
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

    fun frames(session: File): List<File> =
        File(session, DIR).listFiles { f -> f.extension == "jpg" }?.sortedBy { it.name } ?: emptyList()

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
     * read back before it counts ([verify]); [keep] receives the frames read, for a look from the PC.
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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(frames.first().absolutePath, bounds)
        // Encoders want even sizes, most of them multiples of 16.
        val w = bounds.outWidth / 16 * 16
        val h = bounds.outHeight / 16 * 16
        if (w < 160 || h < 160) return false

        val part = File(out.parentFile, out.name + ".part")
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        val canvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val loaded = HashMap<Int, Bitmap>()
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                // Night skies are mostly fine noise, which costs bits: generous for the size.
                setInteger(MediaFormat.KEY_BIT_RATE, (w * h * FPS * 0.25).toInt().coerceIn(2_000_000, 12_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = enc
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val mux = MediaMuxer(part.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            val n = frames.size
            val build = buildSec(n)
            val total = durationSec(n)
            val videoFrames = (total * FPS).roundToInt()
            val finalShot = photo?.takeIf { it.isFile }?.let { decodeFit(it, w, h) }
            val canvas = Canvas(canvasBitmap)
            val pixels = IntArray(w * h)
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
            fun drawFit(b: Bitmap, alpha: Int) {
                draw.alpha = alpha
                canvas.drawBitmap(b, null, fitRect(b.width, b.height, w, h), draw)
            }

            val info = MediaCodec.BufferInfo()
            var track = -1
            var queued = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = enc.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        if (queued >= videoFrames) {
                            enc.queueInputBuffer(inIdx, 0, 0, ptsUs(queued), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            // ---- compose video frame [queued]
                            val t = queued / FPS.toDouble()
                            canvas.drawColor(Color.BLACK)
                            var label: String? = null
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
                            canvasBitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                            val image = enc.getInputImage(inIdx)
                            if (image != null) {
                                writeYuv(pixels, w, h, image)
                                enc.queueInputBuffer(inIdx, 0, w * h * 3 / 2, ptsUs(queued), 0)
                            } else {
                                // No image view of this buffer: pass a black frame rather than stall.
                                enc.queueInputBuffer(inIdx, 0, 0, ptsUs(queued), 0)
                            }
                            queued++
                            progress(queued / videoFrames.toFloat())
                        }
                    }
                }
                val outIdx = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = mux.addTrack(enc.outputFormat)
                        mux.start()
                    }
                    outIdx >= 0 -> {
                        val buf = enc.getOutputBuffer(outIdx)
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buf != null && info.size > 0 && !config && track >= 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            mux.writeSampleData(track, buf, info)
                        }
                        enc.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            mux.stop()
            muxer = null
            mux.release()
            finalShot?.recycle()
            if (!verify(part, keep)) {
                part.delete()
                return false
            }
            if (!part.renameTo(out)) {
                part.copyTo(out, overwrite = true)
                part.delete()
            }
            return true
        } catch (t: Throwable) {
            AppLog.e("stack replay not encoded", t)
            part.delete()
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { muxer?.release() }
            loaded.values.forEach { it.recycle() }
            canvasBitmap.recycle()
        }
    }

    /**
     * Reads [video] back with the platform's own decoder: its length, and frames from the start,
     * a third in and the end. False when it would not play. [keep] receives those frames as JPEGs.
     */
    fun verify(video: File, keep: File? = null): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(video.absolutePath)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (ms <= 0L) return false
            keep?.let { it.deleteRecursively(); it.mkdirs() }
            listOf(0L, ms / 3, ms - 200).all { t ->
                val b = r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: return@all false
                keep?.let { dir ->
                    File(dir, "at_${t}ms.jpg").outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                }
                b.recycle()
                true
            }.also { AppLog.i("replay", "read back: $ms ms, frames ${if (it) "decode" else "MISSING"}") }
        } catch (t: Throwable) {
            AppLog.e("stack replay unreadable", t)
            false
        } finally {
            runCatching { r.release() }
        }
    }

    /** Copies the video into Movies/StarCam as [name]. Null when the gallery refused it. */
    fun saveToGallery(context: Context, video: File, name: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/StarCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { o -> video.inputStream().use { it.copyTo(o) } }
                ?: error("no output stream for $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (t: Throwable) {
            AppLog.e("stack replay not saved to the gallery", t)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun ptsUs(frame: Int): Long = frame * 1_000_000L / FPS

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

    /** ARGB pixels into the encoder's YUV 4:2:0 image (BT.709, limited range), any plane layout. */
    private fun writeYuv(argb: IntArray, w: Int, h: Int, image: android.media.Image) {
        val planes = image.planes
        val yPlane = planes[0]
        val yBuf = yPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        val row = ByteArray(w)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                val p = argb[base + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                row[x] = (16 + ((47 * r + 157 * g + 16 * b) shr 8)).toByte()
            }
            if (yPix == 1) {
                yBuf.position(y * yRow)
                yBuf.put(row, 0, w)
            } else {
                for (x in 0 until w) yBuf.put(y * yRow + x * yPix, row[x])
            }
        }
        val uPlane = planes[1]
        val vPlane = planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        for (cy in 0 until h / 2) {
            for (cx in 0 until w / 2) {
                // Average of the 2x2 block.
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val p = argb[(cy * 2 + dy) * w + cx * 2 + dx]
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                }
                r = r shr 2
                g = g shr 2
                b = b shr 2
                val u = 128 + ((-26 * r - 87 * g + 112 * b) shr 8)
                val v = 128 + ((112 * r - 102 * g - 10 * b) shr 8)
                uBuf.put(cy * uPlane.rowStride + cx * uPlane.pixelStride, u.coerceIn(0, 255).toByte())
                vBuf.put(cy * vPlane.rowStride + cx * vPlane.pixelStride, v.coerceIn(0, 255).toByte())
            }
        }
    }
}
