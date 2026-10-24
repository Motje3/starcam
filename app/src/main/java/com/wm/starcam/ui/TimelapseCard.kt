package com.wm.starcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.components.ScCard
import com.wm.starcam.ui.components.ScSpinner
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.util.Timelapse
import java.io.File
import kotlin.math.roundToInt

/**
 * The timelapse: the session's kept RAW frames played one after the other - the sky turning over
 * the landscape - made by [Timelapse.make] outside the screen; this card shows how it goes, then
 * plays and shares it. [frames]: how many kept frames there are.
 */
@Composable
fun TimelapseCard(
    dir: File,
    frames: Int,
    rotation: Int,
    startedAt: Long,
    enabled: Boolean,
    onPlay: ((File) -> Unit)? = null,
) {
    val c = Sc.colors
    val context = LocalContext.current
    val video = remember(dir) { File(dir, "out/${Timelapse.VIDEO}") }
    val making by Timelapse.making.collectAsState()
    val state = making[dir.absolutePath]
    val progress = state?.progress
    val made = remember(dir, state) { video.isFile }
    fun make() = Timelapse.make(context, dir, rotation, startedAt)

    ScCard(Modifier.fillMaxWidth()) {
        Text("Timelapse", style = ScType.headline, color = c.text)
        Text(
            "The $frames frames one after the other: the sky turning over the landscape, as a " +
                "short video. Made on the phone from the RAW frames; nothing is uploaded.",
            style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 4.dp),
        )
        if (progress != null) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                ScSpinner(size = 18.dp)
                Text("Developing the frames… ${(progress * 100).roundToInt()}%", style = ScType.bodySmall,
                    color = c.text2, modifier = Modifier.padding(start = 10.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (made && onPlay != null) {
                ScButton("Play", { onPlay(video) }, Modifier.weight(1f), kind = ScButtonKind.Primary,
                    icon = ScIcons.Play, enabled = progress == null)
            } else {
                ScButton(if (made) "Make again" else "Make timelapse", { make() }, Modifier.weight(1f),
                    kind = if (made) ScButtonKind.Secondary else ScButtonKind.Primary, icon = ScIcons.Timer,
                    enabled = enabled && progress == null && frames >= Timelapse.MIN_FRAMES)
            }
            if (made) {
                ScButton("Share video", {
                    photoUri(context, video)?.let { shareVideo(context, it) }
                }, Modifier.weight(1f), icon = ScIcons.Share, enabled = progress == null)
            }
        }
        if (made && onPlay != null) {
            ScButton("Make again", { make() }, Modifier.fillMaxWidth().padding(top = 4.dp), kind = ScButtonKind.Text,
                height = 40.dp, enabled = enabled && progress == null && frames >= Timelapse.MIN_FRAMES)
        }
        state?.message?.let { text ->
            Text(text, style = ScType.bodySmall, color = if (state.good) c.accentText else c.warning,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}
