package com.wm.starcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.wm.starcam.sky.SkyLabels
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.components.ScCard
import com.wm.starcam.ui.components.ScNotice
import com.wm.starcam.ui.components.ScSpinner
import com.wm.starcam.ui.components.ScSwitch
import com.wm.starcam.ui.components.Tone
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.util.WhatsInPhoto
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * "What's in this photo": a button until it has been asked, then the answer - the constellations,
 * the named stars and how far they are, the planets that night, the nebulae and galaxies, and how
 * old the oldest light in the photo is - with the labels switch and the labelled copy.
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
    ScCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ScIcons.Constellation, null, Modifier.size(20.dp), tint = c.accentText)
            Spacer(Modifier.width(8.dp))
            Text("What's in this photo", style = ScType.headline, color = c.text)
        }
        val labels = found?.labels
        when {
            working -> Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ScSpinner(size = 20.dp)
                Spacer(Modifier.width(10.dp))
                Text("Recognising the stars…", style = ScType.bodySmall, color = c.text2)
            }
            found == null -> {
                Text(
                    "The constellations, stars, planets, nebulae and galaxies in your photo, named - " +
                        "worked out from the stars themselves, on the phone.",
                    style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 4.dp),
                )
                ScButton("Find out", onFind, Modifier.fillMaxWidth().padding(top = 10.dp),
                    kind = ScButtonKind.Primary, icon = ScIcons.Astro, height = 52.dp)
            }
            labels == null -> {
                ScNotice(
                    "No sky recognised in this photo. It needs a dozen stars or more; cloud, a bright " +
                        "moon or a lot of foreground can hide them.",
                    tone = Tone.Normal, icon = ScIcons.Info, modifier = Modifier.padding(top = 10.dp),
                )
                ScButton("Try again", onFind, Modifier.fillMaxWidth().padding(top = 10.dp), icon = ScIcons.Refresh)
            }
            else -> Answer(labels, takenAtMs, showLabels, onShowLabels, onSave, onShare, note)
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
        style = ScType.titleSmall, color = c.text, modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        "${labels.matched} stars recognised · ${labels.fovAcrossDeg.roundToInt()}° × " +
            "${labels.fovUpDeg.roundToInt()}° of sky",
        style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 2.dp),
    )
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (labels.planets.isNotEmpty()) {
            Fact(ScIcons.Planet, "${joinNames(labels.planets)}, where ${if (labels.planets.size == 1) "it was" else "they were"} that night")
        }
        if (labels.hasCore) Fact(ScIcons.Astro, "The centre of the Milky Way · 26,000 light years")
        for (o in labels.deepSky.take(3)) {
            Fact(ScIcons.Astro, o.name + (o.distanceLy?.let { " · " + SkyLabels.lightYears(it) } ?: " · ${o.kind}"))
        }
        for (s in labels.stars.filter { it.distanceLy != null }.take(3)) {
            Fact(ScIcons.Astro, "${s.name} · ${SkyLabels.lightYears(s.distanceLy!!)}")
        }
    }
    labels.farthest()?.let { (name, ly) ->
        Text(oldestLight(name, ly, takenAtMs), style = ScType.bodySmall, color = c.accentText,
            modifier = Modifier.padding(top = 10.dp))
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
