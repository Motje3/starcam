package com.wm.starcam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wm.starcam.StarCamViewModel
import com.wm.starcam.camera.CamInfo
import com.wm.starcam.camera.CaptureState
import com.wm.starcam.session.AppSettings
import com.wm.starcam.session.Mode
import com.wm.starcam.session.Quality
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.components.ScCard
import com.wm.starcam.ui.components.ScGlass
import com.wm.starcam.ui.components.ScIconButton
import com.wm.starcam.ui.components.ScShutter
import com.wm.starcam.ui.components.ScSlider
import com.wm.starcam.ui.components.ScSpinner
import com.wm.starcam.ui.components.ScStatusPill
import com.wm.starcam.ui.components.Tone
import com.wm.starcam.ui.theme.NightPreviewTint
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScShapes
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.ui.theme.scImageFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The capture screen. Idle, it is a viewfinder with the few controls a night needs, kept slim so
 * the preview gets the room: status and lens on one line, mode and preset as two short rows, one
 * bar with the shutter. Everything else is one tap away in the Pro sheet or Settings.
 *
 * While a session runs, [SessionFlow] draws its full-screen steps on top. The idle layout - and
 * with it the CameraPreview - stays composed underneath, so the viewfinder surface that is part of
 * the camera session is never torn down mid-capture.
 */
@Composable
fun CaptureScreen(
    vm: StarCamViewModel,
    onOpenSessions: () -> Unit,
    onOpenSession: (File) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProbe: () -> Unit,
    onOpenTonight: () -> Unit,
) {
    val c = Sc.colors
    val settings by vm.settingsStore.settings.collectAsState()
    // The engine only exists once the camera has opened, and is replaced on every lens switch;
    // until then its flows read as idle. Observed, not read once: a plain read kept the screen
    // on a stand-in, and a session then ran without ever showing its screens.
    val engine by vm.engineFlow.collectAsState()
    val state by engine?.state.collectOr(CaptureState.Idle)
    val cameraError by vm.cameraError.collectAsState()
    val camInfo by vm.camInfo.collectAsState()
    val cameraLost by vm.cameraLost.collectAsState()
    val sessionStart by engine?.sessionStartedAtMs.collectOr(0L)

    var showPro by rememberSaveable { mutableStateOf(false) }
    var framingBoost by remember { mutableStateOf(false) }
    // A session restarts the viewfinder without the boost; the toggle must not keep claiming it.
    LaunchedEffect(state is CaptureState.Idle) { if (state !is CaptureState.Idle) framingBoost = false }

    // Screen shield: pure black over a running burst (battery, dark adaptation). Pure UI - the
    // session runs on underneath - and disarmed outside the capture phases, so the next session
    // never starts on a surprise black screen. The screen itself stays on (MainActivity's flag).
    var shieldOn by remember { mutableStateOf(false) }
    val shieldEligible =
        state is CaptureState.CapturingLights || state is CaptureState.CapturingDarks
    LaunchedEffect(shieldEligible) { if (!shieldEligible) shieldOn = false }

    Box(Modifier.fillMaxSize().background(c.background)) {
        IdleLayout(
            vm = vm,
            settings = settings,
            idle = state is CaptureState.Idle,
            framingBoost = framingBoost,
            onBoostChange = {
                framingBoost = it
                vm.setFramingBoost(it)
            },
            onOpenPro = { showPro = true },
            onOpenSessions = onOpenSessions,
            onOpenSession = onOpenSession,
            onOpenSettings = onOpenSettings,
            onOpenTonight = onOpenTonight,
        )

        // Not composed while the shield covers it: drawn underneath, its clocks and rings kept
        // the display busy for hours behind a black screen.
        if (state !is CaptureState.Idle && !(shieldOn && shieldEligible)) {
            SessionFlow(
                vm = vm,
                engine = engine,
                state = state,
                settings = settings,
                onShield = { shieldOn = true },
                onOpenSession = { dir ->
                    engine?.dismissResult()
                    onOpenSession(dir)
                },
            )
        }

        // ---- Camera unavailable. A failed session's own Error screen names its folder, so once
        // there is one it wins; this card would otherwise cover it. Nor over a finished photo:
        // the camera is reopened when the viewfinder comes back.
        if (state !is CaptureState.Error && state !is CaptureState.Done) {
            cameraError?.let { msg ->
                val sessionRunning = state !is CaptureState.Idle && state !is CaptureState.Done
                CameraProblemCard(
                    message = msg,
                    cameraLost = cameraLost,
                    sessionRunning = sessionRunning,
                    // Reopening under a running session would tear the camera down beneath it;
                    // the session repairs the camera itself, or stacks what it has.
                    onReopen = if (camInfo != null && !sessionRunning) ({ vm.reopenCamera() }) else null,
                    onProbe = onOpenProbe,
                )
            }
        }

        // ---- Shield: last child, so it draws (and takes every touch) above everything.
        if (shieldOn && shieldEligible) {
            ScreenShield(state, sessionStart, onReveal = { shieldOn = false })
        }
    }

    if (showPro && state is CaptureState.Idle) {
        ProSheet(
            vm = vm,
            framingBoost = framingBoost,
            onBoostChange = {
                framingBoost = it
                vm.setFramingBoost(it)
            },
            onOpenSettings = {
                showPro = false
                onOpenSettings()
            },
            onDismiss = { showPro = false },
        )
    }
}

