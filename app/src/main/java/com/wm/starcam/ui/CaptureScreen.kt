package com.wm.starcam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wm.starcam.StarCamViewModel
import com.wm.starcam.camera.CamInfo
import com.wm.starcam.camera.CaptureState
import com.wm.starcam.session.AppSettings
import com.wm.starcam.session.Mode
import com.wm.starcam.session.Quality
import com.wm.starcam.sky.MoonPhase
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.components.ScCard
import com.wm.starcam.ui.components.ScChevron
import com.wm.starcam.ui.components.ScGlass
import com.wm.starcam.ui.components.ScShutter
import com.wm.starcam.ui.components.ScSlider
import com.wm.starcam.ui.components.ScSpinner
import com.wm.starcam.ui.components.ScSwitch
import com.wm.starcam.ui.components.ScTab
import com.wm.starcam.ui.components.ScTabs
import com.wm.starcam.ui.theme.DuoIcon
import com.wm.starcam.ui.theme.NightPreviewTint
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScColors
import com.wm.starcam.ui.theme.ScDuo
import com.wm.starcam.ui.theme.ScDuoIcon
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScShapes
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.ui.theme.scBackground
import com.wm.starcam.ui.theme.scGlow
import com.wm.starcam.ui.theme.scImageFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The capture screen, as the owner's design (Nov 2026) has it: the light map, tonight (a moon),
 * Find and Quick tools (Find, night vision, the sky view, Settings) along the top, the viewfinder
 * across the whole width with the session length and the lenses over its foot,
 * the modes as tabs, and the gallery, the shutter and Adjust at the bottom.
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
    onOpenStudio: (File) -> Unit,
    onOpenDarkSky: () -> Unit,
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
    val noRawCamera by vm.noRawCamera.collectAsState()
    val sky by vm.skyView.collectAsState()
    val sessionStart by engine?.sessionStartedAtMs.collectOr(0L)

    var showPro by rememberSaveable { mutableStateOf(false) }
    var framingBoost by remember { mutableStateOf(false) }
    // The cards the top row opens: tonight's, from the moon, and Quick tools, from the square.
    var tonightOpen by remember { mutableStateOf(false) }
    var toolsOpen by remember { mutableStateOf(false) }
    // Where the moon's middle is, for the tail of tonight's card (px, from the root's left).
    var tonightX by remember { mutableFloatStateOf(0f) }
    // A session restarts the viewfinder without the boost; the toggle must not keep claiming it.
    // Nor do the cards stay open over it.
    LaunchedEffect(state is CaptureState.Idle) {
        if (state !is CaptureState.Idle) {
            framingBoost = false
            tonightOpen = false
            toolsOpen = false
        }
    }
    // Worked out again when the sky view closes too: a place may have been set from it.
    val tonight = rememberTonightSummary(state is CaptureState.Idle, refresh = sky == null)

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
            tonight = tonight,
            framingBoost = framingBoost,
            tonightOpen = tonightOpen,
            toolsOpen = toolsOpen,
            onBoostChange = {
                framingBoost = it
                vm.setFramingBoost(it)
            },
            onOpenPro = { showPro = true },
            onOpenSessions = onOpenSessions,
            onOpenSession = onOpenSession,
            onOpenDarkSky = onOpenDarkSky,
            onTonight = { tonightOpen = !tonightOpen },
            onTonightPlaced = { tonightX = it },
            onTools = { toolsOpen = !toolsOpen },
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
                onOpenStudio = { dir ->
                    engine?.dismissResult()
                    onOpenStudio(dir)
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
                    noRaw = noRawCamera,
                    cameraLost = cameraLost,
                    sessionRunning = sessionRunning,
                    // Reopening under a running session would tear the camera down beneath it;
                    // the session repairs the camera itself, or stacks what it has.
                    onReopen = if (camInfo != null && !sessionRunning) ({ vm.reopenCamera() }) else null,
                    onProbe = onOpenProbe,
                )
            }
        }

        // ---- The top row's cards: over the camera card too, which on a phone without a RAW camera
        // never goes away, so that Settings stays within reach.
        if (tonightOpen && state is CaptureState.Idle) {
            TonightCard(
                t = tonight,
                anchorX = tonightX,
                onPlan = {
                    tonightOpen = false
                    onOpenTonight()
                },
                onClose = { tonightOpen = false },
            )
        }
        if (toolsOpen && state is CaptureState.Idle) {
            QuickTools(
                nightMode = settings.nightMode,
                skyOn = sky != null,
                onFind = {
                    toolsOpen = false
                    vm.skyView.value = SkyViewRequest(pick = true)
                },
                onNight = { vm.settingsStore.update { it.copy(nightMode = !it.nightMode) } },
                onSky = { vm.skyView.value = if (vm.skyView.value == null) SkyViewRequest() else null },
                onSettings = {
                    toolsOpen = false
                    onOpenSettings()
                },
                onClose = { toolsOpen = false },
            )
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
    tonight: TonightSummary?,
    framingBoost: Boolean,
    tonightOpen: Boolean,
    toolsOpen: Boolean,
    onBoostChange: (Boolean) -> Unit,
    onOpenPro: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSession: (File) -> Unit,
    onOpenDarkSky: () -> Unit,
    onTonight: () -> Unit,
    onTonightPlaced: (Float) -> Unit,
    onTools: () -> Unit,
) {
    val camInfo by vm.camInfo.collectAsState()
    val ready by vm.ready.collectAsState()
    val cameraError by vm.cameraError.collectAsState()
    val restack by vm.restack.collectAsState()
    val sky by vm.skyView.collectAsState()
    val restacking = restack?.running == true
    val plan = remember(settings, camInfo) { planFor(settings, camInfo) }
    val lengths = remember(settings, plan, camInfo) { lengthsFor(vm, settings, plan, camInfo) }
    // The session lengths, opened from the pill over the viewfinder's foot. The menu stands on
    // the pill, so the height of what is under the viewfinder is kept.
    var lengthsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(idle) { if (!idle) lengthsOpen = false }
    var footHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize().scBackground().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            TopRow(
                tonight = tonight,
                tonightOpen = tonightOpen,
                toolsOpen = toolsOpen,
                onLightMap = onOpenDarkSky,
                onTonight = onTonight,
                onTonightPlaced = onTonightPlaced,
                onFind = { vm.skyView.value = SkyViewRequest(pick = true) },
                onTools = onTools,
            )
            Viewfinder(
                vm = vm,
                settings = settings,
                camInfo = camInfo,
                idle = idle,
                lengths = lengths,
                lengthsOpen = lengthsOpen,
                onLengths = { if (lengths.options.isEmpty()) onOpenPro() else lengthsOpen = !lengthsOpen },
                framingBoost = framingBoost,
                restacking = restacking,
                onBoostOff = { onBoostChange(false) },
                onOpenSession = onOpenSession,
                modifier = Modifier.weight(1f),
            )
            Column(Modifier.onSizeChanged { footHeight = with(density) { it.height.toDp() } }) {
                ModeTabs(settings.mode) { m -> vm.settingsStore.update { it.copy(mode = m) } }
                BottomBar(
                    vm = vm,
                    settings = settings,
                    idle = idle,
                    canShoot = ready && cameraError == null && !restacking,
                    onOpenPro = onOpenPro,
                    onOpenSessions = onOpenSessions,
                )
            }
        }
        if (lengthsOpen && sky == null) {
            BackHandler { lengthsOpen = false }
            DismissLayer { lengthsOpen = false }
            LengthMenu(
                options = lengths.options,
                onPick = { i ->
                    lengths.onPick(i)
                    lengthsOpen = false
                },
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = footHeight + FootRowLift + FootRowHeight + 4.dp),
            )
        }
    }
}

