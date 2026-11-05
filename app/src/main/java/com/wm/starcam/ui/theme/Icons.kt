package com.wm.starcam.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * The icon set: 24 dp grid, 1.6 stroke, round caps and joins. The capture screen's icons follow
 * the owner's set (design, Nov 2026: the sparkle, the star-trail orbits, the meteor, the sliders,
 * the map with its pin, the crosshair, the constellation, the gear), redrawn as vectors so they
 * stay sharp at any size and turn red in night vision. Tint with Icon(tint = ...); [ScDuo] has
 * the two-tone versions, line work and a bright detail.
 */
object ScIcons {
    /** Stars (the astro mode): the four-point sparkle, in outline. */
    val Astro get() = ScDuo.Stars.merged
    val Trails get() = ScDuo.Trails.merged
    val Meteor get() = ScDuo.Meteor.merged
    /** Manual exposure (the Manual mode): three sliders on their side. */
    val ProMode by icon {
        sliderRow(7.5f, 8.5f)
        sliderRow(12f, 15.5f)
        sliderRow(16.5f, 8.3f)
    }
    val Gallery by icon {
        rect(3.5f, 4.5f, 17f, 15f, 3f)
        circle(9f, 10f, 1.75f)
        path("M20.5 16l-4.5-4.5L7 20")
    }
    /** Night vision: an eye. */
    val Night get() = ScDuo.Eye.merged
    /** The Adjust sheet: three sliders standing up. */
    val Sliders by icon {
        sliderColumn(7f, 15.5f)
        sliderColumn(12f, 8.5f)
        sliderColumn(17f, 13.5f)
    }
    val Tripod by icon {
        path("M12 9v4M12 13l-5 8M12 13l5 8M12 13v8")
        rect(7f, 4f, 10f, 5f, 1.5f)
    }
    val Info by icon {
        circle(12f, 12f, 9f)
        path("M12 11v5.5M12 7.75v.01")
    }
    /** Screen shield: a phone with a moon. */
    val Shield by icon {
        rect(6.5f, 2.5f, 11f, 19f, 2.5f)
        path("M13.6 9a3.2 3.2 0 1 0 1.9 5.6A3.6 3.6 0 0 1 13.6 9z")
    }
    val Stack by icon {
        path("M12 3l9 5-9 5-9-5z")
        path("M3 13l9 5 9-5")
    }
    val Focus by icon {
        circle(12f, 12f, 7f)
        path("M12 2.5v4M12 17.5v4M2.5 12h4M17.5 12h4")
    }
    val Timer by icon {
        circle(12f, 13f, 8f)
        path("M12 9v4l2.5 2.5M9.5 2.5h5")
    }
    val Heat by icon { path("M10 14.5V5a2 2 0 0 1 4 0v9.5a4 4 0 1 1-4 0z") }
    val Check by icon { path("M5 12.5l4.5 4.5L19 7.5") }
    val CheckCircle by icon {
        circle(12f, 12f, 9f)
        path("M8 12.5l3 3 5-6")
    }
    val ChevronRight by icon { path("M9 5l7 7-7 7") }
    val ChevronLeft by icon { path("M15 5l-7 7 7 7") }
    val ChevronDown by icon { path("M6 9l6 6 6-6") }
    val Close by icon { path("M6 6l12 12M18 6L6 18") }
    val Plus by icon { path("M6 12h12M12 6v12") }
    val Minus by icon { path("M6 12h12") }
    /** Re-process / retry: clockwise arrow. */
    val Refresh by icon {
        path("M20 11a8 8 0 1 0-2.3 5.7")
        path("M20 5v6h-6")
    }
    /** Reset to defaults: counter-clockwise arrow. */
    val Reset by icon {
        path("M4 12a8 8 0 1 0 2.3-5.7")
        path("M4 4v5h5")
    }
    val Camera by icon {
        path("M4 8.5A2.5 2.5 0 0 1 6.5 6h1.8l1.4-2h4.6l1.4 2h1.8A2.5 2.5 0 0 1 20 8.5v9a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 17.5z")
        circle(12f, 13f, 3.5f)
    }
    val Warning by icon {
        path("M10.3 4.2L2.6 17.5A2 2 0 0 0 4.3 20.5h15.4a2 2 0 0 0 1.7-3L13.7 4.2a2 2 0 0 0-3.4 0z")
        path("M12 9.5v4.5M12 17.2v.01")
    }
    val Share by icon {
        circle(18f, 5.5f, 2.5f)
        circle(6f, 12f, 2.5f)
        circle(18f, 18.5f, 2.5f)
        path("M8.2 10.8l7.6-4.1M8.2 13.2l7.6 4.1")
    }
    /** Open in another app. */
    val Open by icon {
        path("M14 4h6v6M20 4l-9 9")
        path("M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4")
    }
    /** Brightness / dimmer. */
    val Sun by icon {
        circle(12f, 12f, 4f)
        path("M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4")
    }
    /** Brighter framing preview. */
    val SunSmall by icon {
        circle(12f, 12f, 4f)
        path("M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2")
    }
    val Settings get() = ScDuo.Gear.merged
    val Search by icon {
        circle(11f, 11f, 6.5f)
        path("M16 16l4.5 4.5")
    }
    /** Find in the sky: the crosshair with a star. */
    val Find get() = ScDuo.Find.merged
    val ZoomIn by icon {
        circle(11f, 11f, 6.5f)
        path("M16 16l4.5 4.5M11 8.5v5M8.5 11h5")
    }
    /** Spirit level. */
    val Level by icon {
        rect(2.5f, 8.5f, 19f, 7f, 3.5f)
        path("M9.5 8.5v7M14.5 8.5v7")
        circle(12f, 12f, 1.2f)
    }
    /** Dark frames: a covered lens. */
    val DarkFrame by icon {
        circle(12f, 12f, 8.5f)
        circle(12f, 12f, 4f)
    }
    val Compare by icon { path("M9 7l-5 5 5 5M15 7l5 5-5 5") }
    val Log by icon {
        path("M7 3h7l5 5v11.5A1.5 1.5 0 0 1 17.5 21h-11A1.5 1.5 0 0 1 5 19.5v-15A1.5 1.5 0 0 1 6.5 3z")
        path("M14 3v5h5M8.5 13h7M8.5 16.5h5")
    }
    val Trash by icon { path("M4 7h16M9 7V4.5h6V7M6.5 7l1 12.5a1.5 1.5 0 0 0 1.5 1.5h6a1.5 1.5 0 0 0 1.5-1.5L17.5 7") }
    val Mountains by icon { path("M3 18l6-8 4 5 3-3 5 6z") }
    val Moon by icon { path("M20 14.5A8.5 8.5 0 1 1 9.5 4a6.75 6.75 0 0 0 10.5 10.5z") }
    val RotateRight by icon { path("M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5") }
    val RotateLeft by icon { path("M4 12a8 8 0 1 0 2.3-5.7M4 4v5h5") }
    val Edit by icon { path("M4 20h4L19 9l-4-4L4 16v4zM13.5 6.5l4 4") }
    val Play by icon { path("M8 5.5v13l10.5-6.5z") }
    /** The autopilot: a steering wheel. */
    val Autopilot by icon {
        circle(12f, 12f, 8.5f)
        circle(12f, 12f, 2.25f)
        path("M3.6 10.5h6.2M14.2 10.5h6.2M12 14.25v7.2")
    }
    /** The light map: a folded map with a pin. */
    val Map get() = ScDuo.LightMap.merged
    val Calendar by icon {
        rect(3.5f, 5f, 17f, 15.5f, 2.5f)
        path("M3.5 10h17M8 3v4M16 3v4")
    }
    /** A place on the map. */
    val Pin by icon {
        path("M12 21s-6.5-6.2-6.5-11.2a6.5 6.5 0 0 1 13 0C18.5 14.8 12 21 12 21z")
        circle(12f, 9.8f, 2.3f)
    }
    /** Go there: a direction arrow. */
    val Navigate by icon { path("M4.5 11l15-6.5-6.5 15-2-6.5z") }
    val Bell by icon {
        path("M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2h-15z")
        path("M10 20.5a2 2 0 0 0 4 0")
    }
    /** A planet with its ring. */
    val Planet by icon {
        circle(12f, 12f, 5f)
        path("M4.2 15.2c-1.6 1.8-1.9 3.2-.9 3.8 1.9 1.1 7.4-1.3 12.3-5.3s7.4-8 5.5-9.1c-.9-.5-2.4-.1-4.3 1")
    }
    /** A constellation: the sky view. */
    val Constellation get() = ScDuo.SkyView.merged
    /** An eclipse: two discs overlapping. */
    val Eclipse by icon {
        circle(10f, 12f, 6.5f)
        path("M13.5 6.6a6.5 6.5 0 1 1 0 10.8")
    }
    val MoreVert by icon {
        circle(12f, 5f, 1.8f)
        circle(12f, 12f, 1.8f)
        circle(12f, 19f, 1.8f)
    }
    /** Three sparkles: tonight's sky. */
    val Sparkles get() = ScDuo.Sparkles.merged
    /** A photo frame with a mountain: a picture. */
    val Photo by icon {
        rect(3.5f, 4.5f, 17f, 15f, 3f)
        path("M3.5 16.5l5-5 4 4 2.5-2.5 5.5 5.5")
        circle(15.5f, 8.8f, 1.4f)
    }
}

