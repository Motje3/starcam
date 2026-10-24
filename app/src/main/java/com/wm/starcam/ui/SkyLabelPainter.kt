package com.wm.starcam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.wm.starcam.R
import com.wm.starcam.sky.PhotoLabel
import com.wm.starcam.sky.SkyLabels
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws [SkyLabels] over a photo on an Android canvas: the constellation figures, then the names,
 * most important first. A name goes where it is fully in view, covers no name drawn before it and,
 * where it can, crosses no figure line; one that fits nowhere is left off. The same drawing on the
 * screen (over the zoomable photo) and in the labelled copy that is saved and shared.
 */
class SkyLabelPainter(context: Context, private val night: Boolean) {

    private val medium: Typeface = runCatching { ResourcesCompat.getFont(context, R.font.geist_medium) }.getOrNull()
        ?: Typeface.DEFAULT
    private val semibold: Typeface = runCatching { ResourcesCompat.getFont(context, R.font.geist_semibold) }.getOrNull()
        ?: Typeface.DEFAULT_BOLD

    /** Night vision keeps everything red: the colour's brightness in the red channel alone. */
    private fun tone(color: Int): Int {
        if (!night) return color
        val l = (0.3 * Color.red(color) + 0.59 * Color.green(color) + 0.11 * Color.blue(color)).toInt()
        return Color.argb(Color.alpha(color), max(l, 90), 0, 0)
    }

    /**
     * Draws [labels] (in their own pixels) at x * [scale] + [dx], y * [scale] + [dy] on [canvas],
     * whose visible part is [clipW] x [clipH]. [unit]: the size of one dp in canvas pixels.
     * [reserved]: parts of the view that other things cover (the zoom badge), kept clear of names.
     */
    fun draw(
        canvas: Canvas, labels: SkyLabels, scale: Float, dx: Float, dy: Float, unit: Float, clipW: Float, clipH: Float,
        reserved: List<RectF> = emptyList(),
    ) {
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.1f * unit
            strokeCap = Paint.Cap.ROUND
            color = tone(Color.argb(120, 190, 210, 255))
        }
        val gap = 6f * unit
        val s = labels.segments
        val drawn = ArrayList<FloatArray>()
        for (i in 0 until s.size step 4) {
            val x0 = s[i] * scale + dx
            val y0 = s[i + 1] * scale + dy
            val x1 = s[i + 2] * scale + dx
            val y1 = s[i + 3] * scale + dy
            val len = hypot(x1 - x0, y1 - y0)
            // The lines stop short of the stars, so the stars stay visible.
            if (len < 3 * gap) continue
            val ux = (x1 - x0) / len
            val uy = (y1 - y0) / len
            val seg = floatArrayOf(x0 + ux * gap, y0 + uy * gap, x1 - ux * gap, y1 - uy * gap)
            canvas.drawLine(seg[0], seg[1], seg[2], seg[3], line)
            drawn += seg
        }

        val margin = 4f * unit
        val placed = ArrayList<RectF>(reserved)
        fun clear(r: RectF) = placed.none { RectF.intersects(it, r) }
        fun fits(r: RectF) = r.left >= margin && r.top >= margin && r.right <= clipW - margin &&
            r.bottom <= clipH - margin && clear(r)
        fun onLine(r: RectF) = drawn.any { crosses(it, r) }