// ------------------------------------------------------------------------------- top row

/**
 * The top row: the light map (how dark the sky is here, and where it is darker) and tonight (the
 * Moon, the dark hours, the best thing to shoot) on the left; Find (the sky view's list of
 * galaxies, planets and stars to be led to) and the square that opens Quick tools on the right.
 * On a narrow phone the pills lose their words.
 */
@Composable
private fun TopRow(
    tonight: TonightSummary?,
    tonightOpen: Boolean,
    toolsOpen: Boolean,
    onLightMap: () -> Unit,
    onTonight: () -> Unit,
    onTonightPlaced: (Float) -> Unit,
    onFind: () -> Unit,
    onTools: () -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(TopRowHeight).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The words go in only where they fit, whatever the phone's width and font size: first
        // the light map's (its icon says less), then Find's.
        fun wordWidth(s: String) = with(density) { measurer.measure(s, PillText).size.width.toDp() } + 10.dp
        val bare = 44.dp * 4 + 8.dp + 8.dp + 10.dp
        val lightWords = bare + wordWidth("Light map") <= maxWidth
        val findWords = lightWords && bare + wordWidth("Light map") + wordWidth("Find") <= maxWidth
        // What tonight's card says, for those who hear the screen rather than see it.
        val tonightWords = when {
            tonight == null -> null
            !tonight.hasPlace -> "set your place"
            else -> listOfNotNull(tonight.bortle?.let { "Bortle $it" }, tonight.darkText).joinToString(" · ").ifEmpty { null }
        }
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            TopPill(ScDuo.LightMap, if (lightWords) "Light map" else null,
                "Light map: how dark the sky is around you", onLightMap)
            Spacer(Modifier.width(8.dp))
            TopPill(
                ScDuo.Tonight, null, "Tonight" + (tonightWords?.let { ": $it" } ?: ""), onTonight,
                Modifier.onGloballyPositioned { onTonightPlaced(it.positionInRoot().x + it.size.width / 2f) },
                lit = tonightOpen,
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            TopPill(ScDuo.Find, if (findWords) "Find" else null,
                "Find in the sky: a galaxy, a planet, a star", onFind)
            Spacer(Modifier.width(10.dp))
            ToolsButton(toolsOpen, onTools)
        }
    }
}

/** The top pills' words: a step smaller than a button's, as the design sets them. */
private val PillText = ScType.button.copy(fontSize = 14.sp)