/** Collects this flow, or [fallback] while there is no flow yet (no engine before the camera opens). */
@Composable
fun <T> StateFlow<T>?.collectOr(fallback: T): State<T> {
    val stand = remember { MutableStateFlow(fallback) }
    return (this ?: stand).collectAsState()
}

// ------------------------------------------------------------------------------- idle layout

@Composable
private fun IdleLayout(
    vm: StarCamViewModel,
    settings: AppSettings,
    idle: Boolean,
    framingBoost: Boolean,
    onBoostChange: (Boolean) -> Unit,
    onOpenPro: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSession: (File) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTonight: () -> Unit,
) {
    val camInfo by vm.camInfo.collectAsState()
    val ready by vm.ready.collectAsState()
    val cameraError by vm.cameraError.collectAsState()
    val restack by vm.restack.collectAsState()
    val restacking = restack?.running == true
    val plan = remember(settings, camInfo) { planFor(settings, camInfo) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopRow(vm)
        Viewfinder(
            vm = vm,
            settings = settings,
            camInfo = camInfo,
            plan = plan,
            framingBoost = framingBoost,
            restacking = restacking,
            onBoostOff = { onBoostChange(false) },
            onOpenPro = onOpenPro,
            onOpenSession = onOpenSession,
            onOpenTonight = onOpenTonight,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.height(10.dp))
        ModeTabs(settings.mode) { m -> vm.settingsStore.update { it.copy(mode = m) } }
        Spacer(Modifier.height(8.dp))
        SecondaryRow(vm, settings, plan, onOpenPro)
        BottomBar(
            vm = vm,
            settings = settings,
            idle = idle,
            canShoot = ready && cameraError == null && !restacking,
            onOpenPro = onOpenPro,
            onOpenSessions = onOpenSessions,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun TopRow(vm: StarCamViewModel) {
    val thermal by vm.thermal.status.collectAsState()
    val lenses by vm.lenses.collectAsState()
    val camInfo by vm.camInfo.collectAsState()
    val battery = rememberBatteryPercent()
    // Thermal silence is the normal state; colour only speaks when it matters.
    val tone = when {
        thermal >= 3 -> Tone.Error
        thermal >= 2 || battery in 0..20 -> Tone.Warning
        else -> Tone.Normal
    }
    Row(
        Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScStatusPill(
            text = (if (battery >= 0) "Battery $battery%" else "Battery") + " · " + vm.thermal.label(),
            tone = tone,
            icon = if (tone == Tone.Normal) null else ScIcons.Heat,
        )
        Spacer(Modifier.weight(1f))
        // Lens chips only with 2+ RAW-capable back cameras.
        if (lenses.size >= 2) {
            LensSwitch(lenses, camInfo) { vm.switchLens(it) }
        }
    }
}

@Composable
private fun LensSwitch(lenses: List<CamInfo>, current: CamInfo?, onSwitch: (String) -> Unit) {
    val c = Sc.colors
    Row(
        Modifier.height(44.dp).clip(CircleShape).background(c.sunken)
            .border(1.dp, c.hairline, CircleShape).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Widest first, like the system camera.
        lenses.sortedBy { if (it.focalLengthMm > 0f) it.focalLengthMm else Float.MAX_VALUE }.forEach { lens ->
            val sel = current?.cameraId == lens.cameraId
            val label = lensLabel(lenses, lens)
            Box(
                Modifier.fillMaxHeight().widthIn(min = 50.dp).clip(CircleShape)
                    .then(
                        if (sel) Modifier.background(c.selectedFill).border(1.dp, c.selectedBorder, CircleShape)
                        else Modifier
                    )
                    .clickable(role = Role.RadioButton) { onSwitch(lens.cameraId) }
                    .semantics { contentDescription = "Lens $label" }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = ScType.dataLarge.copy(fontWeight = FontWeight.Medium),
                    color = if (sel) c.onSelected else c.text2)
            }
        }
    }
}

// ------------------------------------------------------------------------------- viewfinder


@Composable
private fun Viewfinder(
    vm: StarCamViewModel,
    settings: AppSettings,
    camInfo: CamInfo?,
    plan: Plan,
    framingBoost: Boolean,
    restacking: Boolean,
    onBoostOff: () -> Unit,
    onOpenPro: () -> Unit,
    onOpenSession: (File) -> Unit,
    onOpenTonight: () -> Unit,
    modifier: Modifier,
) {
    val c = Sc.colors
    val lenses by vm.lenses.collectAsState()
    val interrupted by vm.interrupted.collectAsState()
    val context = LocalContext.current
    val uiPrefs = remember { UiPrefs(context) }
    var tipDismissed by remember { mutableStateOf(uiPrefs.lensTipDismissed) }

    Box(modifier.fillMaxWidth().padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(previewAspect(camInfo))) {
            CameraPreview(vm, Modifier.fillMaxSize())
            if (c.night) Box(Modifier.fillMaxSize().background(NightPreviewTint))
            ViewfinderGuides()
            RoundedMask(radius = 22.dp, color = c.background, border = c.hairline)

            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Tonight at a glance, and the way into the planner.
                TonightChip(onOpenTonight, Modifier.align(Alignment.End))
                // After a crash or a kill the app used to come back to a plain viewfinder, with
                // the cut-off session's frames sitting unannounced in Sessions.
                interrupted?.let { s ->
                    ViewfinderTip(
                        icon = ScIcons.Warning,
                        tint = c.warning,
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.SemiBold)) {
                                append("Last session was cut off. ")
                            }
                            append(
                                when {
                                    s.frameCount >= 2 -> "${s.frameCount} frames saved - tap to stack them."
                                    s.frameCount == 1 -> "1 frame saved - tap to see it."
                                    else -> "No frames were saved."
                                }
                            )
                        },
                        onClick = if (s.frameCount > 0) ({
                            vm.dismissInterrupted()
                            onOpenSession(s.dir)
                        }) else null,
                        onDismiss = { vm.dismissInterrupted() },
                    )
                }
                if (restacking) {
                    ScGlass(Modifier.align(Alignment.CenterHorizontally)) {
                        ScSpinner(size = 14.dp, strokeWidth = 2.dp)
                        Text("Re-stacking a session · the shutter is back when it's done",
                            style = ScType.caption, color = c.text)
                    }
                }
                if (!tipDismissed) {
                    WiderLensTip(
                        lenses = lenses,
                        current = camInfo,
                        onSwitch = { vm.switchLens(it) },
                        onDismiss = {
                            tipDismissed = true
                            uiPrefs.lensTipDismissed = true
                        },
                    )
                }
                if (settings.nightMode) {
                    NightDimmer(settings.nightDim) { v -> vm.settingsStore.update { it.copy(nightDim = v) } }
                }
                if (framingBoost) {
                    ScGlass(Modifier.align(Alignment.CenterHorizontally).clickable(onClick = onBoostOff)) {
                        Icon(ScIcons.SunSmall, null, Modifier.size(15.dp), tint = c.warning)
                        Text("Brighter preview · slower, ~8 fps", style = ScType.caption, color = c.text)
                        Icon(ScIcons.Close, "Turn off brighter preview", Modifier.size(14.dp), tint = c.text3)
                    }
                }
            }

            if (settings.showLevel) {
                val roll by vm.motion.rollDeg.collectAsState()
                LevelOverlay(roll)
            }

            PlanChip(
                plan = plan,
                mode = settings.mode,
                onClick = onOpenPro,
                modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
            )
        }
    }
}

