package com.wm.starcam.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File

/**
 * "1 frame vs stacked" as one picture to share: the left half of a single frame beside the right
 * half of the finished stack, labelled, with the session's numbers underneath. Written to the
 * cache's share/ folder, which the FileProvider may hand out.
 */
object ShareCard {

    private const val MAX_EDGE = 1600

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun compare(context: Context, single: File, stacked: File, stackedLabel: String, caption: String): File? {
        val after = decode(stacked) ?: return null
        val beforeRaw = decode(single) ?: run {
            after.recycle()
            return null
        }
        val before = Bitmap.createScaledBitmap(beforeRaw, after.width, after.height, true)
        if (before !== beforeRaw) beforeRaw.recycle()
        val w = after.width
        val h = after.height
        val footer = (w * 0.09f).toInt().coerceAtLeast(90)
        val card = Bitmap.createBitmap(w, h + footer, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(card)
            canvas.drawColor(Color.rgb(10, 12, 18))
            val half = w / 2
            canvas.drawBitmap(before, Rect(0, 0, half, h), Rect(0, 0, half, h), null)
            canvas.drawBitmap(after, Rect(half, 0, w, h), Rect(half, 0, w, h), null)
            val line = Paint().apply { color = Color.WHITE; strokeWidth = (w / 400f).coerceAtLeast(3f) }
            canvas.drawLine(half.toFloat(), 0f, half.toFloat(), h.toFloat(), line)

            val textSize = w * 0.032f
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                this.textSize = textSize
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                letterSpacing = 0.06f
            }
            val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 10, 12, 18) }
            fun tag(text: String, x: Float, alignRight: Boolean) {
                val tw = label.measureText(text)
                val pad = textSize * 0.6f
                val left = if (alignRight) x - tw - 2 * pad else x
                val top = textSize * 0.8f
                canvas.drawRoundRect(RectF(left, top, left + tw + 2 * pad, top + textSize * 1.7f),
                    textSize, textSize, pill)
                canvas.drawText(text, left + pad, top + textSize * 1.22f, label)
            }
            val margin = w * 0.025f
            tag("1 FRAME", margin, false)
            tag(stackedLabel.uppercase(), w - margin, true)

            val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                this.textSize = footer * 0.36f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(170, 178, 196)
                this.textSize = footer * 0.26f
            }
            val baseline = h + footer * 0.62f
            canvas.drawText("StarCam", margin, baseline, brand)
            val capX = margin + brand.measureText("StarCam") + footer * 0.3f
            canvas.drawText(caption, capX, baseline, small)

            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val out = File(dir, "starcam_compare.jpg")
            out.outputStream().use { card.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            return out
        } catch (t: Throwable) {
            AppLog.e("share card failed", t)
            return null
        } finally {
            card.recycle()
            before.recycle()
            after.recycle()
        }
    }
}