/**
 * The design keeps the top row and the row over the viewfinder's foot slim, so the sky gets the
 * room. The top row is its 44 dp buttons and 8 dp above and below them; the cards it opens hang
 * from it, their tails' tips [CardTop] down, just under the buttons.
 */
private val TopRowHeight = 60.dp

/** The row over the viewfinder's foot: a finger's height, the pills drawn smaller inside it. */
private val FootRowHeight = 44.dp
private val FootPillHeight = 36.dp
private val FootRowLift = 16.dp

/** A pill [height] tall across the middle of what is being drawn. */
private fun DrawScope.footPill(fill: Color, edge: Color? = null, height: Dp = FootPillHeight) {
    val h = height.toPx()
    val top = (size.height - h) / 2f
    drawRoundRect(fill, Offset(0f, top), Size(size.width, h), CornerRadius(h / 2f))
    if (edge != null) {
        drawRoundRect(edge, Offset(0f, top), Size(size.width, h), CornerRadius(h / 2f), style = Stroke(1.dp.toPx()))
    }
}

/** See-through, so as much of the sky as can be shows behind the row's pills. */
private val ScColors.footGlass: Color get() = glass.copy(alpha = 0.42f)
private val CardTop = TopRowHeight - 5.dp

/** A glowing pill: a two-tone icon and, room permitting, its name; lit while its card is open. */
@Composable
private fun TopPill(
    icon: DuoIcon,
    text: String?,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lit: Boolean = false,
) {
    val c = Sc.colors
    Row(
        modifier.fillMaxHeight()
            .scGlow(c.accentGlow.copy(alpha = c.accentGlow.alpha * (if (lit) 0.6f else 0.35f)), radius = 10.dp)
            .clip(CircleShape)
            .background(
                if (lit) Brush.verticalGradient(listOf(c.selectedFill, c.selectedFill))
                else Brush.verticalGradient(listOf(c.raised, c.buttonSecondary))
            )
            .border(1.dp, if (lit) c.selectedBorder else c.selectedBorder.copy(alpha = 0.75f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(start = 10.dp, end = if (text != null) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScDuoIcon(icon, Modifier.size(24.dp))
        if (text != null) {
            Spacer(Modifier.width(7.dp))
            Text(text, style = PillText, color = c.text, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * The square that opens Quick tools: lit, as the design draws it, and its right-hand rings filled
 * while they are open.
 */
@Composable
private fun ToolsButton(open: Boolean, onClick: () -> Unit) {
    val c = Sc.colors
    Box(
        Modifier.size(44.dp)
            .scGlow(c.accentGlow.copy(alpha = c.accentGlow.alpha * 0.6f), radius = 12.dp, cornerRadius = 16.dp)
            .clip(ScShapes.iconButton)
            .background(c.selectedFill)
            .border(1.dp, c.selectedBorder, ScShapes.iconButton)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Quick tools" },
        contentAlignment = Alignment.Center,
    ) {
        if (open) ScDuoIcon(ScDuo.ToolsOpen, Modifier.size(22.dp), base = c.iconAccent, accent = c.iconBase)
        else ScDuoIcon(ScDuo.Tools, Modifier.size(22.dp))
    }
}

// ------------------------------------------------------------------------------ the Moon

/**
 * The Moon as it is: the lit part drawn with its terminator, waxing lit on the right (on the left
 * south of the equator). A dim disc when the phase is not known yet.
 */
@Composable
fun MoonGlyph(phase: MoonPhase?, southern: Boolean, modifier: Modifier = Modifier) {
    val c = Sc.colors
    val lit = c.accentText
    val dark = c.accent.copy(alpha = 0.12f)
    val rim = c.accent.copy(alpha = 0.3f)
    Canvas(modifier) {
        val r = size.minDimension / 2f * 0.9f
        val o = center
        // The unlit Moon only just there, as on a real night.
        drawCircle(dark, r, o)
        drawCircle(rim, r, o, style = Stroke(1.dp.toPx()))
        if (phase == null) return@Canvas
        val k = phase.illuminated.toFloat().coerceIn(0f, 1f)
        if (k < 0.01f) return@Canvas
        val path = Path().apply {
            arcTo(Rect(o.x - r, o.y - r, o.x + r, o.y + r), -90f, 180f, true)
            // The terminator: half an ellipse, or a straight line at a half Moon.
            val rx = r * abs(1f - 2f * k)
            if (rx < 0.5f) lineTo(o.x, o.y - r)
            else arcTo(Rect(o.x - rx, o.y - r, o.x + rx, o.y + r), 90f, if (k >= 0.5f) 180f else -180f, false)
            close()
        }
        // Waxing in the north: lit on the right. Waning, or the south, mirrored.
        val mirror = phase.waxing == southern
        scale(if (mirror) -1f else 1f, 1f, pivot = o) { drawPath(path, lit) }
    }
}

// ------------------------------------------------------------------------------- viewfinder

/** Battery and temperature, on the viewfinder only when they matter. */
@Composable
private fun PowerWarning(vm: StarCamViewModel, modifier: Modifier) {
    val c = Sc.colors
    val thermal by vm.thermal.status.collectAsState()
    val battery = rememberBatteryPercent()
    val hot = thermal >= 2
    val low = battery in 0..20
    if (!hot && !low) return
    ScGlass(modifier) {
        Icon(if (hot) ScIcons.Heat else ScIcons.Warning, null, Modifier.size(15.dp),
            tint = if (thermal >= 3) c.error else c.warning)
        Text(
            listOfNotNull(
                if (low) "Battery $battery%" else null,
                if (hot) "phone ${vm.thermal.label()}" else null,
            ).joinToString(" · ").replaceFirstChar { it.uppercase() },
            style = ScType.caption, color = c.text,
        )
    }
}

@Composable
private fun LensSwitch(lenses: List<CamInfo>, current: CamInfo?, onSwitch: (String) -> Unit) {
    val c = Sc.colors
    Row(
        Modifier.height(FootRowHeight).drawBehind { footPill(c.footGlass, c.hairline) }.padding(horizontal = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Widest first, like the system camera.
        lenses.sortedBy { if (it.focalLengthMm > 0f) it.focalLengthMm else Float.MAX_VALUE }.forEach { lens ->
            val sel = current?.cameraId == lens.cameraId
            val label = lensLabel(lenses, lens)
            Box(
                Modifier.fillMaxHeight().widthIn(min = 42.dp).clip(CircleShape)
                    .drawBehind { if (sel) footPill(c.text.copy(alpha = 0.18f), height = FootPillHeight - 6.dp) }
                    .clickable(role = Role.RadioButton) { onSwitch(lens.cameraId) }
                    .semantics { contentDescription = "Lens $label" }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = ScType.dataLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    color = if (sel) c.text else c.text2)
            }
        }
    }
}

/**
 * The viewfinder across the whole width (or height, on a short phone), the session length and the
 * lenses over its foot. The corner brackets mark the part of the frame the foot leaves in view.
 */
@Composable
private fun Viewfinder(
    vm: StarCamViewModel,
    settings: AppSettings,
    camInfo: CamInfo?,
    idle: Boolean,
    lengths: Lengths,
    lengthsOpen: Boolean,
    onLengths: () -> Unit,
    framingBoost: Boolean,
    restacking: Boolean,
    onBoostOff: () -> Unit,
    onOpenSession: (File) -> Unit,
    modifier: Modifier,
) {
    val c = Sc.colors
    val lenses by vm.lenses.collectAsState()
    val sky by vm.skyView.collectAsState()
    val interrupted by vm.interrupted.collectAsState()
    val context = LocalContext.current
    val uiPrefs = remember { UiPrefs(context) }
    var tipDismissed by remember { mutableStateOf(uiPrefs.lensTipDismissed) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val aspect = previewAspect(camInfo)
        val fullWidth = maxWidth / aspect <= maxHeight
        val pw = if (fullWidth) maxWidth else maxHeight * aspect
        val ph = if (fullWidth) maxWidth / aspect else maxHeight
        val previewTop = (maxHeight - ph) / 2
        // How far the row over the foot reaches into the preview from its foot (the preview is
        // centred, so the gap is the same at both ends).
        val selectorHead = FootRowLift + FootRowHeight + 2.dp - previewTop

        Box(Modifier.align(Alignment.Center).size(pw, ph)) {
            CameraPreview(vm, Modifier.fillMaxSize())
            if (c.night) Box(Modifier.fillMaxSize().background(NightPreviewTint))
            sky?.let { request ->
                SkyOverlay(
                    request = request,
                    cam = camInfo,
                    // A new time keeps what is being found.
                    onTime = { vm.skyView.value = vm.skyView.value?.copy(atMs = it) ?: SkyViewRequest(it) },
                    onClose = { vm.skyView.value = null },
                    topInset = 0.dp,
                    // A Canvas does not clip: the constellations ran over the top row and tabs.
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                )
            }
            if (sky == null) {
                ViewfinderGuides(Modifier.fillMaxSize().padding(bottom = selectorHead.coerceAtLeast(0.dp)))
            }
            if (settings.showLevel) {
                val roll by vm.motion.rollDeg.collectAsState()
                LevelOverlay(roll)
            }
        }

        // ---- over the top: whatever needs saying
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PowerWarning(vm, Modifier.align(Alignment.End))
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

        // ---- over the foot: how long the session takes, and the lenses. The sky view puts its
        // clock there instead.
        if (sky == null) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = FootRowLift),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LengthPill(lengths, lengthsOpen, onLengths)
                Spacer(Modifier.weight(1f))
                // Lens chips only with 2+ RAW-capable back cameras.
                if (lenses.size >= 2) LensSwitch(lenses, camInfo) { vm.switchLens(it) }
            }
        }
    }
}

/** Corner brackets: the framing guide, out of the way of the stars. */
@Composable
private fun ViewfinderGuides(modifier: Modifier) {
    val corner = Sc.colors.text.copy(alpha = 0.7f)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val inset = 16.dp.toPx()
        val len = 22.dp.toPx()
        val sw = 1.6.dp.toPx()
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
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.glass)
            .border(1.dp, if (tint == c.warning) c.warningBorder else c.selectedBorder.copy(alpha = 0.7f), shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(17.dp), tint = tint)
        Spacer(Modifier.width(9.dp))
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
        Modifier.fillMaxWidth().height(42.dp).clip(CircleShape).background(c.glass)
            .border(1.dp, c.hairline, CircleShape).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ScIcons.Sun, "Screen dimming", Modifier.size(16.dp), tint = c.text2)
        ScSlider(value, onChange, 0f..0.8f, Modifier.weight(1f).padding(horizontal = 8.dp))
        Text("dim ${(value * 100).roundToInt()}%", style = ScType.data, color = c.text2)
    }
}

// ------------------------------------------------------------------------------- session length

/**
 * About how long a session with [p] takes, in whole minutes: the frames with their gaps, the dark
 * frames, and the minute or so of settling, measuring and focusing before them. "Shoot for" sets
 * the time itself.
 */
private fun sessionMinutes(p: Plan, s: AppSettings): Int {
    if (s.mode == Mode.ASTRO && s.autopilotMinutes > 0) return s.autopilotMinutes
    val seconds = p.frames * (p.exposureSec + s.cooldownSec + 0.5) + p.darks * (p.exposureSec + 0.5) + 45.0
    return ceil(seconds / 60.0).toInt().coerceAtLeast(1)
}

/** One session length on offer: its value, and a word beside it (the Stars presets' names). */
private class LengthOption(val value: String, val label: String?, val selected: Boolean, val description: String)

/**
 * What the session-length pill offers changes with the mode: the trail and meteor durations; how
 * long a Stars session takes at each preset. With numbers of one's own (Manual, or Adjust set by
 * hand) the length follows from them, so the pill offers nothing and opens Adjust. [current] is
 * what the pill says.
 */
private class Lengths(val current: String, val options: List<LengthOption>, val onPick: (Int) -> Unit)

private fun lengthsFor(vm: StarCamViewModel, s: AppSettings, plan: Plan, cam: CamInfo?): Lengths = when {
    s.mode == Mode.TRAILS -> {
        val options = listOf(10, 20, 30, 60)
        Lengths(
            "${s.trailsMinutes} min",
            options.map { m -> LengthOption("$m min", null, m == s.trailsMinutes, "Star trails for $m minutes") },
        ) { i -> vm.settingsStore.update { it.copy(trailsMinutes = options[i]) } }
    }
    s.mode == Mode.METEOR -> {
        val options = listOf(15, 30, 60, 120)
        fun text(m: Int) = if (m >= 60) "${m / 60} h" else "$m min"
        Lengths(
            text(s.meteorMinutes),
            options.map { m -> LengthOption(text(m), null, m == s.meteorMinutes, "Watch for meteors for $m minutes") },
        ) { i -> vm.settingsStore.update { it.copy(meteorMinutes = options[i]) } }
    }
    plan.manual -> Lengths("~${sessionMinutes(plan, s)} min", emptyList()) {}
    else -> {
        val options = Quality.entries.map { q ->
            val minutes = sessionMinutes(planFor(s.copy(quality = q), cam), s)
            LengthOption("~$minutes min", qualityName(q), q == s.quality,
                "${qualityName(q)}: about $minutes minutes, ${q.frames} frames")
        }
        Lengths(options.firstOrNull { it.selected }?.value ?: "~${sessionMinutes(plan, s)} min", options) { i ->
            vm.settingsStore.update { it.copy(quality = Quality.entries[i]) }
        }
    }
}

/** How long the session takes, over the viewfinder's foot: a clock, the length, a chevron. */
@Composable
private fun LengthPill(lengths: Lengths, open: Boolean, onClick: () -> Unit) {
    val c = Sc.colors
    val opensAdjust = lengths.options.isEmpty()
    Row(
        Modifier.height(FootRowHeight).clip(CircleShape).drawBehind { footPill(c.footGlass, c.hairline) }
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "Session length: ${lengths.current}" + if (opensAdjust) ". Opens Adjust" else ""
            }
            .padding(start = 11.dp, end = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ScIcons.Clock, null, Modifier.size(17.dp), tint = c.iconBase)
        Spacer(Modifier.width(7.dp))
        Text(lengths.current, style = PillText.copy(fontSize = 13.sp, fontFeatureSettings = "tnum"), color = c.text, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        ScChevron(open, Modifier.size(15.dp), tint = c.text)
    }
}

/** The lengths on offer, standing on the pill; the chosen one lit. */
@Composable
private fun LengthMenu(options: List<LengthOption>, onPick: (Int) -> Unit, modifier: Modifier) {
    val c = Sc.colors
    val shape = RoundedCornerShape(20.dp)
    val row = RoundedCornerShape(15.dp)
    Column(
        modifier.width(IntrinsicSize.Max).widthIn(min = 140.dp)
            .scGlow(c.accentGlow.copy(alpha = c.accentGlow.alpha * 0.35f), radius = 14.dp, cornerRadius = 20.dp)
            .clip(shape).background(popoverFill(c)).border(1.dp, c.selectedBorder, shape)
            .blockTouches()
            .padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, o ->
            Row(
                Modifier.fillMaxWidth().height(44.dp).clip(row)
                    .then(if (o.selected) Modifier.background(c.selectedFill).border(1.dp, c.selectedBorder, row) else Modifier)
                    .clickable(role = Role.RadioButton) { onPick(i) }
                    .semantics {
                        contentDescription = o.description
                        selected = o.selected
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(o.value, style = ScType.headline.copy(fontSize = 16.sp, lineHeight = 19.sp,
                    fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                    color = if (o.selected) c.onSelected else c.text, maxLines = 1)
                if (o.label != null) {
                    Spacer(Modifier.weight(1f).widthIn(min = 16.dp))
                    Text(o.label, style = ScType.bodySmall, color = if (o.selected) c.accentText else c.text2, maxLines = 1)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------- modes

@Composable
private fun ModeTabs(mode: Mode, onSelect: (Mode) -> Unit) {
    ScTabs(
        tabs = listOf(
            ScTab(ScIcons.Astro, "Stars"),
            ScTab(ScIcons.Trails, "Star trails"),
            ScTab(ScIcons.Meteor, "Meteor", badge = "β"),
            ScTab(ScIcons.ProMode, "Manual"),
        ),
        selectedIndex = mode.ordinal,
        onSelect = { onSelect(Mode.entries[it]) },
        weights = listOf(0.8f, 1.2f, 1.07f, 0.93f),
        modifier = Modifier.padding(horizontal = 4.dp),
        height = 46.dp,
    )
}

// ------------------------------------------------------------------------------- bottom

/** The words the shutter's description gives each mode: "Start astro capture". */
private fun captureName(m: Mode) = when (m) {
    Mode.ASTRO -> "astro"
    Mode.TRAILS -> "trails"
    Mode.METEOR -> "meteor"
    Mode.PRO -> "manual"
}

@Composable
private fun BottomBar(
    vm: StarCamViewModel,
    settings: AppSettings,
    idle: Boolean,
    canShoot: Boolean,
    onOpenPro: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    val c = Sc.colors
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    // Refreshed whenever the screen returns to idle, so a session just finished shows up.
    LaunchedEffect(idle) {
        if (idle) {
            thumb = withContext(Dispatchers.IO) {
                latestPhoto(vm.repo.sessionsRoot())?.let { decodeDownsampled(it.absolutePath, 256)?.asImageBitmap() }
            }
        }
    }
    val square = RoundedCornerShape(17.dp)
    Row(
        Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 14.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarItem("Gallery") {
            Box(
                Modifier.size(58.dp).clip(square)
                    .background(Brush.verticalGradient(listOf(c.raised, c.buttonSecondary)))
                    .border(1.dp, c.hairlineStrong, square)
                    .clickable(role = Role.Button, onClick = onOpenSessions)
                    .semantics { contentDescription = "Sessions gallery" },
                contentAlignment = Alignment.Center,
            ) {
                val t = thumb
                if (t != null) {
                    Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                        colorFilter = scImageFilter)
                    Box(Modifier.fillMaxSize().border(1.dp, c.hairlineStrong, square))
                } else {
                    Icon(ScIcons.Photo, null, Modifier.size(26.dp), tint = c.iconBase)
                }
            }
        }
        BarItem(null) {
            ScShutter(
                enabled = canShoot,
                onClick = { vm.startSession() },
                contentDescription = "Start ${captureName(settings.mode)} capture",
                size = 84.dp,
            )
        }
        BarItem("Adjust") {
            Box(
                Modifier.size(58.dp).clip(square)
                    .background(Brush.verticalGradient(listOf(c.raised, c.buttonSecondary)))
                    .border(1.dp, c.hairlineStrong, square)
                    .clickable(role = Role.Button, onClick = onOpenPro)
                    .semantics { contentDescription = "Adjust: exposure, ISO, frames, focus" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(ScIcons.Sliders, null, Modifier.size(28.dp), tint = c.iconBase)
            }
        }
    }
}

/**
 * A bottom-bar item with its name under it. The shutter gets an empty line of the same height, so
 * all three centre on one line.
 */
@Composable
private fun BarItem(label: String?, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Text(
            label ?: " ", style = ScType.button.copy(fontSize = 14.sp, fontWeight = FontWeight.Normal),
            color = Sc.colors.text,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// ------------------------------------------------------------------------------- the top row's cards

/** The cards are as wide as Quick tools' two tiles need; their tails rise this far above them. */
private val TopCardWidth = 288.dp
private val TailHeight = 12.dp

/**
 * A card the top row opens, hanging just under it: violet glass with a lavender edge, its tail
 * pointing up at the button, its name and a cross. At the screen's end under the Quick tools
 * square; at its start when [anchorX] (the button's middle, in px from the root's left) is given.
 * A touch anywhere else, the cross or Back closes it.
 */
@Composable
private fun TopCard(
    title: String,
    onClose: () -> Unit,
    anchorX: Float? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Sc.colors
    val density = LocalDensity.current
    var left by remember { mutableFloatStateOf(0f) }
    BackHandler(onBack = onClose)
    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .onGloballyPositioned { left = it.positionInRoot().x },
    ) {
        DismissLayer(onClose)
        // The tail's tip under the button: [anchorX] less the card's 12 dp margin, or the square's
        // middle, its 44 dp halved and the same margin in from the end.
        val fromStart = anchorX?.let { with(density) { (it - left).toDp() } - 12.dp }
        val shape = remember(fromStart) {
            PopoverShape(corner = 14.dp, tail = TailHeight, tailWidth = 16.dp,
                tipFromStart = fromStart, tipFromEnd = if (fromStart == null) 22.dp else null)
        }
        Box(
            Modifier.align(if (fromStart != null) Alignment.TopStart else Alignment.TopEnd)
                .padding(top = CardTop, start = 12.dp, end = 12.dp)
                .width((maxWidth - 24.dp).coerceAtMost(TopCardWidth)),
        ) {
            // The glow, round the card but not its tail.
            Box(
                Modifier.matchParentSize().padding(top = TailHeight)
                    .scGlow(c.accentGlow.copy(alpha = c.accentGlow.alpha * 0.35f), radius = 16.dp, cornerRadius = 14.dp),
            )
            Column(
                Modifier.fillMaxWidth().clip(shape).background(popoverFill(c)).border(1.dp, c.selectedBorder, shape)
                    .blockTouches()
                    .padding(start = 10.dp, end = 10.dp, top = TailHeight, bottom = 10.dp),
            ) {
                Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = ScType.headline.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
                        color = c.text, modifier = Modifier.weight(1f).padding(start = 6.dp),
                    )
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .clickable(role = Role.Button, onClick = onClose)
                            .semantics { contentDescription = "Close ${title.lowercase()}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(ScIcons.Close, null, Modifier.size(20.dp), tint = c.text)
                    }
                }
                content()
            }
        }
    }
}

/**
 * Quick tools (the owner's design, 5 Nov 2026): Find, night vision, the sky view and Settings, as
 * four tiles on a card hanging from the square that opens it. The switches leave it open.
 */
@Composable
private fun QuickTools(
    nightMode: Boolean,
    skyOn: Boolean,
    onFind: () -> Unit,
    onNight: () -> Unit,
    onSky: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
) {
    TopCard("Quick tools", onClose) {
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tile = Modifier.weight(1f).fillMaxHeight()
            ToolTile(ScDuo.Find, "Find", "Find in the sky: a galaxy, a planet, a star", tile, opens = true,
                onClick = onFind)
            ToolTile(ScDuo.Eye, "Night vision", if (nightMode) "Night vision on" else "Night vision off", tile,
                checked = nightMode, onClick = onNight)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tile = Modifier.weight(1f).fillMaxHeight()
            ToolTile(ScDuo.SkyView, "Sky view", if (skyOn) "Sky view on" else "Sky view", tile,
                checked = skyOn, onClick = onSky)
            ToolTile(ScDuo.Gear, "Settings", "Settings", tile, onClick = onSettings)
        }
    }
}

/**
 * Tonight, from the moon on the top row (the owner's idea, 5 Nov 2026, in place of the bar over
 * the viewfinder): the Moon, how long it stays dark, the sky's Bortle class and the best thing to
 * shoot, a line each, and the planner a tap further. Without a place it asks for one.
 */
@Composable
private fun TonightCard(t: TonightSummary?, anchorX: Float, onPlan: () -> Unit, onClose: () -> Unit) {
    val c = Sc.colors
    TopCard("Tonight", onClose, anchorX) {
        Column(Modifier.padding(horizontal = 6.dp)) {
            if (t != null) {
                TonightLine({ MoonGlyph(t.moon, t.southern, Modifier.size(20.dp)) },
                    "Moon ${(t.moon.illuminated * 100).roundToInt()}% lit")
                if (t.hasPlace) {
                    t.darkText?.let { TonightLine({ Icon(ScIcons.Clock, null, Modifier.size(19.dp), tint = c.accent) }, it) }
                    t.bortle?.let { TonightLine({ Icon(ScIcons.Map, null, Modifier.size(19.dp), tint = c.accent) }, "Your sky: Bortle $it") }
                    t.bestName?.let { name ->
                        TonightLine({ Icon(ScIcons.Sparkles, null, Modifier.size(19.dp), tint = c.accent) },
                            name, t.bestAtText?.let { "best at $it" })
                    }
                }
            }
        }
        ScButton(
            if (t?.hasPlace == false) "Set your place" else "Plan tonight", onPlan,
            Modifier.fillMaxWidth().padding(top = 8.dp), kind = ScButtonKind.Primary,
        )
    }
}

/** One of tonight's lines: an icon, a few words, and under them [sub] when there is one. */
@Composable
private fun TonightLine(icon: @Composable () -> Unit, text: String, sub: String? = null) {
    val c = Sc.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 38.dp).padding(vertical = if (sub != null) 4.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(text, style = ScType.body, color = c.text, maxLines = 2)
            if (sub != null) Text(sub, style = ScType.bodySmall, color = c.text2, maxLines = 1)
        }
    }
}

/**
 * One of Quick tools' tiles: the tool's icon, its name and, under the name, what a tap does: a
 * switch, or Open. Settings' chevron sits beside its name.
 */
@Composable
private fun ToolTile(
    icon: DuoIcon,
    title: String,
    description: String,
    modifier: Modifier,
    checked: Boolean? = null,
    opens: Boolean = false,
    onClick: () -> Unit,
) {
    val c = Sc.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.heightIn(min = 64.dp).clip(shape)
            .background(c.text.copy(alpha = 0.035f)).border(1.dp, c.hairlineStrong, shape)
            .then(
                if (checked != null) Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = { onClick() })
                else Modifier.clickable(role = Role.Button, onClick = onClick)
            )
            .semantics { contentDescription = description }
            .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScDuoIcon(icon, Modifier.size(22.dp))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = PillText, color = c.text, maxLines = 2, modifier = Modifier.weight(1f))
                if (checked == null && !opens) Icon(ScIcons.ChevronRight, null, Modifier.size(16.dp), tint = c.text2)
            }
            if (opens) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Open", style = PillText.copy(fontWeight = FontWeight.Normal), color = c.accentText)
                    Spacer(Modifier.width(3.dp))
                    Icon(ScIcons.ChevronRight, null, Modifier.size(14.dp), tint = c.accentText)
                }
            }
            if (checked != null) {
                Spacer(Modifier.height(2.dp))
                // The tile is the switch; this only shows it.
                ScSwitch(checked, null, Modifier.align(Alignment.End).height(30.dp))
            }
        }
    }
}

