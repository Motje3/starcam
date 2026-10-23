package com.wm.starcam.ui

import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.components.ScScreen
import com.wm.starcam.ui.components.ScTopBar
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScreenMargin
import java.io.File

/**
 * A session's replay video played in the app, on a loop, with Share - without the system's
 * "Open with" question for a video the app made itself.
 */
@Composable
fun VideoScreen(file: File, onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember { arrayOfNulls<VideoView>(1) }
    DisposableEffect(file) {
        onDispose { player[0]?.stopPlayback() }
    }
    ScScreen {
        ScTopBar(
            title = "Watch it build up",
            subtitle = file.parentFile?.parentFile?.name,
            onBack = onBack,
        )
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        player[0] = this
                        setVideoPath(file.absolutePath)
                        setOnPreparedListener { mp ->
                            mp.isLooping = true
                            start()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        ScButton(
            "Share video",
            { photoUri(context, file)?.let { shareVideo(context, it) } },
            Modifier.fillMaxWidth().padding(horizontal = ScreenMargin, vertical = 12.dp),
            kind = ScButtonKind.Primary, icon = ScIcons.Share, height = 52.dp,
        )
    }
}
