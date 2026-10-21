package com.wm.starcam.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wm.starcam.StarCamViewModel
import com.wm.starcam.camera.CameraTest
import com.wm.starcam.ui.theme.WarnAmber
import com.wm.starcam.util.AppLog
import com.wm.starcam.util.DebugBundle
import com.wm.starcam.util.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private val OkGreen = Color(0xFF6EDB8F)

private enum class LogFilter(val label: String) { ALL("All"), PROBLEMS("Problems"), CAMERA("Camera") }

/**
 * Diagnostics: where the logs live, how to get them off the phone, a camera test that replays
 * the field failure at home, and the live log itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(vm: StarCamViewModel, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(AppLog.snapshot(400).joinToString("\n")))
                        Toast.makeText(context, "Last 400 log lines copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy recent log")
                    }
                },
            )
        },
    ) { padding ->
        var filter by remember { mutableStateOf(LogFilter.ALL) }
        var lines by remember { mutableStateOf(emptyList<String>()) }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        // Polled ~3× a second rather than re-read per line: camera tracing logs in bursts, and a
        // debounce would never fire while a burst lasts. Follows the tail only once the reader
        // is already there (and has lines to follow), so opening the screen shows the cards and
        // scrolling up is not yanked back down.
        LaunchedEffect(filter) {
            var seen = -1L
            while (true) {
                val v = AppLog.version.value
                if (v != seen) {
                    seen = v
                    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                    val following = lines.isNotEmpty() && lastVisible != null &&
                        lastVisible >= HEADER_ITEMS + lines.size - 2
                    lines = AppLog.snapshot(2000).filter { line ->
                        when (filter) {
                            LogFilter.ALL -> true
                            LogFilter.PROBLEMS -> levelOf(line) == 'W' || levelOf(line) == 'E'
                            LogFilter.CAMERA -> tagOf(line) in CAMERA_TAGS
                        }
                    }
                    // A programmatic scroll loses to a finger on the list and throws; that must
                    // not end this loop, or the log silently stops updating.
                    if (following && !listState.isScrollInProgress) {
                        try {
                            listState.scrollToItem(HEADER_ITEMS + lines.size - 1)
                        } catch (e: CancellationException) {
                            coroutineContext.ensureActive()
                        }
                    }
                }
                delay(300)
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            // A live viewfinder outside the list, so it exists for the whole time this screen is
            // open: the camera test runs with the preview stream in its session exactly like a
            // real shoot, and scrolling the log can never kill that stream mid-test.
            PreviewStrip(vm)
            Spacer(Modifier.height(8.dp))
            // One scrolling list: on a phone, cards plus a fixed-height log pane leave the log no room.
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
            ) {
                item { LogsCard(vm) }
                item {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        CameraTestCard(vm)
                    }
                }
                item {
                    Row(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LogFilter.entries.forEach { f ->
                            FilterChip(
                                selected = filter == f,
                                onClick = { filter = f },
                                label = { Text(f.label, fontSize = 11.sp) },
                            )
                        }
                        FilterChip(
                            selected = false,
                            onClick = { scope.launch { listState.scrollToItem(HEADER_ITEMS + lines.size - 1) } },
                            label = { Text("latest ↓", fontSize = 11.sp) },
                        )
                    }
                }
                items(lines) { LogLine(it) }
            }
        }
    }
}

/** Items above the log lines in the Diagnostics list. */
private const val HEADER_ITEMS = 3

