package com.wm.starcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wm.starcam.StarCamViewModel
import com.wm.starcam.session.AppSettings
import com.wm.starcam.session.FocusMode
import com.wm.starcam.session.Mode
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScChip
import com.wm.starcam.ui.components.ScIconButton
import com.wm.starcam.ui.components.ScLabel
import com.wm.starcam.ui.components.ScSegmented
import com.wm.starcam.ui.components.ScSlider
import com.wm.starcam.ui.components.ScSwitch
import com.wm.starcam.ui.components.ScToggleTile
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScShapes
import com.wm.starcam.ui.theme.ScType
import java.util.Locale
import kotlin.math.abs

private enum class Dial { Shutter, Iso, Frames }

private val ShutterStops = listOf(0.5, 1.0, 2.0, 4.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0, 30.0)
private val IsoStops = listOf(200, 400, 800, 1600, 3200, 6400)
private val FrameStops = listOf(8, 12, 16, 20, 30, 40, 60, 99)
private val TrailMinutes = listOf(5, 10, 15, 20, 30, 45, 60)
private val MeteorMinutes = listOf(10, 15, 30, 60, 90, 120)

/**
 * Quick manual overrides, over the viewfinder: the three exposure numbers, focus, start delay and
 * the four switches people actually flip at night. Everything else lives in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProSheet(
    vm: StarCamViewModel,
    framingBoost: Boolean,
    onBoostChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Sc.colors
    val s by vm.settingsStore.settings.collectAsState()
    val cam by vm.camInfo.collectAsState()
    val lenses by vm.lenses.collectAsState()
    fun up(t: (AppSettings) -> AppSettings) = vm.settingsStore.update(t)
    val plan = remember(s, cam) { planFor(s, cam) }
    val forcedManual = s.mode == Mode.PRO
    val manual = plan.manual
    val longMode = s.mode == Mode.TRAILS || s.mode == Mode.METEOR
    var dial by remember { mutableStateOf(Dial.Shutter) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = ScShapes.sheet,
        containerColor = c.surface,
        contentColor = c.text,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 4.dp).size(width = 36.dp, height = 4.dp)
                .clip(CircleShape).background(c.hairlineStrong))
        },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pro controls", style = ScType.titleSmall, color = c.text)
                    Text(
                        modeLabel(s.mode) + when {
                            longMode -> " · ${plan.minutes} min"
                            manual -> " · manual"
                            else -> " · " + s.quality.name.lowercase().replaceFirstChar { it.uppercase() }
                        },
                        style = ScType.caption, color = c.text3,
                    )
                }
                ScIconButton(ScIcons.Settings, "All settings", onOpenSettings, filled = false)
                Spacer(Modifier.width(4.dp))
                ScButton("Done", onDismiss, height = 44.dp)
            }

            // ---- Exposure
            Row(
                Modifier.fillMaxWidth().clip(ScShapes.button).background(c.tile)
                    .border(1.dp, c.hairline, ScShapes.button)
                    .clickable(enabled = !forcedManual, role = Role.Switch) {
                        up { it.copy(manualExposure = !it.manualExposure) }
                    }
                    .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Auto exposure", style = ScType.headline.copy(fontWeight = FontWeight.Medium), color = c.text)
                    Text(
                        when {
                            forcedManual -> "PRO mode is always manual"
                            manual -> "Off · you set shutter, ISO and frames"
                            else -> "On · a test frame picks the ISO"
                        },
                        style = ScType.caption, color = c.text3,
                    )
                }
                ScSwitch(!manual, null, enabled = !forcedManual)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The line under each number says where it comes from.
                DialTile("Shutter", exposureText(plan.exposureSec).removeSuffix(" s"), unit = "s",
                    source = if (manual) "manual" else "lens limit", auto = !manual,
                    selected = manual && dial == Dial.Shutter, modifier = Modifier.weight(1f)) { dial = Dial.Shutter }
                DialTile("ISO", plan.iso?.toString() ?: "auto",
                    source = if (manual) "manual" else "test frame", auto = !manual,
                    selected = manual && dial == Dial.Iso, modifier = Modifier.weight(1f)) { dial = Dial.Iso }
                DialTile(
                    if (longMode) "Duration" else "Frames",
                    if (longMode) "${plan.minutes}" else "${plan.frames}",
                    unit = if (longMode) "min" else "",
                    source = when {
                        longMode -> "${plan.frames} frames"
                        manual -> "manual"
                        else -> "preset"
                    },
                    auto = !manual && !longMode,
                    selected = (manual || longMode) && dial == Dial.Frames,
                    modifier = Modifier.weight(1f),
                ) { dial = Dial.Frames }
            }

            val maxExp = cam?.maxExposureSeconds ?: 30.0
            val isoRange = cam?.isoRange
            when {
                dial == Dial.Shutter && manual -> {
                    val stops = ShutterStops.filter { it <= maxExp + 0.01 }
                    StepperRow(
                        options = stops,
                        current = s.exposureNs / 1e9,
                        label = { exposureText(it) },
                    ) { v -> up { it.copy(exposureNs = (v * 1e9).toLong()) } }
                }
                dial == Dial.Iso && manual -> {
                    val stops = IsoStops.filter { isoRange == null || it in isoRange.lower..isoRange.upper }
                    StepperRow(
                        options = stops,
                        current = s.iso,
                        label = { "ISO $it" },
                    ) { v -> up { it.copy(iso = v) } }
                }
                dial == Dial.Frames && s.mode == Mode.TRAILS -> StepperRow(
                    options = TrailMinutes, current = s.trailsMinutes, label = { "$it min" },
                ) { v -> up { it.copy(trailsMinutes = v) } }
                dial == Dial.Frames && s.mode == Mode.METEOR -> StepperRow(
                    options = MeteorMinutes, current = s.meteorMinutes, label = { "$it min" },
                ) { v -> up { it.copy(meteorMinutes = v) } }
                dial == Dial.Frames && manual -> StepperRow(
                    options = FrameStops, current = s.frameCount, label = { "$it" },
                    fineStep = { cur, dir -> (cur + dir).coerceIn(4, 99) },
                ) { v -> up { it.copy(frameCount = v) } }
            }

            // Honest feedback on the one number that ruins a night: stars trail past the lens's limit.
            cam?.let { info ->
                val limit = info.trailLimitSeconds()
                val lens = if (lenses.size >= 2) " on ${lensLabel(lenses, info)}" else ""
                val over = manual && plan.exposureSec > limit + 0.05
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (over) ScIcons.Warning else ScIcons.Info, null, Modifier.size(15.dp),
                        tint = if (over) c.warning else c.text3)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (over) "${exposureText(plan.exposureSec)} is past this lens's ${exposureText(limit)} star limit$lens — expect short trails."
                        else "Stars stay round up to ${exposureText(limit)}$lens · camera allows up to ${exposureText(info.maxExposureSeconds)}",
                        style = ScType.caption, color = if (over) c.warning else c.text3,
                    )
                }
            }

            // ---- Focus and timing
            LabeledControl("Focus") {
                ScSegmented(
                    listOf("Auto stars", "Infinity", "Manual"), s.focusMode.ordinal,
                    { i -> up { it.copy(focusMode = FocusMode.entries[i]) } },
                    Modifier.fillMaxWidth(),
                )
            }
            if (s.focusMode == FocusMode.MANUAL) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("∞", style = ScType.headline, color = c.text2)
                    ScSlider(
                        s.manualFocusDiopters, { v -> up { it.copy(manualFocusDiopters = v) } }, 0f..0.5f,
                        Modifier.weight(1f).padding(horizontal = 10.dp),
                    )
                    Text(String.format(Locale.US, "%.3f dpt", s.manualFocusDiopters), style = ScType.data, color = c.text2)
                }
            }
            LabeledControl("Start delay") {
                val delays = listOf(0, 3, 10)
                ScSegmented(
                    delays.map { "$it s" }, delays.indexOf(s.startDelaySec).coerceAtLeast(0),
                    { i -> up { it.copy(startDelaySec = delays[i]) } },
                    Modifier.fillMaxWidth(), mono = true,
                )
            }

            // ---- Switches
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScToggleTile(ScIcons.DarkFrame, "Dark frames", "remove hot pixels", s.darksEnabled,
                    { up { it.copy(darksEnabled = !it.darksEnabled) } }, Modifier.weight(1f))
                ScToggleTile(ScIcons.Stack, "Save RAW", "DNG for desktop", s.saveDngs,
                    { up { it.copy(saveDngs = !it.saveDngs) } }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScToggleTile(ScIcons.SunSmall, "Brighter preview", "framing aid, ~8 fps", framingBoost,
                    { onBoostChange(!framingBoost) }, Modifier.weight(1f))
                ScToggleTile(ScIcons.Level, "Spirit level", "for horizons", s.showLevel,
                    { up { it.copy(showLevel = !it.showLevel) } }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DialTile(
    label: String,
    value: String,
    source: String,
    auto: Boolean,
    selected: Boolean,
    modifier: Modifier,
    unit: String = "",
    onClick: () -> Unit,
) {
    val c = Sc.colors
    Column(
        modifier.clip(ScShapes.button)
            .background(if (selected) c.selectedFill else c.tile)
            .border(1.dp, if (selected) c.selectedBorder else c.hairline, ScShapes.button)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        ScLabel(label, color = if (selected) c.accentText else c.text3)
        // The unit is set small so the number keeps its size in a third of a phone's width.
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
            Text(value, style = ScType.stat, color = c.text, maxLines = 1, softWrap = false)
            if (unit.isNotEmpty()) {
                Text(" $unit", style = ScType.data, color = c.text3, maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        Text(source, style = ScType.caption, color = if (auto) c.accentText else c.text3, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/**
 * − value + with the usual stops as chips underneath ([options] ascending). A value between stops
 * (set earlier, or with [fineStep]) is still shown and steps to the next stop either way.
 */
@Composable
private fun <T : Comparable<T>> StepperRow(
    options: List<T>,
    current: T,
    label: (T) -> String,
    fineStep: ((T, Int) -> T)? = null,
    onChange: (T) -> Unit,
) {
    val c = Sc.colors
    if (options.isEmpty()) return
    fun step(dir: Int) {
        val next = when {
            fineStep != null -> fineStep(current, dir)
            dir > 0 -> options.firstOrNull { it > current }
            else -> options.lastOrNull { it < current }
        }
        next?.let(onChange)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScIconButton(ScIcons.Minus, "Less", { step(-1) }, size = 48.dp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(label(current), style = ScType.readout, color = c.text)
            }
            ScIconButton(ScIcons.Plus, "More", { step(1) }, size = 48.dp)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { o ->
                ScChip(label(o), selected = o.compareTo(current) == 0, onClick = { onChange(o) }, mono = true)
            }
        }
    }
}

@Composable
private fun LabeledControl(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ScLabel(label)
        content()
    }
}