/** Thirds grid and corner brackets: framing help that stays out of the way of the stars. */
@Composable
private fun ViewfinderGuides() {
    val line = Sc.colors.text.copy(alpha = 0.10f)
    val corner = Sc.colors.text.copy(alpha = 0.45f)
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val hair = 1.dp.toPx()
        for (i in 1..2) {
            drawLine(line, Offset(w * i / 3f, 0f), Offset(w * i / 3f, h), hair)
            drawLine(line, Offset(0f, h * i / 3f), Offset(w, h * i / 3f), hair)
        }
        val inset = 14.dp.toPx()
        val len = 18.dp.toPx()
        val sw = 1.5.dp.toPx()
        val corners = listOf(
            Offset(inset, inset) to Offset(1f, 1f),
            Offset(w - inset, inset) to Offset(-1f, 1f),
            Offset(inset, h - inset) to Offset(1f, -1f),
            Offset(w - inset, h - inset) to Offset(-1f, -1f),
        )
        for ((p, d) in corners) {
            drawLine(corner, p, Offset(p.x + d.x * len, p.y), sw, StrokeCap.Round)
            drawLine(corner, p, Offset(p.x, p.y + d.y * len), sw, StrokeCap.Round)
        }
    }
}

/**
 * Rounds the viewfinder. The camera draws into its own surface, which a Compose clip cannot
 * shape, so the corners outside the rounded rectangle are painted over in the background colour.
 */
