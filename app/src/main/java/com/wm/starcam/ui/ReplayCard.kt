package com.wm.starcam.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wm.starcam.session.stackPhoto
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.components.ScCard
import com.wm.starcam.ui.components.ScSpinner
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.util.AppLog
import com.wm.starcam.util.StackReplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * "Watch it build up": makes the session's replay video (the live stack as it grew, into the
 * finished photo), saves it to the gallery and shares it. [frames] is how many live-stack
 * moments were kept during capture; [caption] the closing line (frames, exposure, ISO).
 */
@Composable
fun ReplayCard(dir: File, frames: Int, caption: String, enabled: Boolean) {
    val c = Sc.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val video = remember(dir) { File(dir, "out/${StackReplay.VIDEO}") }
    var made by remember(dir) { mutableStateOf(video.isFile) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    fun make() {
        progress = 0f
        message = null
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                val t0 = System.currentTimeMillis()
                // Debug builds keep the frames read back from the video, for a look from the PC.
                val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                val keep = if (debuggable) File(context.cacheDir, "replay_check") else null
                val ok = StackReplay.encode(StackReplay.frames(dir), stackPhoto(dir), caption, video, keep) { p ->
                    progress = p
                }
                if (!ok) return@withContext "The video could not be made on this phone." to false
                val uri = StackReplay.saveToGallery(context, video, "starcam_${dir.name}_replay.mp4")
                AppLog.i("replay", "made ${video.name}: $frames frames, ${video.length() / 1024} KB in " +
                    "${System.currentTimeMillis() - t0} ms, gallery: ${uri ?: "FAILED"}")
                if (uri != null) "Saved to the gallery (Movies › StarCam)." to true
                else "Made, but the gallery copy failed: share it from here." to false
            }
            progress = null
            made = video.isFile
            message = result
        }
    }

    ScCard(Modifier.fillMaxWidth()) {
        Text("Watch it build up", style = ScType.headline, color = c.text)
        Text(
            "A short video of the stack growing frame by frame, from the first noisy shot to the " +
                "finished photo. Made on the phone; nothing is uploaded.",
            style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 4.dp),
        )
        val p = progress
        if (p != null) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                ScSpinner(size = 18.dp)
                Text("Making the video… ${(p * 100).roundToInt()}%", style = ScType.bodySmall, color = c.text2,
                    modifier = Modifier.padding(start = 10.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScButton(if (made) "Make again" else "Make video", ::make, Modifier.weight(1f),
                kind = if (made) ScButtonKind.Secondary else ScButtonKind.Primary, icon = ScIcons.Play,
                enabled = enabled && p == null)
            if (made) {
                ScButton("Share video", {
                    photoUri(context, video)?.let { shareVideo(context, it) }
                }, Modifier.weight(1f), icon = ScIcons.Share, enabled = p == null)
            }
        }
        message?.let { (text, good) ->
            Text(text, style = ScType.bodySmall, color = if (good) c.accentText else c.warning,
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