        /** The first of [boxes] that fits off the lines, else the first that fits at all. */
        fun choose(boxes: List<RectF>): RectF? =
            boxes.firstOrNull { fits(it) && !onLine(it) } ?: boxes.firstOrNull { fits(it) }

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(2.5f * unit, 0f, 0f, Color.argb(200, 0, 0, 0)) }
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        /** A name beside a point [r] across: right of it or left, a little up or down if need be. */
        fun beside(label: String, x: Float, y: Float, r: Float): Boolean {
            val w = text.measureText(label)
            val h = text.textSize
            val boxes = ArrayList<RectF>()
            for (shift in listOf(0f, -0.9f * h, 0.9f * h)) for (right in listOf(true, false)) {
                val left = if (right) x + r + 4 * unit else x - r - 4 * unit - w
                boxes += RectF(left - 2 * unit, y + shift - h * 0.9f, left + w + 2 * unit, y + shift + h * 0.35f)
            }
            val box = choose(boxes) ?: return false
            canvas.drawText(label, box.left + 2 * unit, box.bottom - h * 0.35f + h * 0.33f, text)
            placed += box
            return true
        }

        /** A box [w] x [h] centred on x, at [top], moved into view sideways. */
        fun centred(x: Float, top: Float, w: Float, h: Float): RectF {
            val left = (x - w / 2).coerceIn(margin, max(margin, clipW - margin - w))
            return RectF(left, top, left + w, top + h)
        }

        for (l in labels.labels) {
            val x = l.x * scale + dx
            val y = l.y * scale + dy
            if (x < -40 * unit || y < -40 * unit || x > clipW + 40 * unit || y > clipH + 40 * unit) continue
            when (l.kind) {
                PhotoLabel.Kind.CONSTELLATION -> {
                    text.typeface = semibold
                    text.textSize = 11.5f * unit
                    text.letterSpacing = 0.16f
                    text.color = tone(Color.argb(235, 157, 184, 255))
                    val name = l.text.uppercase()
                    val w = text.measureText(name) + 6 * unit
                    val h = text.textSize + 4 * unit
                    // At its stars' middle, else a little above or below, clear of its own figure.
                    val boxes = listOf(0f, -1.6f, 1.6f, -3.2f, 3.2f).map { centred(x, y - h * 0.8f + it * h, w, h) }
                    choose(boxes)?.let { box ->
                        canvas.drawText(name, box.left + 3 * unit, box.bottom - 4 * unit - h * 0.05f, text)
                        placed += box
                    }
                    text.letterSpacing = 0f
                }
                PhotoLabel.Kind.CORE -> {
                    mark.color = tone(Color.argb(230, 255, 158, 196))
                    mark.strokeWidth = 1.4f * unit
                    val r = 9f * unit
                    canvas.drawCircle(x, y, r, mark)
                    canvas.drawLine(x - 2 * r, y, x - r * 1.3f, y, mark)
                    canvas.drawLine(x + r * 1.3f, y, x + 2 * r, y, mark)
                    canvas.drawLine(x, y - 2 * r, x, y - r * 1.3f, mark)
                    canvas.drawLine(x, y + r * 1.3f, x, y + 2 * r, mark)
                    placed += RectF(x - r, y - r, x + r, y + r)
                    text.typeface = semibold
                    text.textSize = 11f * unit
                    text.color = mark.color
                    beside(l.text, x, y, 2 * r)
                }
                PhotoLabel.Kind.PLANET -> {
                    mark.color = tone(Color.argb(240, 255, 200, 107))
                    mark.strokeWidth = 1.4f * unit
                    val r = 7f * unit
                    canvas.drawCircle(x, y, r, mark)
                    placed += RectF(x - r, y - r, x + r, y + r)
                    text.typeface = semibold
                    text.textSize = 12f * unit
                    text.color = mark.color
                    beside(l.text, x, y, r)
                }
                PhotoLabel.Kind.DEEP_SKY -> {
                    val r = max(l.radiusPx * scale, 8f * unit)
                    val ring = RectF(x - r, y - r, x + r, y + r)
                    // A big one (the Magellanic Clouds, the Hyades) is not boxed out of its own area.
                    val big = r >= 40 * unit
                    if (!big && !clear(ring)) continue
                    val name = l.text
                    val detail = l.detail
                    text.typeface = semibold
                    text.textSize = 9f * unit
                    val dw = detail?.let { text.measureText(it) } ?: 0f
                    text.textSize = 11f * unit
                    val w = max(text.measureText(name), dw) + 4 * unit
                    val h = (if (detail != null) 2 else 1) * 12.5f * unit + 2 * unit
                    // Under its ring, else over it; on a big one, at its middle.
                    val boxes = if (big) listOf(centred(x, y - h / 2, w, h))
                    else listOf(centred(x, y + r + 3 * unit, w, h), centred(x, y - r - 3 * unit - h, w, h))
                    mark.color = tone(Color.argb(220, 127, 224, 214))
                    mark.strokeWidth = 1.2f * unit
                    mark.pathEffect = DashPathEffect(floatArrayOf(4f * unit, 3f * unit), 0f)
                    canvas.drawCircle(x, y, r, mark)
                    mark.pathEffect = null
                    if (!big) placed += ring
                    val box = choose(boxes) ?: continue
                    val cx = box.centerX()
                    text.color = mark.color
                    canvas.drawText(name, cx - text.measureText(name) / 2, box.top + 10.5f * unit, text)
                    if (detail != null) {
                        text.typeface = medium
                        text.textSize = 9f * unit
                        text.color = tone(Color.argb(190, 127, 224, 214))
                        canvas.drawText(detail, cx - text.measureText(detail) / 2, box.top + 22.5f * unit, text)
                    }
                    placed += box
                }
                PhotoLabel.Kind.STAR -> {
                    val r = 5.5f * unit
                    val ring = RectF(x - r, y - r, x + r, y + r)
                    if (!clear(ring)) continue
                    text.typeface = medium
                    text.textSize = 10.5f * unit
                    text.color = tone(Color.argb(235, 255, 255, 255))
                    placed += ring
                    if (beside(l.text, x, y, r)) {
                        mark.color = tone(Color.argb(170, 255, 255, 255))
                        mark.strokeWidth = 1f * unit
                        canvas.drawCircle(x, y, r, mark)
                    } else {
                        placed.remove(ring)
                    }
                }
            }
        }
    }

    /** Whether segment [s] (x0, y0, x1, y1) passes through [r] (Liang-Barsky). */
    private fun crosses(s: FloatArray, r: RectF): Boolean {
        val x0 = s[0]
        val y0 = s[1]
        val ddx = s[2] - x0
        val ddy = s[3] - y0
        var t0 = 0f
        var t1 = 1f
        val p = floatArrayOf(-ddx, ddx, -ddy, ddy)
        val q = floatArrayOf(x0 - r.left, r.right - x0, y0 - r.top, r.bottom - y0)
        for (i in 0..3) {
            if (p[i] == 0f) {
                if (q[i] < 0) return false
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0) t0 = max(t0, t) else t1 = min(t1, t)
                if (t0 > t1) return false
            }
        }
        return true
    }
}