@Composable
private fun RoundedMask(radius: Dp, color: Color, border: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val r = radius.toPx()
        val bounds = Rect(Offset.Zero, size)
        val outer = Path().apply { addRect(bounds) }
        val inner = Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(r))) }
        val corners = Path().apply { op(outer, inner, PathOperation.Difference) }
        drawPath(corners, color)
        drawRoundRect(border, cornerRadius = CornerRadius(r), style = Stroke(1.dp.toPx()))
    }
}

/**
 * Horizon line for landscape-astro framing. The line follows the real horizon; the short marks
 * show level for whichever way the phone is held. All four flat orientations count as level.
 */
@Composable
private fun LevelOverlay(rollDeg: Float) {
    val c = Sc.colors
    val nearest = (rollDeg / 90f).roundToInt() * 90f
    val dev = abs(rollDeg - nearest)
    val level = dev <= 0.7f
    val lineColor = if (level) c.accent else c.warning
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().rotate(nearest)) {
            val y = size.height / 2f
            val tick = size.width * 0.12f
            drawLine(c.text.copy(alpha = 0.5f), Offset(size.width * 0.08f, y), Offset(size.width * 0.08f + tick, y), 1.5.dp.toPx())
            drawLine(c.text.copy(alpha = 0.5f), Offset(size.width * 0.92f - tick, y), Offset(size.width * 0.92f, y), 1.5.dp.toPx())
        }
        Canvas(Modifier.fillMaxSize().rotate(rollDeg)) {
            val y = size.height / 2f
            drawLine(lineColor, Offset(size.width * 0.28f, y), Offset(size.width * 0.72f, y), 2.dp.toPx(), StrokeCap.Round)
        }
        ScGlass(Modifier.offset(y = 30.dp)) {
            Text("Level", style = ScType.caption, color = c.text2)
            Text(String.format(Locale.US, "%.1f°", dev), style = ScType.data, color = if (level) c.accentText else c.warning)
        }
    }
}

