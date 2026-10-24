package com.wm.starcam.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The handoff's icon set (design/handoff/screens/Foundations.html): 24 dp grid, 1.75 stroke,
 * round caps and joins, outline only. Ported path for path from the SVGs, so the app and the
 * mockups draw identical glyphs. Tint with Icon(tint = ...); the stroke colour is replaced.
 */
object ScIcons {
    val Astro by icon { path("M12 3C12.8 9 15 11.2 21 12C15 12.8 12.8 15 12 21C11.2 15 9 12.8 3 12C9 11.2 11.2 9 12 3Z") }
    val Trails by icon {
        path("M12 4a8 8 0 0 1 8 8")
        path("M12 7.5a4.5 4.5 0 0 1 4.5 4.5")
        path("M4 12a8 8 0 0 0 8 8")
        path("M7.5 12a4.5 4.5 0 0 0 4.5 4.5")
        circle(12f, 12f, 1.2f)
    }
    val Meteor by icon {
        path("M4 4l9.5 9.5")
        path("M8.5 3.5l7 7")
        path("M3.5 8.5l7 7")
        circle(16.5f, 16.5f, 3f)
    }
    /** PRO mode: a gauge. */
    val ProMode by icon {
        circle(12f, 13f, 7.5f)
        path("M12 13l3.5-3.5")
        path("M12 3v1.5M4.2 6.2l1.1 1.1M19.8 6.2l-1.1 1.1")
    }
    val Gallery by icon {
        rect(3.5f, 4.5f, 17f, 15f, 3f)
        circle(9f, 10f, 1.75f)
        path("M20.5 16l-4.5-4.5L7 20")
    }
    /** Night vision: an eye. */
    val Night by icon {
        path("M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12s-3.5 6.5-9.5 6.5S2.5 12 2.5 12z")
        circle(12f, 12f, 2.75f)
    }
    /** Pro controls: sliders. */
    val Sliders by icon {
        path("M6 4v3M6 11v9M12 4v9M12 17v3M18 4v2M18 10v10")
        circle(6f, 9f, 2f)
        circle(12f, 15f, 2f)
        circle(18f, 8f, 2f)
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
    val Settings by icon {
        circle(12f, 12f, 3f)
        path("M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z")
    }
    val Search by icon {
        circle(11f, 11f, 6.5f)
        path("M16 16l4.5 4.5")
    }
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
    /** The dark-sky map: a folded map. */
    val Map by icon { path("M9 4.5L3.5 6.5v13l5.5-2 6 2 5.5-2v-13l-5.5 2-6-2zM9 4.5v13M15 6.5v13") }
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
    /** A constellation: four stars joined up. */
    val Constellation by icon {
        path("M6.3 15.6l2.6-3.1M11.4 10.9l2.3 1.4M16.5 11.6l1.9-4.1")
        circle(5f, 17f, 1.6f)
        circle(10f, 10.6f, 1.6f)
        circle(15.2f, 13.4f, 1.6f)
        circle(19.2f, 5.8f, 1.6f)
    }
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
}

// ------------------------------------------------------------------------------- builder

private class IconPaths {
    val paths = ArrayList<String>()
    fun path(d: String) {
        paths += d
    }
    fun circle(cx: Float, cy: Float, r: Float) {
        paths += "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0z"
    }
    fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float) {
        val r = rx.coerceAtMost(minOf(w, h) / 2f)
        paths += "M${x + r},${y}h${w - 2 * r}a$r,$r 0 0 1 $r,${r}v${h - 2 * r}" +
            "a$r,$r 0 0 1 ${-r},${r}h${-(w - 2 * r)}a$r,$r 0 0 1 ${-r},${-r}" +
            "v${-(h - 2 * r)}a$r,$r 0 0 1 $r,${-r}z"
    }
}

/** Lazily built stroke icon; `val X by icon { ... }`. */
private fun icon(block: IconPaths.() -> Unit) = lazy {
    val p = IconPaths().apply(block)
    val b = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    )
    for (d in p.paths) {
        b.addPath(
            pathData = addPathNodes(d),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.75f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    b.build()
}