/**
 * Two-tone icons: [DuoIcon.base] is the line work, [DuoIcon.accent] the bright detail; draw them
 * with [ScDuoIcon]. [DuoIcon.merged] is both in one, for a single tint.
 */
object ScDuo {
    val Stars by duo {
        base { path(sparkle(12f, 12f, 7.2f, 0.6f)) }
    }
    val Trails by duo {
        base {
            circle(12f, 12f, 2.9f)
            circle(12f, 12f, 5.6f)
            path("M18.96 7.3A8.4 8.4 0 0 1 6.83 18.62")
            path("M5.76 17.62A8.4 8.4 0 0 1 16.2 4.73")
        }
        accent { dot(17.73f, 5.86f, 1.3f) }
    }
    val Meteor by duo {
        base { path("M5.8 14.5L16.2 4.1M8.1 16.3L20.4 4M12.5 15.8L18.7 9.9") }
        accent { fill(sparkle(5.6f, 18.2f, 2.5f, 0.25f)) }
    }
    val Find by duo {
        base {
            circle(12f, 12f, 6.6f)
            path("M12 1.9v4.3M12 17.8v4.3M1.9 12h4.3M17.8 12h4.3")
        }
        accent { fill(sparkle(12f, 12f, 3.9f, 0f)) }
    }
    val LightMap by duo {
        base {
            path("M10.74 7.32L8.7 6.4L2.9 8.7V20.9L8.7 18.3L13.8 20.9L19.5 18.3V12.2")
            path("M8.7 6.4V18.3M13.8 13.4V20.9")
        }
        accent {
            path("M16.2 15.5L12.62 10.64A4.45 4.45 0 1 1 19.78 10.64Z")
            dot(16.2f, 8f, 1.25f)
        }
    }
    val SkyView by duo {
        base { path("M4.4 18.4L11.1 13.1L19.3 5.6M11.1 13.1L16.7 16.9L19.3 5.6") }
        accent {
            dot(4.4f, 18.4f, 1.6f)
            dot(11.1f, 13.1f, 1.65f)
            dot(16.7f, 16.9f, 1.6f)
            dot(19.3f, 5.6f, 1.9f)
        }
    }
    val Gear by duo {
        base {
            path(gear(12f, 12f, 9.6f, 7.3f, 8, 7f, 14f, 10f))
            circle(12f, 12f, 2.8f)
        }
    }
    val Eye by duo {
        base { path("M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12s-3.5 6.5-9.5 6.5S2.5 12 2.5 12z") }
        accent { dot(12f, 12f, 2.7f) }
    }
    val Sparkles by duo {
        accent {
            fill(sparkle(9.2f, 14.2f, 6f, 0.5f))
            fill(sparkle(17.6f, 5.9f, 3.3f, 0.3f))
            fill(sparkle(18.4f, 16.6f, 2.3f, 0.2f))
        }
    }
}

