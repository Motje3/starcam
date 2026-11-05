package com.wm.starcam.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.wm.starcam.ui.theme.ScIcons
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
    val context = LocalContext.current
    val video = remember(dir) { File(dir, "out/${Timelapse.VIDEO}") }
    val making by Timelapse.making.collectAsState()
    val state = making[dir.absolutePath]
    val made = remember(dir, state) { video.isFile }
    VideoCard(
        icon = ScIcons.Timer,
        title = "Timelapse",
        line = "The $frames frames as a short video",
        progressText = state?.progress?.let { "Developing the frames… ${(it * 100).roundToInt()}%" },
        made = made,
        canMake = enabled && frames >= Timelapse.MIN_FRAMES,
        onMake = { Timelapse.make(context, dir, rotation, startedAt) },
        onPlay = onPlay?.let { play -> { play(video) } },
        onShare = { photoUri(context, video)?.let { shareVideo(context, it) } },
        message = state?.message,
        good = state?.good == true,
    )
}