/**
 * A suggestion, never a switch made for you: when another lens holds stars point-like for 1.5×
 * as long or more, say so, and let a tap switch to it. Total light per frame is the whole game for
 * a wide Milky Way frame, and an ultrawide's NPF limit is several times the main lens's.
 */
@Composable
private fun WiderLensTip(
    lenses: List<CamInfo>,
    current: CamInfo?,
    onSwitch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (current == null || lenses.size < 2) return
    val currentLimit = current.trailLimitSeconds()
    val widest = lenses.filter { it.focalLengthMm > 0f }.minByOrNull { it.focalLengthMm } ?: return
    if (widest.cameraId == current.cameraId) return
    val widestLimit = widest.trailLimitSeconds()
    if (widestLimit < currentLimit * 1.5) return
    val c = Sc.colors
    val label = lensLabel(lenses, widest)
    ViewfinderTip(
        icon = ScIcons.Astro,
        tint = c.accent,
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = c.accentText, fontWeight = FontWeight.SemiBold)) { append(label) }
            append(" holds stars for ${widestLimit.toInt()} s vs ${currentLimit.toInt()} s. Tap to switch.")
        },
        onClick = { onSwitch(widest.cameraId) },
        onDismiss = onDismiss,
    )
}

/** A tappable note on the viewfinder with its own dismiss button. */
@Composable
private fun ViewfinderTip(
    icon: ImageVector,
    tint: Color,
    text: AnnotatedString,
    onClick: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val c = Sc.colors
    Row(
        Modifier.fillMaxWidth().clip(ScShapes.button).background(c.glass)
            .border(1.dp, if (tint == c.warning) c.warningBorder else c.selectedBorder, ScShapes.button)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(text, style = ScType.bodySmall, color = c.text, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onDismiss, role = Role.Button)
                .semantics { contentDescription = "Dismiss" },
            contentAlignment = Alignment.Center,
        ) { Icon(ScIcons.Close, null, Modifier.size(16.dp), tint = c.text3) }
    }
}

/** Night vision's extra dimmer, where it is needed: on top of the viewfinder. */
@Composable
private fun NightDimmer(value: Float, onChange: (Float) -> Unit) {
    val c = Sc.colors
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(CircleShape).background(c.glass)
            .border(1.dp, c.hairline, CircleShape).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ScIcons.Sun, "Screen dimming", Modifier.size(16.dp), tint = c.text2)
        ScSlider(value, onChange, 0f..0.8f, Modifier.weight(1f).padding(horizontal = 8.dp))
        Text("dim ${(value * 100).roundToInt()}%", style = ScType.data, color = c.text2)
    }
}

