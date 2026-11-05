package com.wm.starcam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wm.starcam.sky.SkyLabels
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.components.ScCard
import com.wm.starcam.ui.components.ScSpinner
import com.wm.starcam.ui.components.ScSwitch
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.util.WhatsInPhoto
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * "What's in this photo": one row with a button until it has been asked, then the answer - the
 * constellations, the first few things named in it (Show all: the rest, the star count and how old
 * the oldest light in the photo is) - with the labels switch and the labelled copy.
 */
@Composable
fun WhatsInCard(
    found: WhatsInPhoto.Found?,
    working: Boolean,
    takenAtMs: Long,
    showLabels: Boolean,
    onShowLabels: (Boolean) -> Unit,
    onFind: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    note: String?,
    modifier: Modifier = Modifier,
) {
    val c = Sc.colors
    val labels = found?.labels
    ScCard(modifier.fillMaxWidth(), padding = PaddingValues(14.dp)) {
        // One row until there is an answer: what it is, and the button that finds out.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ScIcons.Constellation, null, Modifier.size(24.dp), tint = c.accentText)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("What's in this photo", style = ScType.headline, color = c.text)
                if (labels == null) {
                    Text(
                        when {
                            working -> "Recognising the stars…"
                            found == null -> "Stars, constellations, planets"
                            else -> "No sky recognised"
                        },
                        style = ScType.bodySmall, color = c.text2,
                    )
                }
            }
            if (labels == null) {
                Spacer(Modifier.width(8.dp))
                when {
                    working -> ScSpinner(size = 22.dp)
                    found == null -> ScButton("Find out", onFind, kind = ScButtonKind.Primary, height = 42.dp)
                    else -> ScButton("Try again", onFind, height = 42.dp)
                }
            }
        }
        when {
            labels != null -> Answer(labels, takenAtMs, showLabels, onShowLabels, onSave, onShare, note)
            found != null && !working -> Text(
                "It needs a dozen stars or more: cloud, a bright moon or a lot of foreground can hide them.",
                style = ScType.caption, color = c.text3, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Answer(
    labels: SkyLabels,
    takenAtMs: Long,
    showLabels: Boolean,
    onShowLabels: (Boolean) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    note: String?,
) {
    val c = Sc.colors
    Text(
        joinNames(labels.constellations.take(3)).ifEmpty { "A patch of sky" },
        style = ScType.titleSmall, color = c.text, modifier = Modifier.padding(top = 10.dp),
    )
    // What is named in it, the first three in sight; the rest, and its numbers, behind Show all.
    val facts = buildList<Pair<ImageVector, String>> {
        if (labels.planets.isNotEmpty()) {
            add(ScIcons.Planet to "${joinNames(labels.planets)}, where ${if (labels.planets.size == 1) "it was" else "they were"} that night")
        }
        if (labels.hasCore) add(ScIcons.Astro to "The centre of the Milky Way · 26,000 light years")
        for (o in labels.deepSky.take(3)) {
            add(ScIcons.Astro to o.name + (o.distanceLy?.let { " · " + SkyLabels.lightYears(it) } ?: " · ${o.kind}"))
        }
        for (s in labels.stars.filter { it.distanceLy != null }.take(3)) {
            add(ScIcons.Astro to "${s.name} · ${SkyLabels.lightYears(s.distanceLy!!)}")
        }
    }
    val oldest = labels.farthest()
    var all by remember(labels) { mutableStateOf(false) }
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        (if (all) facts else facts.take(3)).forEach { (icon, text) -> Fact(icon, text) }
    }
    if (all) {
        oldest?.let { (name, ly) ->
            Text(oldestLight(name, ly, takenAtMs), style = ScType.bodySmall, color = c.accentText,
                modifier = Modifier.padding(top = 10.dp))
        }
        Text(
            "${labels.matched} stars recognised · ${labels.fovAcrossDeg.roundToInt()}° × " +
                "${labels.fovUpDeg.roundToInt()}° of sky",
            style = ScType.caption, color = c.text3, modifier = Modifier.padding(top = 6.dp),
        )
    }
    ScButton(if (all) "Show less" else "Show all", { all = !all }, kind = ScButtonKind.Text, height = 38.dp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Labels on the photo", style = ScType.body, color = c.text, modifier = Modifier.weight(1f))
        ScSwitch(showLabels, onShowLabels)
    }
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ScButton("Save labelled", onSave, Modifier.weight(1f), kind = ScButtonKind.Primary, icon = ScIcons.Gallery)
        ScButton("Share", onShare, Modifier.weight(1f), icon = ScIcons.Share)
    }
    note?.let {
        Text(it, style = ScType.caption, color = c.text3, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Fact(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(15.dp), tint = Sc.colors.text3)
        Spacer(Modifier.width(8.dp))
        Text(text, style = ScType.bodySmall, color = Sc.colors.text)
    }
}

/**
 * [labels] drawn over a photo that fills this box's width, shown [zoom] times around its middle
 * and moved by [offset]: the names keep their size as the photo grows. [reserved] gives the
 * parts of the box other things cover (in px, from the box's size and the density).
 */
@Composable
fun SkyLabelsOverlay(
    labels: SkyLabels,
    modifier: Modifier = Modifier,
    zoom: Float = 1f,
    offset: Offset = Offset.Zero,
    reserved: (Size, Float) -> List<android.graphics.RectF> = { _, _ -> emptyList() },
) {
    val context = LocalContext.current
    val night = Sc.colors.night
    val painter = remember(night) { SkyLabelPainter(context, night) }
    Canvas(modifier) {
        val s = zoom * size.width / labels.width
        val dx = size.width / 2f * (1 - zoom) + offset.x
        val dy = size.height / 2f * (1 - zoom) + offset.y
        val keepClear = reserved(size, density)
        drawIntoCanvas { painter.draw(it.nativeCanvas, labels, s, dx, dy, density, size.width, size.height, keepClear) }
    }
}

/** "Orion", "Orion and Taurus", "Orion, Taurus and Gemini". */
private fun joinNames(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

/** "the Andromeda Galaxy", "the Pleiades", but "Rigel" and "Omega Centauri". */
private fun withThe(name: String): String {
    val the = name.startsWith("the ") || name.contains("'s ") || name.first().isDigit() || name.matches(Regex("M\\d+"))
    if (the) return name
    val things = listOf("Galaxy", "Nebula", "Cloud", "clouds", "Cluster", "Pleiades", "Hyades", "Coalsack", "Box", "Coathanger")
    return if (things.any { name.endsWith(it) }) "the $name" else name
}

/** How long the farthest light in the photo was on its way, and when it set out. */
private fun oldestLight(name: String, ly: Double, takenAtMs: Long): String {
    val years = SkyLabels.lightYears(ly).replace("light years", "years")
    val from = withThe(name)
    if (ly >= 10_000) return "Its oldest light, from $from, set out $years ago."
    val year = Instant.ofEpochMilli(takenAtMs).atZone(ZoneId.systemDefault()).year - ly.roundToInt()
    val era = if (year > 0) "around the year $year" else "around ${-year + 1} BC"
    return "Its oldest light, from $from, set out $years ago - $era."
}
