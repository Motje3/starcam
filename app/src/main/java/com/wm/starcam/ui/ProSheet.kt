package com.wm.starcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wm.starcam.StarCamViewModel
import com.wm.starcam.session.AppSettings
import com.wm.starcam.session.FocusMode
import com.wm.starcam.session.Mode
import com.wm.starcam.ui.components.NightWindow
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.components.ScChip
import com.wm.starcam.ui.components.ScIconButton
import com.wm.starcam.ui.components.ScLabel
import com.wm.starcam.ui.components.ScSegmented
import com.wm.starcam.ui.components.ScSlider
import com.wm.starcam.ui.components.ScSwitch
import com.wm.starcam.ui.theme.LocalNightDim
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScShapes
import com.wm.starcam.ui.theme.ScType
import java.util.Locale

private enum class Dial { Shutter, Iso, Frames }

private val ShutterStops = listOf(0.5, 1.0, 2.0, 4.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0, 30.0)
private val IsoStops = listOf(200, 400, 800, 1600, 3200, 6400)
private val FrameStops = listOf(8, 12, 16, 20, 30, 40, 60, 99)
private val TrailMinutes = listOf(5, 10, 15, 20, 30, 45, 60)
private val MeteorMinutes = listOf(10, 15, 30, 60, 90, 120)

/**
 * Quick overrides, over the viewfinder: Auto or Manual exposure, the three exposure numbers, focus,
 * start delay and the switches people flip at night, the two framing aids folded under More
 * options. Everything else lives in Settings, the gear in the top bar above the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProSheet(
    vm: StarCamViewModel,
    framingBoost: Boolean,
    onBoostChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Sc.colors
    val s by vm.settingsStore.settings.collectAsState()
    val cam by vm.camInfo.collectAsState()
    val lenses by vm.lenses.collectAsState()
    fun up(t: (AppSettings) -> AppSettings) = vm.settingsStore.update(t)
    val plan = remember(s, cam) { planFor(s, cam) }
    val manual = plan.manual
    val longMode = s.mode == Mode.TRAILS || s.mode == Mode.METEOR
    // No number is open for changing until it is tapped, so the sheet opens calm.
    var dial by remember { mutableStateOf<Dial?>(null) }
    var more by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The sheet is its own window, above the activity's night dim: the strip around the content
    // (handle, nav-bar inset) is pre-dimmed here, the content by NightWindow.
    val dim = if (c.night) LocalNightDim.current else 0f
    val edge = Color.Black.copy(alpha = dim).compositeOver(c.surface)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = ScShapes.sheet,
        containerColor = edge,
        contentColor = c.text,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 4.dp).size(width = 36.dp, height = 4.dp)
                .clip(CircleShape).background(Color.Black.copy(alpha = dim).compositeOver(c.hairlineStrong)))
        },
    ) {
      NightWindow {
        Column(
            Modifier.fillMaxWidth().background(c.surface).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ScIcons.Sliders, null, Modifier.size(26.dp), tint = c.accent)
                Spacer(Modifier.width(14.dp))
                Text("Adjust", style = ScType.title.copy(fontSize = 24.sp, lineHeight = 30.sp), color = c.text,
                    modifier = Modifier.weight(1f))
                ScButton("Done", onDismiss, kind = ScButtonKind.Accent, height = 44.dp)
            }

            // Who sets the exposure. Auto from the Manual mode is the Stars mode, which decides it.
            ScSegmented(
                listOf("Auto", "Manual"), if (manual) 1 else 0,
                { i ->
                    up {
                        if (i == 0 && it.mode == Mode.PRO) it.copy(mode = Mode.ASTRO, manualExposure = false)
                        else it.copy(manualExposure = i == 1)
                    }
                    if (i == 0 && (dial != Dial.Frames || !longMode)) dial = null
                },
                Modifier.fillMaxWidth(), height = 44.dp,
            )

            Section("Exposure") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // In Auto the app sets shutter and ISO (and Stars' frames); the trail and
                    // meteor modes' duration is always yours.
                    val framesOpen = manual || longMode
                    DialTile("Shutter", exposureText(plan.exposureSec).removeSuffix(" s"), unit = "s",
                        open = dial == Dial.Shutter, enabled = manual, modifier = Modifier.weight(1f)) {
                        dial = if (dial == Dial.Shutter) null else Dial.Shutter
                    }
                    DialTile("ISO", plan.iso?.toString() ?: "auto",
                        open = dial == Dial.Iso, enabled = manual, modifier = Modifier.weight(1f)) {
                        dial = if (dial == Dial.Iso) null else Dial.Iso
                    }
                    DialTile(
                        if (longMode) "Duration" else "Frames",
                        if (longMode) "${plan.minutes}" else "${plan.frames}",
                        unit = if (longMode) "min" else "",
                        open = dial == Dial.Frames, enabled = framesOpen, modifier = Modifier.weight(1f),
                    ) { dial = if (dial == Dial.Frames) null else Dial.Frames }
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

                // The one number that ruins a night, said only when it does: stars trail past the
                // lens's limit.
                cam?.let { info ->
                    val limit = info.trailLimitSeconds()
                    if (manual && plan.exposureSec > limit + 0.05) {
                        val lens = if (lenses.size >= 2) " on ${lensLabel(lenses, info)}" else ""
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(ScIcons.Warning, null, Modifier.size(15.dp), tint = c.warning)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${exposureText(plan.exposureSec)} is past this lens's ${exposureText(limit)} star " +
                                    "limit$lens — expect short trails.",
                                style = ScType.caption, color = c.warning,
                            )
                        }
                    }
                }
            }

            Section("Focus") {
                ScSegmented(
                    listOf("Auto stars", "Infinity", "Manual"), s.focusMode.ordinal,
                    { i -> up { it.copy(focusMode = FocusMode.entries[i]) } },
                    Modifier.fillMaxWidth(), height = 44.dp,
                )
                if (s.focusMode == FocusMode.MANUAL) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("∞", style = ScType.headline, color = c.text2)
                        ScSlider(
                            s.manualFocusDiopters, { v -> up { it.copy(manualFocusDiopters = v) } }, 0f..0.5f,
                            Modifier.weight(1f).padding(horizontal = 10.dp),
                        )
                        Text(String.format(Locale.US, "%.3f dpt", s.manualFocusDiopters), style = ScType.data,
                            color = c.text2)
                    }
                }
            }

            Section("Start delay") {
                val delays = listOf(0, 3, 10)
                ScSegmented(
                    delays.map { "$it s" }, delays.indexOf(s.startDelaySec).coerceAtLeast(0),
                    { i -> up { it.copy(startDelaySec = delays[i]) } },
                    Modifier.fillMaxWidth(), height = 44.dp, mono = true,
                )
            }

            Section("Capture options") {
                SwitchRow(ScIcons.DarkFrame, "Dark frames", "Remove hot pixels", s.darksEnabled) {
                    up { it.copy(darksEnabled = !it.darksEnabled) }
                }
                SwitchRow(ScIcons.Stack, "Save RAW", "DNG for editing", s.saveDngs) {
                    up { it.copy(saveDngs = !it.saveDngs) }
                }
                Spacer(Modifier.height(4.dp))
                OptionRow(ScIcons.SunSmall, "More options", "Brighter preview · Spirit level", Role.Button,
                    { more = !more }) {
                    Icon(ScIcons.ChevronDown, null, Modifier.size(22.dp).rotate(if (more) 180f else 0f),
                        tint = c.text2)
                    Spacer(Modifier.width(8.dp))
                }
                if (more) {
                    SwitchRow(ScIcons.SunSmall, "Brighter preview", "Framing aid, ~8 fps", framingBoost) {
                        onBoostChange(!framingBoost)
                    }
                    SwitchRow(ScIcons.Level, "Spirit level", "For horizons", s.showLevel) {
                        up { it.copy(showLevel = !it.showLevel) }
                    }
                }
            }
        }
      }
    }
}

@Composable
private fun Section(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScLabel(label)
        content()
    }
}

/**
 * One of the three exposure numbers: its name and its value. Tapped, it opens its stepper under the
 * row; it is dim and still when the app sets it ([enabled] off).
 */
