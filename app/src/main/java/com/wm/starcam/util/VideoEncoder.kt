package com.wm.starcam.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File

/**
 * H.264 video in an MP4, from pictures drawn one video frame at a time - the replay video and the
 * timelapse. The platform's encoder, on the phone; nothing is uploaded. A video counts only once
 * it has been read back.
 */
object VideoEncoder {
    private const val STALL_MS = 15_000L

    /**
     * Encodes [count] frames of [w] x [h] (multiples of 16) at [fps] into [out]: [draw] paints
     * frame i on a canvas cleared to black. Blocking; [progress] gets 0..1. [keep] receives the
     * frames read back, for a look from the PC. False when the phone could not make it.
     */
    fun encode(
        out: File,
        w: Int,
        h: Int,
        fps: Int,
        count: Int,
        keep: File? = null,
        progress: (Float) -> Unit = {},
        draw: (Int, Canvas) -> Unit,
    ): Boolean {
        val part = File(out.parentFile, "${out.name}.${SystemClock.elapsedRealtimeNanos()}.part")
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        val canvasBitmap = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull()
            ?: return false
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                // Night skies are mostly fine noise, which costs bits: generous for the size.
                setInteger(MediaFormat.KEY_BIT_RATE, (w * h * fps * 0.25).toInt().coerceIn(2_000_000, 12_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
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
            val canvas = Canvas(canvasBitmap)
            val pixels = IntArray(w * h)

            val info = MediaCodec.BufferInfo()
            var track = -1
            var queued = 0
            var inputDone = false
            var outputDone = false
            // An encoder that stops taking or giving buffers ends the video, not the app's patience.
            var lastActivity = SystemClock.elapsedRealtime()
            while (!outputDone) {
                if (SystemClock.elapsedRealtime() - lastActivity > STALL_MS) error("encoder stalled")
                if (!inputDone) {
                    val inIdx = enc.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        lastActivity = SystemClock.elapsedRealtime()
                        if (queued >= count) {
                            enc.queueInputBuffer(inIdx, 0, 0, ptsUs(queued, fps), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            canvas.drawColor(Color.BLACK)
                            draw(queued, canvas)
                            canvasBitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                            // Every encoder must offer its input as a flexible YUV image (CDD);
                            // one that does not ends the attempt.
                            val image = enc.getInputImage(inIdx) ?: error("no input image")
                            writeYuv(pixels, w, h, image)
                            enc.queueInputBuffer(inIdx, 0, w * h * 3 / 2, ptsUs(queued, fps), 0)
                            queued++
                            // Drawing a frame can take a while (a timelapse develops RAW frames).
                            lastActivity = SystemClock.elapsedRealtime()
                            progress(queued / count.toFloat())
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
                        lastActivity = SystemClock.elapsedRealtime()
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
            AppLog.e("video not encoded", t)
            part.delete()
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { muxer?.release() }
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
            }.also { AppLog.i("video", "read back ${video.name}: $ms ms, frames ${if (it) "decode" else "MISSING"}") }
        } catch (t: Throwable) {
            AppLog.e("video unreadable", t)
            false
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * Copies the video into Movies/StarCam as [name], in place of the copy an earlier attempt for
     * the same session left there. Null when the gallery refused it.
     */
    fun saveToGallery(context: Context, video: File, name: String): Uri? {
        val resolver = context.contentResolver
        runCatching {
            // Only the app's own entries can go without asking; anything else stays.
            resolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Video.Media.DISPLAY_NAME}=? AND ${MediaStore.Video.Media.RELATIVE_PATH}=?",
                arrayOf(name, "Movies/StarCam/"))
        }
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
            AppLog.e("video not saved to the gallery", t)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun ptsUs(frame: Int, fps: Int): Long = frame * 1_000_000L / fps

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