/**
 * A card with a small tail rising from its top edge, its tip [tipFromStart] in from the left (or
 * [tipFromEnd] from the right), its sides curving in to the tip as the design draws Quick tools'.
 * The card itself starts [tail] down.
 */
private class PopoverShape(
    private val corner: Dp,
    private val tail: Dp,
    private val tailWidth: Dp,
    private val tipFromStart: Dp?,
    private val tipFromEnd: Dp?,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { corner.toPx() }
        val t = with(density) { tail.toPx() }
        val half = with(density) { tailWidth.toPx() } / 2f
        val w = size.width
        val h = size.height
        // Kept off the rounded corners.
        val tip = with(density) { tipFromStart?.toPx() ?: (w - (tipFromEnd ?: 0.dp).toPx()) }
            .coerceIn(r + half, maxOf(r + half, w - r - half))
        val path = Path().apply {
            moveTo(r, t)
            lineTo(tip - half, t)
            cubicTo(tip - half * 0.4f, t, tip - half * 0.12f, t * 0.4f, tip, 0f)
            cubicTo(tip + half * 0.12f, t * 0.4f, tip + half * 0.4f, t, tip + half, t)
            lineTo(w - r, t)
            arcTo(Rect(w - 2 * r, t, w, t + 2 * r), -90f, 90f, false)
            lineTo(w, h - r)
            arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
            lineTo(r, h)
            arcTo(Rect(0f, h - 2 * r, 2 * r, h), 90f, 90f, false)
            lineTo(0f, t + r)
            arcTo(Rect(0f, t, 2 * r, t + 2 * r), 180f, 90f, false)
            close()
        }
        return Outline.Generic(path)
    }
}

