package com.wm.starcam.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wm.starcam.StarCamViewModel
import com.wm.starcam.camera.CameraTest
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.components.ScCard
import com.wm.starcam.ui.components.ScChip
import com.wm.starcam.ui.components.ScIconButton
import com.wm.starcam.ui.components.ScLabel
import com.wm.starcam.ui.components.ScScreen
import com.wm.starcam.ui.components.ScSwitch
import com.wm.starcam.ui.components.ScTopBar
import com.wm.starcam.ui.theme.NightPreviewTint
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.ui.theme.ScreenMargin
import com.wm.starcam.util.AppLog
import com.wm.starcam.util.DebugBundle
import com.wm.starcam.util.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private enum class LogFilter(val label: String) { ALL("All"), PROBLEMS("Problems"), CAMERA("Camera") }

/**
 * Diagnostics: where the logs live, how to get them off the phone, a camera test that replays
 * the field failure at home, and the live log itself.
 */
@Composable
fun DiagnosticsScreen(vm: StarCamViewModel, onBack: () -> Unit) {
    val c = Sc.colors
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
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

    ScScreen {
        ScTopBar(
            "Diagnostics", onBack,
            subtitle = "Settings › Advanced · for troubleshooting",
            actions = {
                ScIconButton(ScIcons.Log, "Copy recent log", {
                    clipboard.setText(AnnotatedString(AppLog.snapshot(400).joinToString("\n")))
                    Toast.makeText(context, "Last 400 log lines copied", Toast.LENGTH_SHORT).show()
                }, filled = false)
            },
        )
        Column(Modifier.fillMaxSize().padding(horizontal = ScreenMargin)) {
            // A live viewfinder outside the list, so it exists for the whole time this screen is
            // open: the camera test runs with the preview stream in its session exactly like a
            // real shoot, and scrolling the log can never kill that stream mid-test.
            PreviewStrip(vm)
            Spacer(Modifier.height(10.dp))
            // One scrolling list: on a phone, cards plus a fixed-height log pane leave the log no room.
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                item { LogsCard(vm) }
                item {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        CameraTestCard(vm)
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LogFilter.entries.forEach { f ->
                            ScChip(f.label, selected = filter == f, onClick = { filter = f }, height = 40.dp)
                        }
                        ScChip("Latest", selected = false, icon = ScIcons.ChevronDown, height = 40.dp, onClick = {
                            scope.launch { listState.scrollToItem(HEADER_ITEMS + lines.size - 1) }
                        })
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
    val c = Sc.colors
    val status by vm.testStatus.collectAsState()
    val settings by vm.settingsStore.settings.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            CameraPreview(vm, Modifier.height(96.dp))
            // Red in night vision, like the viewfinder: the sky's own colours spoil dark adaptation.
            if (c.night) Box(Modifier.matchParentSize().background(NightPreviewTint))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                when {
                    status.running -> "Camera test running"
                    status.summary != null -> "Last test: ${status.summary}"
                    else -> "Live camera"
                },
                style = ScType.bodySmall,
                color = when {
                    status.summary?.startsWith("PASS") == true -> c.accentText
                    status.summary != null && !status.running -> c.warning
                    else -> c.text
                },
            )
            if (status.running) {
                Text(status.current, style = ScType.caption, color = c.text2)
            }
            Text(
                "preview ${if (settings.debugPreviewInSession) "in session" else "OUT"} · " +
                    "map ${if (settings.debugShadingEveryFrame) "every frame" else "probe only"} · " +
                    "fd ${if (settings.debugFrameDuration) "SET" else "template"} · " +
                    "session ${if (settings.freshSessionAfterLong) "fresh after long" else "KEPT (old bug)"}",
                style = ScType.data.copy(fontSize = ScType.caption.fontSize), color = c.text3,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun LogsCard(vm: StarCamViewModel) {
    val c = Sc.colors
    ScCard(Modifier.fillMaxWidth()) {
        Text("Everything the app does is saved on the phone", style = ScType.headline, color = c.text)
        Text(
            AppLog.logDir?.absolutePath?.substringAfter("/0/") ?: "(log folder unavailable)",
            style = ScType.data, color = c.text3, modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "Each session also keeps its own meta/session.log. The report zips the logs, crash " +
                "reports and recent sessions' details — no photos — for sending from anywhere.",
            style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(12.dp))
        ShareDebugButton(vm, Modifier.fillMaxWidth(), label = "Share debug report", kind = ScButtonKind.Primary)
    }
}

/**
 * Packs the debug bundle off the main thread and opens the share sheet. Used here and on the
 * capture screen's error step.
 */
@Composable
fun ShareDebugButton(
    vm: StarCamViewModel,
    modifier: Modifier = Modifier,
    label: String = "Share log",
    kind: ScButtonKind = ScButtonKind.Secondary,
    height: Dp = 52.dp,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    ScButton(
        text = if (busy) "Packing…" else label,
        onClick = {
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
        },
        modifier = modifier,
        kind = kind,
        icon = ScIcons.Share,
        enabled = !busy,
        height = height,
    )
}

@Composable
private fun CameraTestCard(vm: StarCamViewModel) {
    val c = Sc.colors
    val status by vm.testStatus.collectAsState()
    var expanded by remember { mutableStateOf(status.running || status.results.isNotEmpty()) }
    // A test started from the PC must show its progress without anyone tapping the card open.
    LaunchedEffect(status.running) { if (status.running) expanded = true }

    ScCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Camera test", style = ScType.headline, color = c.text)
                Text(
                    status.summary ?: if (status.running) status.current
                    else "Replays a session's exposures at home — cover the lens",
                    style = ScType.caption,
                    color = when {
                        status.summary?.startsWith("PASS") == true -> c.accentText
                        status.summary != null -> c.warning
                        else -> c.text3
                    },
                )
            }
            Icon(if (expanded) ScIcons.ChevronDown else ScIcons.ChevronRight, null, Modifier.size(20.dp), tint = c.text3)
        }
        if (expanded) CameraTestControls(vm)
    }
}

@Composable
private fun CameraTestControls(vm: StarCamViewModel) {
    val c = Sc.colors
    val status by vm.testStatus.collectAsState()
    val ready by vm.ready.collectAsState()
    var frames by remember { mutableIntStateOf(5) }
    var exposureMs by remember { mutableLongStateOf(20_000L) }
    var iso by remember { mutableIntStateOf(800) }
    var work by remember { mutableStateOf(true) }
    var recovery by remember { mutableStateOf(false) }
    Column {
        Text(
            "Same requests and troubleshooting switches as a real session (listed next to the " +
                "viewfinder above). Indoors with the lens covered, 5 × 20 s replays the field " +
                "failure in about two minutes.",
            style = ScType.caption, color = c.text3, modifier = Modifier.padding(top = 8.dp),
        )
        ChipRow("Frames", listOf(3, 5, 10), frames, { "$it" }) { frames = it }
        ChipRow("Exposure", listOf(2_000L, 10_000L, 20_000L, 30_000L), exposureMs,
            { Fmt.exposure(it * 1_000_000L) }) { exposureMs = it }
        ChipRow("ISO", listOf(400, 800, 1600, 3200), iso, { "$it" }) { iso = it }
        SwitchLine("Simulate session work (ingest + DNG)", work) { work = it }
        SwitchLine("Repair the camera on failure", recovery) { recovery = it }

        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status.running) {
                ScButton("Stop", { vm.stopCameraTest() }, height = 48.dp)
                Spacer(Modifier.width(12.dp))
                Text(status.current, style = ScType.caption, color = c.text2, modifier = Modifier.weight(1f))
            } else {
                ScButton(
                    "Run camera test",
                    { vm.runCameraTest(CameraTest.Params(frames, exposureMs, iso, work, recovery)) },
                    Modifier.fillMaxWidth(),
                    kind = ScButtonKind.Accent,
                    enabled = ready && vm.engine?.isRunning != true,
                    height = 48.dp,
                )
            }
        }
        if (status.results.isNotEmpty()) Spacer(Modifier.height(8.dp))
        status.results.takeLast(12).forEach { r ->
            Text(
                "${if (r.ok) "✓" else "✗"} ${r.label} · ${r.ms}ms ${r.detail}",
                style = ScType.data.copy(fontSize = ScType.caption.fontSize),
                color = if (r.ok) c.accentText else c.error,
            )
        }
    }
}