@Composable
private fun DialTile(
    label: String,
    value: String,
    open: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    unit: String = "",
    onClick: () -> Unit,
) {
    val c = Sc.colors
    val lit = open && enabled
    Column(
        modifier.clip(ScShapes.tile)
            .background(if (lit) c.selectedFill else c.tile)
            .border(1.dp, if (lit) c.selectedBorder else c.hairline, ScShapes.tile)
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        ScLabel(label, color = if (lit) c.accentText else c.text3)
        // The unit is set small so the number keeps its size in a third of a phone's width.
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(value, style = ScType.stat.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
                color = if (enabled) c.text else c.text2, maxLines = 1, softWrap = false)
            if (unit.isNotEmpty()) {
                Text(" $unit", style = ScType.dataLarge, color = c.text3, maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

/** A switch with its name and what it does, on a card of its own; the whole card flips it. */
@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onToggle: () -> Unit) {
    OptionRow(icon, title, subtitle, Role.Switch, onToggle) {
        ScSwitch(checked, null)
    }
}

@Composable
private fun OptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    role: Role,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val c = Sc.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(ScShapes.tile).background(c.tile)
            .border(1.dp, c.hairline, ScShapes.tile)
            .clickable(role = role, onClick = onClick)
            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = c.accent)
        Spacer(Modifier.width(16.dp))
        Box(Modifier.width(1.dp).height(28.dp).background(c.hairlineStrong))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = ScType.headline.copy(fontWeight = FontWeight.Medium), color = c.text)
            Text(subtitle, style = ScType.bodySmall, color = c.text2)
        }
        trailing()
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