class DuoIcon(val base: ImageVector, val accent: ImageVector?, val merged: ImageVector)

/** A two-tone icon: line work in [base], the bright detail in [accent]. Size it with [modifier]. */
@Composable
fun ScDuoIcon(
    icon: DuoIcon,
    modifier: Modifier = Modifier,
    base: Color = Sc.colors.iconBase,
    accent: Color = Sc.colors.iconAccent,
) {
    Box(modifier) {
        Icon(icon.base, null, Modifier.matchParentSize(), tint = base)
        icon.accent?.let { Icon(it, null, Modifier.matchParentSize(), tint = accent) }
    }
}

// ------------------------------------------------------------------------------- builder

private class IconPaths {
    /** Path data, and whether it is filled rather than stroked. */
    val paths = ArrayList<Pair<String, Boolean>>()
    fun path(d: String) {
        paths += d to false
    }
    fun fill(d: String) {
        paths += d to true
    }
    fun circle(cx: Float, cy: Float, r: Float) {
        path(circleData(cx, cy, r))
    }
    fun dot(cx: Float, cy: Float, r: Float) {
        fill(circleData(cx, cy, r))
    }
    fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float) {
        val r = rx.coerceAtMost(minOf(w, h) / 2f)
        path("M${x + r},${y}h${w - 2 * r}a$r,$r 0 0 1 $r,${r}v${h - 2 * r}" +
            "a$r,$r 0 0 1 ${-r},${r}h${-(w - 2 * r)}a$r,$r 0 0 1 ${-r},${-r}" +
            "v${-(h - 2 * r)}a$r,$r 0 0 1 $r,${-r}z")
    }
    /** A slider on its side at height [y], its knob at [x]: the line stops at the knob. */
    fun sliderRow(y: Float, x: Float, r: Float = 1.8f) {
        path("M3.4 ${y}H${x - r}M${x + r} ${y}H20.6")
        circle(x, y, r)
    }
    /** A slider standing up at [x], its knob at [y]. */
    fun sliderColumn(x: Float, y: Float, r: Float = 1.8f) {
        path("M$x 3.4V${y - r}M$x ${y + r}V20.6")
        circle(x, y, r)
    }
}