@Composable
private fun <T> ChipRow(
    label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit,
) {
    // Label above the chips: beside them, a large system font wrapped it and pushed chips off-screen.
    ScLabel(label, Modifier.padding(top = 10.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { o ->
            ScChip(text(o), selected = o == selected, onClick = { onSelect(o) }, mono = true, height = 38.dp)
        }
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = ScType.bodySmall, color = Sc.colors.text, modifier = Modifier.weight(1f))
        ScSwitch(checked, onChange)
    }
}

/** One log line, colour-coded by level. */
@Composable
private fun LogLine(line: String) {
    val c = Sc.colors
    Text(
        // The date is the same all night; the time is what matters on screen.
        if (line.length > 11) line.substring(11) else line,
        style = ScType.data.copy(fontSize = ScType.caption.fontSize.times(0.88f), lineHeight = ScType.caption.lineHeight),
        color = when (levelOf(line)) {
            'E' -> c.error
            'W' -> c.warning
            'D' -> c.text3
            else -> c.text2
        },
        modifier = Modifier.fillMaxWidth().background(
            if (levelOf(line) == 'E') c.errorFill else androidx.compose.ui.graphics.Color.Transparent
        ).padding(vertical = 1.dp),
    )
}

private val CAMERA_TAGS = setOf("cam", "camtest", "session", "focus")

// Lines look like "2026-10-21 07:30:00.123 E/cam [thread] message".
private fun levelOf(line: String): Char = line.getOrNull(24) ?: ' '
private fun tagOf(line: String): String =
    if (line.length > 26) line.substring(26).substringBefore(' ') else ""
