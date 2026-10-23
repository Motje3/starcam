package com.wm.starcam.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import com.wm.starcam.util.StackReplay
import java.io.File
import kotlin.math.roundToInt

/**
 * "Watch it build up": makes the session's replay video (the live stack as it grew, into the
 * finished photo), saves it to the gallery and shares it. [frames] is how many live-stack
 * moments were kept during capture; [caption] the closing line (frames, exposure, ISO). The
 * video is made by [StackReplay.make], outside the screen: this card only shows how it goes.
 */
@Composable
fun ReplayCard(dir: File, frames: Int, caption: String, enabled: Boolean) {
    val c = Sc.colors
    val context = LocalContext.current
    val video = remember(dir) { File(dir, "out/${StackReplay.VIDEO}") }
    val making by StackReplay.making.collectAsState()
    val state = making[dir.absolutePath]
    val progress = state?.progress
    val made = remember(dir, state) { video.isFile }

    ScCard(Modifier.fillMaxWidth()) {
        Text("Watch it build up", style = ScType.headline, color = c.text)
        Text(
            "A short video of the stack growing frame by frame, from the first noisy shot to the " +
                "finished photo. Made on the phone; nothing is uploaded.",
            style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 4.dp),
        )
        if (progress != null) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                ScSpinner(size = 18.dp)
                Text("Making the video… ${(progress * 100).roundToInt()}%", style = ScType.bodySmall,
                    color = c.text2, modifier = Modifier.padding(start = 10.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScButton(if (made) "Make again" else "Make video", { StackReplay.make(context, dir, caption) },
                Modifier.weight(1f), kind = if (made) ScButtonKind.Secondary else ScButtonKind.Primary,
                icon = ScIcons.Play, enabled = enabled && progress == null && frames >= 2)
            if (made) {
                ScButton("Share video", {
                    photoUri(context, video)?.let { shareVideo(context, it) }
                }, Modifier.weight(1f), icon = ScIcons.Share, enabled = progress == null)
            }
        }
        state?.message?.let { text ->
            Text(text, style = ScType.bodySmall, color = if (state.good) c.accentText else c.warning,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun shareVideo(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, "Share video"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app to share with", Toast.LENGTH_SHORT).show()
    }
}
