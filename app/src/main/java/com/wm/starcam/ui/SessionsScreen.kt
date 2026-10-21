package com.wm.starcam.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wm.starcam.session.Mode
import com.wm.starcam.session.SessionRepository
import com.wm.starcam.session.SessionSummary
import com.wm.starcam.ui.components.ScCard
import com.wm.starcam.ui.components.ScChip
import com.wm.starcam.ui.components.ScGlass
import com.wm.starcam.ui.components.ScScreen
import com.wm.starcam.ui.components.ScSpinner
import com.wm.starcam.ui.components.ScTopBar
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.ui.theme.ScreenMargin
import com.wm.starcam.ui.theme.scImageFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Past sessions as a two-column grid, newest first, with what each one ended as: a finished
 * photo, RAWs only, or interrupted with frames saved. Thumbnails decode lazily on IO.
 */
@Composable
fun SessionsScreen(
    repo: SessionRepository,
    onOpen: (File) -> Unit,
    onBack: () -> Unit,
) {
    val c = Sc.colors
    var sessions by remember { mutableStateOf<List<SessionSummary>?>(null) }
    var free by remember { mutableStateOf<Long?>(null) }
    var filter by rememberSaveable { mutableStateOf<Mode?>(null) }

    LaunchedEffect(Unit) {
        sessions = withContext(Dispatchers.IO) { repo.listSessions() }
        free = withContext(Dispatchers.IO) { runCatching { repo.sessionsRoot().usableSpace }.getOrNull() }
    }

    ScScreen {
        ScTopBar("Sessions", onBack, actions = {
            free?.let {
                Text("${bytesText(it)} free", style = ScType.data, color = c.text3,
                    modifier = Modifier.padding(end = 8.dp))
            }
        })
        val list = sessions
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ScSpinner(size = 28.dp)
            }
            list.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(ScIcons.Gallery, null, Modifier.size(40.dp), tint = c.text3)
                Text("No sessions yet", style = ScType.titleSmall, color = c.text,
                    modifier = Modifier.padding(top = 12.dp))
                Text("Every capture lands here, newest first, with its RAW frames and photo.",
                    style = ScType.body, color = c.text2, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp))
            }
            else -> {
                val byMode = list.groupBy { modeOf(it) }
                if (byMode.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = ScreenMargin, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ScChip("All ${list.size}", selected = filter == null, onClick = { filter = null }, height = 40.dp)
                        Mode.entries.forEach { m ->
                            val n = byMode[m]?.size ?: 0
                            if (n > 0) {
                                ScChip("${modeLabel(m)} $n", selected = filter == m, onClick = { filter = m }, height = 40.dp)
                            }
                        }
                    }
                }
                val shown = if (filter == null) list else list.filter { modeOf(it) == filter }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = ScreenMargin, end = ScreenMargin, top = 8.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.dir.absolutePath }) { s ->
                        SessionTile(s, onClick = { onOpen(s.dir) })
                    }
                }
            }
        }
    }
}

private fun modeOf(s: SessionSummary): Mode? =
    runCatching { Mode.valueOf(s.mode.uppercase()) }.getOrNull()

@Composable
private fun SessionTile(s: SessionSummary, onClick: () -> Unit) {
    val c = Sc.colors
    ScCard(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp), onClick = onClick) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(c.sunken)) {
            val thumb by produceState<ImageBitmap?>(null, s.finalJpg?.absolutePath) {
                value = s.finalJpg?.let { f ->
                    withContext(Dispatchers.IO) { decodeDownsampled(f.absolutePath, 400)?.asImageBitmap() }
                }
            }
            val t = thumb
            if (t != null) {
                Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, colorFilter = scImageFilter)
            } else {
                Icon(ScIcons.Stack, null, Modifier.size(28.dp).align(Alignment.Center), tint = c.text3)
            }
            StatusBadge(s, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(sessionTitle(s.name, s.startedAt), style = ScType.headline.copy(fontFeatureSettings = "tnum"),
                color = c.text, maxLines = 1)
            val mode = modeOf(s)?.let { modeLabel(it) } ?: s.mode
            Text("$mode · ${s.frameCount} frame${if (s.frameCount == 1) "" else "s"}",
                style = ScType.caption, color = c.text3, maxLines = 1)
        }
    }
}

@Composable
private fun StatusBadge(s: SessionSummary, modifier: Modifier) {
    val c = Sc.colors
    val (icon, text, color) = when {
        s.finished && s.meteorCount > 0 ->
            Triple(ScIcons.Check, "${s.meteorCount} meteor${if (s.meteorCount == 1) "" else "s"}", c.accentText)
        s.finished -> Triple(ScIcons.Check, "Finished", c.accentText)
        s.issueCount > 0 && s.frameCount > 0 -> Triple(ScIcons.Warning, "Interrupted · ${s.frameCount} saved", c.warning)
        s.issueCount > 0 -> Triple(ScIcons.Warning, "Failed", c.warning)
        s.frameCount > 0 -> Triple(ScIcons.Stack, "RAWs only", c.text)
        else -> Triple(ScIcons.Close, "Empty", c.text3)
    }
    ScGlass(modifier, padding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)) {
        Icon(icon, null, Modifier.size(13.dp), tint = color)
        Text(text, style = ScType.caption, color = color, maxLines = 1)
    }
}