private fun circleData(cx: Float, cy: Float, r: Float) =
    "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0z"

/**
 * A four-point sparkle centred on ([cx], [cy]) reaching [r] along each axis, its sides curving in:
 * [waist] 0 is the sharpest star, 1 a plain diamond.
 */
private fun sparkle(cx: Float, cy: Float, r: Float, waist: Float): String {
    val k = waist * r * 0.5f
    fun f(v: Float) = String.format(Locale.US, "%.3f", v)
    return "M${f(cx)} ${f(cy - r)}" +
        "Q${f(cx + k)} ${f(cy - k)} ${f(cx + r)} ${f(cy)}" +
        "Q${f(cx + k)} ${f(cy + k)} ${f(cx)} ${f(cy + r)}" +
        "Q${f(cx - k)} ${f(cy + k)} ${f(cx - r)} ${f(cy)}" +
        "Q${f(cx - k)} ${f(cy - k)} ${f(cx)} ${f(cy - r)}Z"
}

/**
 * A gear outline: [teeth] trapezoid teeth reaching [rOuter], the valleys arcs at [rInner]; each
 * tooth [halfTop] degrees wide either side at its tip, [halfBase] at its root.
 */
private fun gear(
    cx: Float, cy: Float, rOuter: Float, rInner: Float, teeth: Int,
    halfTop: Float, halfBase: Float, rotation: Float,
): String {
    fun f(v: Double) = String.format(Locale.US, "%.3f", v)
    fun pt(r: Float, deg: Double): String {
        val a = Math.toRadians(deg)
        return "${f(cx + r * cos(a))} ${f(cy + r * sin(a))}"
    }
    val sb = StringBuilder()
    val step = 360.0 / teeth
    for (i in 0 until teeth) {
        val a = rotation + i * step
        sb.append(if (i == 0) "M" else "L").append(pt(rInner, a - halfBase))
        sb.append("L").append(pt(rOuter, a - halfTop))
        sb.append("L").append(pt(rOuter, a + halfTop))
        sb.append("L").append(pt(rInner, a + halfBase))
        sb.append("A$rInner $rInner 0 0 1 ").append(pt(rInner, a + step - halfBase))
    }
    return sb.append("Z").toString()
}

private fun build(p: IconPaths): ImageVector {
    val b = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    )
    for ((d, filled) in p.paths) {
        if (filled) {
            b.addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
        } else {
            b.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }
    return b.build()
}

/** Lazily built stroke icon; `val X by icon { ... }`. */
private fun icon(block: IconPaths.() -> Unit) = lazy { build(IconPaths().apply(block)) }

private class DuoBuilder {
    val base = IconPaths()
    val accent = IconPaths()
    fun base(block: IconPaths.() -> Unit) {
        base.block()
    }
    fun accent(block: IconPaths.() -> Unit) {
        accent.block()
    }
}

/** Lazily built two-tone icon; `val X by duo { base { ... }; accent { ... } }`. */
private fun duo(block: DuoBuilder.() -> Unit) = lazy {
    val d = DuoBuilder().apply(block)
    val merged = IconPaths().also { it.paths += d.base.paths; it.paths += d.accent.paths }
    DuoIcon(
        base = build(d.base),
        accent = if (d.accent.paths.isEmpty()) null else build(d.accent),
        merged = build(merged),
    )
}