/** Quick tools' and the session lengths' glass: the raised violet, lit a touch. */
private fun popoverFill(c: ScColors): Color = c.selectedBorder.copy(alpha = 0.06f).compositeOver(c.raised)

/** Under an open menu: the first touch anywhere else closes it, and goes no further. */
@Composable
private fun DismissLayer(onDismiss: () -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                dismiss()
            }
        },
    )
}

// ------------------------------------------------------------------------------- overlays

@Composable
private fun CameraProblemCard(
    message: String,
    noRaw: Boolean,
    cameraLost: Boolean,
    sessionRunning: Boolean,
    onReopen: (() -> Unit)?,
    onProbe: () -> Unit,
) {
    val c = Sc.colors
    // No full-screen scrim: the top row (the light map, Find, Quick tools with Settings) and the
    // gallery must stay reachable when the camera cannot come back - on a phone without a usable
    // RAW camera, it never will.
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        ScCard(Modifier.fillMaxWidth().blockTouches(), color = c.raised, borderColor = c.errorBorder) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(c.errorFill).border(1.dp, c.errorBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(ScIcons.Warning, null, Modifier.size(20.dp), tint = c.error) }
                Spacer(Modifier.width(12.dp))
                Text(if (noRaw) "No RAW camera" else "Camera problem", style = ScType.titleSmall, color = c.text)
            }
            Text(message, style = ScType.body, color = c.text2, modifier = Modifier.padding(top = 12.dp))
            if (noRaw) {
                Text("Tonight, Find and the light map still work.",
                    style = ScType.bodySmall, color = c.text3, modifier = Modifier.padding(top = 6.dp))
            }
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
    val dim = if (night) Color(0xFF4A100B) else Color(0xFF3A3448)
    val dimmer = if (night) Color(0xFF350B07) else Color(0xFF2A2536)
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