/** "Auto · 8.5 s × 20 · ISO auto · +4 darks ›" - what the shutter will do. Opens Pro. */
@Composable
private fun PlanChip(plan: Plan, mode: Mode, onClick: () -> Unit, modifier: Modifier) {
    val c = Sc.colors
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = c.accentText, fontWeight = FontWeight.SemiBold)) {
            append(if (plan.manual) "Manual" else "Auto")
        }
        val parts = buildList {
            when (mode) {
                Mode.TRAILS -> add("${plan.minutes} min trails")
                Mode.METEOR -> add("${plan.minutes} min watch")
                else -> Unit
            }
            add(exposureText(plan.exposureSec) + if (plan.minutes == null) " × ${plan.frames}" else " frames")
            add(plan.iso?.let { "ISO $it" } ?: "ISO auto")
            if (plan.darks > 0) add("+${plan.darks} darks")
        }
        append(" · ")
        append(parts.joinToString(" · "))
    }
    Row(
        modifier.height(40.dp).clip(CircleShape).background(c.glass).border(1.dp, c.hairline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Plan: ${text.text}. Opens Pro controls." }
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = ScType.bodySmall.copy(fontFeatureSettings = "tnum"), color = c.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(ScIcons.ChevronRight, null, Modifier.size(16.dp).padding(start = 2.dp), tint = c.text3)
    }
}

// ------------------------------------------------------------------------------- controls

private fun modeIcon(m: Mode) = when (m) {
    Mode.ASTRO -> ScIcons.Astro
    Mode.TRAILS -> ScIcons.Trails
    Mode.METEOR -> ScIcons.Meteor
    Mode.PRO -> ScIcons.ProMode
}

/** Tab widths follow the label lengths, so METEOR β gets the room PRO does not need. */
private fun modeWeight(m: Mode) = when (m) {
    Mode.ASTRO -> 1f
    Mode.TRAILS -> 1.05f
    Mode.METEOR -> 1.25f
    Mode.PRO -> 0.8f
}

