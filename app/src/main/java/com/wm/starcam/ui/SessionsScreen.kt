package com.wm.starcam.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wm.starcam.session.Mode
import com.wm.starcam.session.SessionRepository
import com.wm.starcam.session.SessionSummary
import com.wm.starcam.ui.components.ScChip
import com.wm.starcam.ui.components.ScEmptyState
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
 * Past sessions as a two-column grid of photos, newest first: the date on the picture, the mode as
 * its icon with the frame count, and a badge only when a session did not simply finish (cut off,
 * RAW frames only). Thumbnails decode lazily on IO.
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
        ScTopBar("Gallery", onBack, subtitle = free?.let { "${bytesText(it)} free" })
        val list = sessions
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ScSpinner(size = 28.dp)
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ScEmptyState(ScIcons.Photo, "No photos yet",
                    "Every session lands here, newest first, with its RAW frames and photo.")
            }
            else -> {
                val byMode = list.groupBy { modeOf(it) }
                if (byMode.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = ScreenMargin, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ScChip("All ${list.size}", selected = filter == null, onClick = { filter = null }, height = 40.dp)
                        Mode.entries.forEach { m ->
                            val n = byMode[m]?.size ?: 0
                            if (n > 0) {
                                ScChip("$n", selected = filter == m, onClick = { filter = m }, height = 40.dp,
                                    icon = modeIcon(m),
                                    modifier = Modifier.semantics { contentDescription = "${modeLabel(m)}: $n" })
                            }
                        }
                    }
                }
                val shown = if (filter == null) list else list.filter { modeOf(it) == filter }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = ScreenMargin, end = ScreenMargin, top = 8.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
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
    val shape = RoundedCornerShape(20.dp)
    val mode = modeOf(s)
    Box(
        Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(shape)
            .background(Brush.verticalGradient(listOf(c.raised, c.sunken)))
            .border(1.dp, c.hairlineStrong, shape)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        // A plain state + effect: with produceState, lint's check reported the assignment as
        // missing (a false "never assigns value" error that failed the lint task).
        val path = s.finalJpg?.absolutePath
        var thumb by remember(path) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(path) {
            thumb = path?.let { p ->
                withContext(Dispatchers.IO) { decodeDownsampled(p, 480)?.asImageBitmap() }
            }
        }
        val t = thumb
        if (t != null) {
            Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, colorFilter = scImageFilter)
        } else {
            Icon(ScIcons.Stack, null, Modifier.size(30.dp).align(Alignment.Center), tint = c.text3)
        }
        StatusBadge(s, Modifier.align(Alignment.TopStart).padding(8.dp))
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, c.glass, c.glass)))
                .padding(start = 12.dp, end = 12.dp, top = 22.dp, bottom = 10.dp),
        ) {
            Text(sessionTitle(s.name, s.startedAt),
                style = ScType.headline.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                color = c.text, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (mode != null) {
                    Icon(modeIcon(mode), null, Modifier.size(14.dp), tint = c.accentText)
                    Spacer(Modifier.width(5.dp))
                }
                Text("${mode?.let { modeLabel(it) } ?: s.mode} · ${s.frameCount}",
                    style = ScType.caption.copy(fontFeatureSettings = "tnum"), color = c.text2, maxLines = 1)
            }
        }
    }
}

/** Only what needs saying: meteors caught, a session cut off or failed, RAW frames only. */
@Composable
private fun StatusBadge(s: SessionSummary, modifier: Modifier) {
    val c = Sc.colors
    val (icon, text, color) = when {
        s.finished && s.meteorCount > 0 -> Triple(ScIcons.Meteor, "${s.meteorCount}", c.accentText)
        s.finished -> return
        s.issueCount > 0 && s.frameCount > 0 -> Triple(ScIcons.Warning, "Cut off · ${s.frameCount} saved", c.warning)
        s.issueCount > 0 -> Triple(ScIcons.Warning, "Failed", c.warning)
        s.frameCount > 0 -> Triple(ScIcons.Stack, "RAW only", c.text)
        else -> Triple(ScIcons.Close, "Empty", c.text3)
    }
    ScGlass(modifier, padding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)) {
        Icon(icon, null, Modifier.size(13.dp), tint = color)
        Text(text, style = ScType.caption, color = color, maxLines = 1)
    }
}
