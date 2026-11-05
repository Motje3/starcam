package com.wm.starcam.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
fun ReplayCard(dir: File, frames: Int, caption: String, enabled: Boolean, onPlay: ((File) -> Unit)? = null) {
    val context = LocalContext.current
    val video = remember(dir) { File(dir, "out/${StackReplay.VIDEO}") }
    val making by StackReplay.making.collectAsState()
    val state = making[dir.absolutePath]
    val made = remember(dir, state) { video.isFile }
    VideoCard(
        icon = ScIcons.Play,
        title = "Watch it build up",
        line = "A video of the stack growing",
        progressText = state?.progress?.let { "Making the video… ${(it * 100).roundToInt()}%" },
        made = made,
        canMake = enabled && frames >= 2,
        onMake = { StackReplay.make(context, dir, caption) },
        onPlay = onPlay?.let { play -> { play(video) } },
        onShare = { photoUri(context, video)?.let { shareVideo(context, it) } },
        message = state?.message,
        good = state?.good == true,
    )
}

/**
 * A video made on the phone from a session, in one row: what it is, and its one button - Make, or
 * Play once it is made - with Share and Make again under it then. Nothing is uploaded.
 */
@Composable
internal fun VideoCard(
    icon: ImageVector,
    title: String,
    /** What it is, until it has been made. */
    line: String,
    progressText: String?,
    made: Boolean,
    canMake: Boolean,
    onMake: () -> Unit,
    /** Null where there is no player (the result screen): sharing is then the video's button. */
    onPlay: (() -> Unit)?,
    onShare: () -> Unit,
    message: String?,
    good: Boolean,
) {
    val c = Sc.colors
    val busy = progressText != null
    ScCard(Modifier.fillMaxWidth(), padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp), tint = c.accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = ScType.headline, color = c.text)
                Text(
                    progressText ?: message ?: if (made) "Saved in Movies › Stelli" else line,
                    style = ScType.bodySmall,
                    color = if (message != null && !good && !busy) c.warning else c.text2,
                )
            }
            Spacer(Modifier.width(8.dp))
            when {
                busy -> ScSpinner(size = 22.dp)
                made && onPlay != null -> ScButton("Play", onPlay, kind = ScButtonKind.Primary, icon = ScIcons.Play,
                    height = 42.dp)
                made -> ScButton("Share", onShare, icon = ScIcons.Share, height = 42.dp)
                else -> ScButton("Make", onMake, kind = ScButtonKind.Primary, enabled = canMake, height = 42.dp)
            }
        }
        if (made && !busy) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                if (onPlay != null) ScButton("Share", onShare, kind = ScButtonKind.Text, height = 38.dp)
                ScButton("Make again", onMake, kind = ScButtonKind.Text, enabled = canMake, height = 38.dp)
            }
        }
    }
}

internal fun shareVideo(context: Context, uri: Uri) {
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