@Composable
private fun ModeTabs(mode: Mode, onSelect: (Mode) -> Unit) {
    val c = Sc.colors
    // With a large system font the labels alone fill the tabs; the icons go first.
    val showIcons = LocalDensity.current.fontScale <= 1.2f
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(44.dp)
            .clip(ScShapes.chip).background(c.sunken).border(1.dp, c.hairline, ScShapes.chip)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Mode.entries.forEach { m ->
            val sel = m == mode
            val tint = if (sel) c.onSelected else c.text2
            Row(
                Modifier.weight(modeWeight(m)).fillMaxHeight().clip(ScShapes.segmentItem)
                    .then(
                        if (sel) Modifier.background(c.selectedFill).border(1.dp, c.selectedBorder, ScShapes.segmentItem)
                        else Modifier
                    )
                    .clickable(role = Role.Tab) { onSelect(m) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showIcons) {
                    Icon(modeIcon(m), null, Modifier.size(15.dp), tint = if (sel) c.accent else c.text3)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    buildAnnotatedString {
                        append(m.name)
                        if (m == Mode.METEOR) {
                            withStyle(SpanStyle(color = c.warning, baselineShift = BaselineShift.Superscript,
                                letterSpacing = 0.sp)) { append(" β") }
                        }
                    },
                    style = ScType.label, color = tint, maxLines = 1, softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The second row changes with the mode: astro quality presets; trail and meteor durations; for
 * manual exposure, the three numbers that matter, each opening the Pro sheet.
 */
@Composable
private fun SecondaryRow(vm: StarCamViewModel, s: AppSettings, plan: Plan, onOpenPro: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(38.dp)
    when {
        s.mode == Mode.TRAILS -> OptionStrip(
            options = listOf(10, 20, 30, 60),
            selected = s.trailsMinutes,
            text = { "$it" }, unit = "min",
            modifier = modifier,
        ) { m -> vm.settingsStore.update { it.copy(trailsMinutes = m) } }
        s.mode == Mode.METEOR -> OptionStrip(
            options = listOf(15, 30, 60, 120),
            selected = s.meteorMinutes,
            text = { "$it" }, unit = "min",
            modifier = modifier,
        ) { m -> vm.settingsStore.update { it.copy(meteorMinutes = m) } }
        plan.manual -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ManualChip("Shutter", exposureText(plan.exposureSec), Modifier.weight(1f), onOpenPro)
            ManualChip("ISO", "${plan.iso ?: s.iso}", Modifier.weight(1f), onOpenPro)
            ManualChip("Frames", "${plan.frames}", Modifier.weight(1f), onOpenPro)
        }
        else -> QualityStrip(s.quality, modifier) { q -> vm.settingsStore.update { it.copy(quality = q) } }
    }
}

@Composable
private fun QualityStrip(quality: Quality, modifier: Modifier, onSelect: (Quality) -> Unit) {
    val c = Sc.colors
    Row(
        modifier.clip(ScShapes.chip).background(c.sunken).border(1.dp, c.hairline, ScShapes.chip).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Quality.entries.forEach { q ->
            val sel = q == quality
            Row(
                Modifier.weight(1f).fillMaxHeight().clip(ScShapes.segmentItem)
                    .then(
                        if (sel) Modifier.background(c.selectedFill).border(1.dp, c.selectedBorder, ScShapes.segmentItem)
                        else Modifier
                    )
                    .clickable(role = Role.RadioButton) { onSelect(q) }
                    .semantics { contentDescription = "${q.name.lowercase()}, ${q.frames} frames" },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(q.name, style = ScType.label, color = if (sel) c.onSelected else c.text2)
                Spacer(Modifier.width(6.dp))
                Text("${q.frames}", style = ScType.data, color = if (sel) c.accentText else c.text3)
            }
        }
    }
}

@Composable
private fun OptionStrip(
    options: List<Int>,
    selected: Int,
    text: (Int) -> String,
    unit: String,
    modifier: Modifier,
    onSelect: (Int) -> Unit,
) {
    val c = Sc.colors
    Row(
        modifier.clip(ScShapes.chip).background(c.sunken).border(1.dp, c.hairline, ScShapes.chip).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { o ->
            val sel = o == selected
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(ScShapes.segmentItem)
                    .then(
                        if (sel) Modifier.background(c.selectedFill).border(1.dp, c.selectedBorder, ScShapes.segmentItem)
                        else Modifier
                    )
                    .clickable(role = Role.RadioButton) { onSelect(o) },
                contentAlignment = Alignment.Center,
            ) {
                Text("${text(o)} $unit", style = ScType.data.copy(fontWeight = FontWeight.Medium),
                    color = if (sel) c.onSelected else c.text2)
            }
        }
    }
}

@Composable
private fun ManualChip(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Sc.colors
    Row(
        modifier.fillMaxHeight().clip(ScShapes.chip).background(c.buttonSecondary)
            .border(1.dp, c.hairline, ScShapes.chip).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(label.uppercase(), style = ScType.label, color = c.text3)
        Spacer(Modifier.width(6.dp))
        Text(value, style = ScType.data.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 1)
    }
}

@Composable
private fun BottomBar(
    vm: StarCamViewModel,
    settings: AppSettings,
    idle: Boolean,
    canShoot: Boolean,
    onOpenPro: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val c = Sc.colors
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    // Refreshed whenever the screen returns to idle, so a session just finished shows up.
    LaunchedEffect(idle) {
        if (idle) {
            thumb = withContext(Dispatchers.IO) {
                latestPhoto(vm.repo.sessionsRoot())?.let { decodeDownsampled(it.absolutePath, 192)?.asImageBitmap() }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarItem("Gallery") {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(c.buttonSecondary)
                    .border(1.dp, c.hairlineStrong, CircleShape)
                    .clickable(role = Role.Button, onClick = onOpenSessions)
                    .semantics { contentDescription = "Sessions gallery" },
                contentAlignment = Alignment.Center,
            ) {
                val t = thumb
                if (t != null) {
                    Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                        colorFilter = scImageFilter)
                } else {
                    Icon(ScIcons.Gallery, null, Modifier.size(20.dp), tint = c.text)
                }
            }
        }
        BarItem("Night", selected = settings.nightMode) {
            ScIconButton(
                ScIcons.Night,
                if (settings.nightMode) "Night vision on" else "Night vision off",
                onClick = { vm.settingsStore.update { it.copy(nightMode = !it.nightMode) } },
                selected = settings.nightMode,
            )
        }
        ScShutter(
            enabled = canShoot,
            onClick = { vm.startSession() },
            contentDescription = "Start ${settings.mode.name.lowercase()} capture",
        )
        BarItem("Pro") {
            ScIconButton(ScIcons.Sliders, "Pro controls", onClick = onOpenPro)
        }
        BarItem("Settings") {
            ScIconButton(ScIcons.Settings, "Settings", onClick = onOpenSettings)
        }
    }
}

@Composable
private fun BarItem(label: String, selected: Boolean = false, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Text(
            label, style = ScType.caption,
            color = if (selected) Sc.colors.accentText else Sc.colors.text3,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

// ------------------------------------------------------------------------------- overlays

@Composable
private fun CameraProblemCard(
    message: String,
    cameraLost: Boolean,
    sessionRunning: Boolean,
    onReopen: (() -> Unit)?,
    onProbe: () -> Unit,
) {
    val c = Sc.colors
    // No full-screen scrim: the bottom bar (Sessions, Settings) must stay reachable when the
    // camera cannot come back - on a phone without a usable RAW camera, it never will.
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        ScCard(Modifier.fillMaxWidth().blockTouches(), color = c.raised, borderColor = c.errorBorder) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(c.errorFill).border(1.dp, c.errorBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(ScIcons.Warning, null, Modifier.size(20.dp), tint = c.error) }
                Spacer(Modifier.width(12.dp))
                Text("Camera problem", style = ScType.titleSmall, color = c.text)
            }
            Text(message, style = ScType.body, color = c.text2, modifier = Modifier.padding(top = 12.dp))
            if (cameraLost) {
                Text("Frames already captured are safe on disk.", style = ScType.bodySmall,
                    color = c.text3, modifier = Modifier.padding(top = 6.dp))
            }
            if (sessionRunning) {
                Text("The session is trying to bring the camera back by itself.", style = ScType.bodySmall,
                    color = c.text3, modifier = Modifier.padding(top = 6.dp))
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ScButton("Capability probe", onProbe, Modifier.weight(1f))
                if (onReopen != null) {
                    ScButton("Reopen camera", onReopen, Modifier.weight(1f), kind = ScButtonKind.Primary,
                        icon = ScIcons.Refresh)
                }
            }
        }
    }
}

/** Pure black, a dim counter and clock that still tick, double-tap anywhere to come back. */
@Composable
private fun ScreenShield(state: CaptureState, sessionStart: Long, onReveal: () -> Unit) {
    val night = Sc.colors.night
    val dim = if (night) Color(0xFF4A100B) else Color(0xFF3A3A3A)
    val dimmer = if (night) Color(0xFF350B07) else Color(0xFF2A2A2A)
    val (cur, tot, what) = when (state) {
        is CaptureState.CapturingLights -> Triple(state.current, state.total, "frame")
        is CaptureState.CapturingDarks -> Triple(state.current, state.total, "dark")
        else -> Triple(0, 0, "frame")
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            // Takes every touch, so nothing underneath can be pressed by accident.
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onReveal() }) },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$what $cur / $tot", style = ScType.data, color = dim)
            if (sessionStart != 0L) {
                val elapsed = rememberElapsedMs(sessionStart)
                Text(durationClock(elapsed / 1000L), style = ScType.data, color = dim)
            }
            Text("double-tap to show", style = ScType.caption, color = dimmer,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** m:ss / h:mm:ss for live clocks; fixed-width digits so nothing shifts as it ticks. */
fun durationClock(totalSec: Long): String {
    val t = totalSec.coerceAtLeast(0L)
    return if (t >= 3600) String.format(Locale.US, "%d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
    else String.format(Locale.US, "%d:%02d", t / 60, t % 60)
}
