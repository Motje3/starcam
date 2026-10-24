package com.wm.starcam.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.components.ScCard
import com.wm.starcam.ui.components.ScDialog
import com.wm.starcam.ui.components.ScSpinner
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScShapes
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.ui.theme.scImageFilter
import com.wm.starcam.util.MeteorHighlights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FellAt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

private fun fellAt(cut: MeteorHighlights.Cut): String =
    cut.atMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(FellAt) } ?: "frame ${cut.frame + 1}"

/**
 * The meteors a session caught, each cut out of the frame it flashed through ([MeteorHighlights]):
 * a row of them with the time each fell; one opened large, to share. [count]: how many the
 * stack found.
 */
@Composable
fun MeteorCard(dir: File, count: Int) {
    val c = Sc.colors
    var cuts by remember(dir) { mutableStateOf<List<Pair<MeteorHighlights.Cut, ImageBitmap?>>?>(null) }
    LaunchedEffect(dir) {
        cuts = withContext(Dispatchers.Default) {
            val list = MeteorHighlights.kept(dir) ?: MeteorHighlights.make(dir)
            list.map { it to decodeDownsampled(it.file.absolutePath, 480)?.asImageBitmap() }
        }
    }
    var open by remember { mutableStateOf<MeteorHighlights.Cut?>(null) }

    ScCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ScIcons.Meteor, null, Modifier.size(20.dp), tint = c.accentText)
            Spacer(Modifier.width(8.dp))
            Text(if (count == 1) "1 meteor caught" else "$count meteors caught", style = ScType.headline, color = c.text)
        }
        Text("Each one cut out of the frame it flashed through, with the time it fell.",
            style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 4.dp))
        val list = cuts
        when {
            list == null -> Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                ScSpinner(size = 18.dp)
                Text("Cutting out the meteors…", style = ScType.bodySmall, color = c.text2,
                    modifier = Modifier.padding(start = 10.dp))
            }
            list.isEmpty() -> Text("Their frames were not kept, so they cannot be cut out here.",
                style = ScType.bodySmall, color = c.text3, modifier = Modifier.padding(top = 8.dp))
            else -> Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((cut, image) in list) {
                    Column(Modifier.clip(ScShapes.chip).clickable { open = cut }) {
                        if (image != null) {
                            Image(image, "Meteor at ${fellAt(cut)}",
                                Modifier.height(112.dp).aspectRatio(image.width / image.height.toFloat()).clip(ScShapes.chip),
                                contentScale = ContentScale.Crop, colorFilter = scImageFilter)
                        }
                        Text(fellAt(cut), style = ScType.data, color = c.text2, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
                    }
                }
            }
        }
    }
    open?.let { cut -> MeteorDialog(cut) { open = null } }
}

@Composable
private fun MeteorDialog(cut: MeteorHighlights.Cut, onDismiss: () -> Unit) {
    val c = Sc.colors
    val context = LocalContext.current
    var image by remember(cut) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(cut) {
        image = withContext(Dispatchers.IO) { decodeDownsampled(cut.file.absolutePath, 1400)?.asImageBitmap() }
    }
    ScDialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(c.surface).border(1.dp, c.hairline, ScShapes.card).padding(16.dp)) {
            Text("Meteor at ${fellAt(cut)}", style = ScType.headline, color = c.text)
            image?.let {
                Image(it, null, Modifier.fillMaxWidth().padding(top = 10.dp).clip(ScShapes.chip)
                    .aspectRatio(it.width / it.height.toFloat()), contentScale = ContentScale.Fit, colorFilter = scImageFilter)
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScButton("Close", onDismiss, Modifier.weight(1f), height = 44.dp)
                ScButton("Share", { photoUri(context, cut.file)?.let { sharePhoto(context, it) } }, Modifier.weight(1f),
                    kind = ScButtonKind.Primary, icon = ScIcons.Share, height = 44.dp)
            }
        }
    }
}