/** Small live viewfinder plus what the test (if any) is doing right now. */
@Composable
private fun PreviewStrip(vm: StarCamViewModel) {
    val status by vm.testStatus.collectAsState()
    val settings by vm.settingsStore.settings.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        CameraPreview(vm, Modifier.height(96.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                when {
                    status.running -> "camera test running"
                    status.summary != null -> "last test: ${status.summary}"
                    else -> "live camera"
                },
                fontSize = 12.sp,
                color = when {
                    status.summary?.startsWith("PASS") == true -> OkGreen
                    status.summary != null && !status.running -> WarnAmber
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (status.running) {
                Text(status.current, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "debug: preview ${if (settings.debugPreviewInSession) "in session" else "OUT"} · " +
                    "map ${if (settings.debugShadingEveryFrame) "every frame" else "probe only"} · " +
                    "fd ${if (settings.debugFrameDuration) "SET" else "template"} · " +
                    "session ${if (settings.freshSessionAfterLong) "fresh after long" else "KEPT (old bug)"}",
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LogsCard(vm: StarCamViewModel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("Everything the app does is saved on the phone", style = MaterialTheme.typography.bodyMedium)
            Text(
                AppLog.logDir?.absolutePath?.substringAfter("/0/") ?: "(log folder unavailable)",
                fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Each session also keeps its own meta/session.log. The bundle zips the logs, crash " +
                    "reports and recent sessions' details (no images) for sending from anywhere.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            ShareDebugButton(vm, label = "Share debug bundle")
        }
    }
}

/**
 * Packs the debug bundle off the main thread and opens the share sheet. Used here and on the
 * capture screen's error card.
 */
@Composable
fun ShareDebugButton(vm: StarCamViewModel, outlined: Boolean = false, label: String = "Share log") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val click: () -> Unit = {
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    DebugBundle.share(context, vm.buildDebugBundle())
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    AppLog.e("ui", "could not build or share the debug bundle", t)
                    Toast.makeText(context, "Could not pack the logs: ${t.message}", Toast.LENGTH_LONG).show()
                } finally {
                    busy = false
                }
            }
        }
    }
    val text = if (busy) "Packing…" else label
    if (outlined) OutlinedButton(onClick = click, enabled = !busy) { Text(text) }
    else Button(onClick = click, enabled = !busy) { Text(text) }
}

@Composable
private fun CameraTestCard(vm: StarCamViewModel) {
    val status by vm.testStatus.collectAsState()
    var expanded by remember { mutableStateOf(status.running || status.results.isNotEmpty()) }
    // A test started from the PC must show its progress without anyone tapping the card open.
    LaunchedEffect(status.running) { if (status.running) expanded = true }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Camera test", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        status.summary ?: if (status.running) status.current
                        else "replays a session's exposures at home — cover the lens",
                        fontSize = 11.sp,
                        color = when {
                            status.summary?.startsWith("PASS") == true -> OkGreen
                            status.summary != null -> WarnAmber
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            }
            if (expanded) CameraTestControls(vm)
        }
    }
}

@Composable
private fun CameraTestControls(vm: StarCamViewModel) {
    val status by vm.testStatus.collectAsState()
    val ready by vm.ready.collectAsState()
    var frames by remember { mutableIntStateOf(5) }
    var exposureMs by remember { mutableLongStateOf(20_000L) }
    var iso by remember { mutableIntStateOf(800) }
    var work by remember { mutableStateOf(true) }
    var recovery by remember { mutableStateOf(false) }
    Column {
        Text(
            "Same requests and Debug switches as a real session (shown under the viewfinder " +
                "above). Indoors with the lens covered, 5 × 20 s replays the field failure in " +
                "about two minutes.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        ChipRow("frames", listOf(3, 5, 10), frames, { "$it" }) { frames = it }
        ChipRow("exposure", listOf(2_000L, 10_000L, 20_000L, 30_000L), exposureMs,
            { Fmt.exposure(it * 1_000_000L) }) { exposureMs = it }
        ChipRow("ISO", listOf(400, 800, 1600, 3200), iso, { "$it" }) { iso = it }
        SwitchRow("simulate session work (ingest + DNG)", work) { work = it }
        SwitchRow("repair the camera on failure", recovery) { recovery = it }

        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status.running) {
                OutlinedButton(onClick = { vm.stopCameraTest() }) { Text("Stop") }
                Spacer(Modifier.width(10.dp))
                Text(status.current, fontSize = 11.sp, modifier = Modifier.weight(1f))
            } else {
                Button(
                    onClick = {
                        vm.runCameraTest(CameraTest.Params(frames, exposureMs, iso, work, recovery))
                    },
                    enabled = ready && vm.engine?.isRunning != true,
                ) { Text("Run test") }
            }
        }
        status.results.takeLast(12).forEach { r ->
            Text(
                "${if (r.ok) "✓" else "✗"} ${r.label} · ${r.ms}ms ${r.detail}",
                fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                color = if (r.ok) OkGreen else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun <T> ChipRow(
    label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 11.sp, modifier = Modifier.width(58.dp))
        options.forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(o) },
                label = { Text(text(o), fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** One log line, colour-coded by level. */
@Composable
private fun LogLine(line: String) {
    Text(
        // The date is the same all night; the time is what matters on screen.
        if (line.length > 11) line.substring(11) else line,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        color = when (levelOf(line)) {
            'E' -> MaterialTheme.colorScheme.error
            'W' -> WarnAmber
            'D' -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onBackground
        },
        modifier = Modifier.padding(vertical = 1.dp),
    )
}

private val CAMERA_TAGS = setOf("cam", "camtest", "session", "focus")

// Lines look like "2026-10-21 07:30:00.123 E/cam [thread] message".
private fun levelOf(line: String): Char = line.getOrNull(24) ?: ' '
private fun tagOf(line: String): String =
    if (line.length > 26) line.substring(26).substringBefore(' ') else ""
