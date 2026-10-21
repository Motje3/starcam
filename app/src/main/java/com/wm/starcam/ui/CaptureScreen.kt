package com.wm.starcam.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RawOn
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wm.starcam.StarCamViewModel
import com.wm.starcam.camera.AutoExposure
import com.wm.starcam.camera.Busy
import com.wm.starcam.camera.CamInfo
import com.wm.starcam.camera.CaptureState
import com.wm.starcam.camera.FocusSweep
import com.wm.starcam.session.FocusMode
import com.wm.starcam.session.Mode
import com.wm.starcam.session.Quality
import com.wm.starcam.ui.theme.WarnAmber
import com.wm.starcam.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Main screen, zoned like a modern camera app instead of floating everything over the preview:
 *
 *   [ quick bar ]      <- the dead space above a 4:3 preview on a 20:9 screen: most-used toggles
 *   [ status strip ]   <- gyro/mode/thermal/level + the app's reasoning lines
 *   [ preview ]        <- anchored under the strip, correct aspect
 *   [ mode + shutter ] <- lens/mode/quality chips and the big button, in the bottom dead space
 *
 * Session overlays still cover everything while working; in-progress controls draw above them.
 */
@Composable
fun CaptureScreen(
    vm: StarCamViewModel,
    onOpenPro: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenProbe: () -> Unit,
    onOpenSelfTest: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val settings by vm.settingsStore.settings.collectAsState()
    // The engine only exists once the camera has opened. Fallback flows are remembered
    // unconditionally and explicitly typed — `remember` inside an elvis branch is a conditional
    // composable call, and MutableStateFlow(null) alone would infer MutableStateFlow<Nothing?>.
    val idleState = remember { MutableStateFlow<CaptureState>(CaptureState.Idle) }
    val noDecision = remember { MutableStateFlow<AutoExposure.Decision?>(null) }
    val noFocus = remember { MutableStateFlow<FocusSweep.Result?>(null) }
    val noBusy = remember { MutableStateFlow<Busy?>(null) }
    val noCalibration = remember { MutableStateFlow<String?>(null) }
    val noSessionStart = remember { MutableStateFlow(0L) }
    val engine = vm.engine
    val state by (engine?.state ?: idleState).collectAsState()
    val cameraError by vm.cameraError.collectAsState()
    val ready by vm.ready.collectAsState()
    val isStill by vm.motion.isStill.collectAsState()
    val thermal by vm.thermal.status.collectAsState()
    val decision by (engine?.decision ?: noDecision).collectAsState()
    val focus by (engine?.focus ?: noFocus).collectAsState()
    val calibration by (engine?.calibration ?: noCalibration).collectAsState()
    val busy by (engine?.busy ?: noBusy).collectAsState()
    val sessionStart by (engine?.sessionStartedAtMs ?: noSessionStart).collectAsState()
    val lenses by vm.lenses.collectAsState()
    val rollDeg by vm.motion.rollDeg.collectAsState()
    val camInfo by vm.camInfo.collectAsState()
    val cameraLost by vm.cameraLost.collectAsState()

    var framingBoost by remember { mutableStateOf(false) }
    // Screen shield: pure-black cover for long sessions (battery + dark adaptation). Pure UI —
    // the capture session keeps running underneath; auto-disarmed outside the capture phases so
    // the next session never starts on a surprise black screen.
    var shieldOn by remember { mutableStateOf(false) }
    val shieldEligible =
        state is CaptureState.CapturingLights || state is CaptureState.CapturingDarks
    LaunchedEffect(shieldEligible) { if (!shieldEligible) shieldOn = false }

    // The shield used to drop FLAG_KEEP_SCREEN_ON so the display could sleep behind it. That
    // arrived in the same build as the field failures, and a screen going off mid-burst kills the
    // viewfinder output that is still part of the capture session, so it is a suspect. On an
    // OLED panel a black shield costs next to nothing to keep lit, so the screen now stays on
    // (MainActivity's flag) until the camera test has cleared the screen-off case. Pressing the
    // power button still works; the session's camera repair covers it.

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- Base layout: quick bar / status strip / preview / bottom controls ----
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
        ) {
            if (state is CaptureState.Idle && cameraError == null) {
                QuickBar(
                    vm = vm,
                    framingBoost = framingBoost,
                    onBoostToggle = {
                        framingBoost = !framingBoost
                        vm.setFramingBoost(framingBoost)
                    },
                    onOpenProbe = onOpenProbe,
                    onOpenSelfTest = onOpenSelfTest,
                    onOpenDiagnostics = onOpenDiagnostics,
                )
            }

            // ---- Status strip: only what is actionable right now. The mode is shown by the
            // chips, "max exposure" lives in the Pro panel, and the gyro dot appears in the
            // hold-still overlay — none of them earn permanent HUD space.
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Opt-in: matters only when a horizon is in frame (landscape astro).
                    if (state is CaptureState.Idle && settings.showLevel) {
                        // All four flat orientations count as level (0, ±90, 180).
                        val levelDev =
                            minOf(abs(rollDeg), abs(abs(rollDeg) - 90f), abs(abs(rollDeg) - 180f))
                        Text("⦿ %.1f°".format(levelDev),
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                            color = if (levelDev <= 0.7f) Color(0xFF6EDB8F) else WarnAmber)
                    }
                    // Thermal: silence is the normal state; speak only when it matters.
                    if (thermal >= 2) {
                        Text(vm.thermal.label(), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                            color = if (thermal >= 3) MaterialTheme.colorScheme.error else WarnAmber)
                    }
                    if (framingBoost) {
                        Text("night preview ~8 fps — eye icon turns it off",
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = WarnAmber)
                    }
                }
                // The app's reasoning matters while it is working, not as a leftover afterwards.
                if (state !is CaptureState.Idle) {
                    // One clock that never stops for as long as a session runs. Everything else
                    // on screen can legitimately sit still for 30 s; this cannot, so a frozen app
                    // is now distinguishable from a working one at a glance.
                    SessionClock(sessionStart, Modifier.padding(top = 3.dp))
                    decision?.let {
                        Text(it.reasoning, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 3.dp))
                    }
                    focus?.let {
                        Text(it.message, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = if (it.ok) Color(0xFF6EDB8F) else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                    // Reused calibration must never be silent: an old dark set or a missing
                    // vignetting map changes the result, so it is stated rather than assumed.
                    calibration?.let {
                        Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }

            // ---- Viewfinder: fixed to a camera-supported size so it is never stretched. ----
            // weight(1f) makes the PREVIEW the element that yields when space is tight: the
            // bottom controls are measured first, so the shutter can never be pushed off-screen
            // (which is exactly what happened when ASTRO's quality row appeared).
            Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.TopCenter,
            ) {
                CameraPreview(vm)
            }

            // ---- Idle bottom controls (in-progress controls draw above the overlays instead) ----
            if (state is CaptureState.Idle) {
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Lens chips: only with 2+ RAW-capable back cameras. Labels are the focal
                    // ratio vs the main lens (0.3x ultrawide, 1x main).
                    if (lenses.size >= 2) {
                        val mainFocal = lenses.first().focalLengthMm
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(bottom = 6.dp),
                        ) {
                            lenses.forEachIndexed { idx, lens ->
                                val label = when {
                                    idx == 0 -> "1x"
                                    mainFocal > 0f && lens.focalLengthMm > 0f ->
                                        "%.1fx".format(lens.focalLengthMm / mainFocal)
                                    else -> "cam ${lens.cameraId}"
                                }
                                FilterChip(
                                    selected = camInfo?.cameraId == lens.cameraId,
                                    onClick = { vm.switchLens(lens.cameraId) },
                                    label = { Text(label, fontSize = 12.sp) },
                                )
                            }
                        }
                        WiderLensHint(lenses, camInfo)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Mode.entries.forEach { m ->
                            FilterChip(
                                selected = settings.mode == m,
                                onClick = { vm.settingsStore.update { it.copy(mode = m) } },
                                label = { Text(m.name, fontSize = 12.sp) },
                            )
                        }
                    }
                    if (settings.mode == Mode.ASTRO) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            Quality.entries.forEach { q ->
                                FilterChip(
                                    selected = settings.quality == q,
                                    onClick = { vm.settingsStore.update { it.copy(quality = q) } },
                                    label = { Text(q.name, fontSize = 12.sp) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onOpenPro) {
                            Icon(Icons.Filled.Tune, "Pro settings", tint = Color.White)
                        }
                        Spacer(Modifier.width(18.dp))
                        BigShutterButton(
                            enabled = ready && cameraError == null,
                            label = when (settings.mode) {
                                Mode.ASTRO -> "ASTRO"
                                Mode.TRAILS -> "TRAILS"
                                Mode.METEOR -> "METEOR"
                                Mode.PRO -> "SHOOT"
                            },
                            onClick = { vm.startSession() },
                        )
                        Spacer(Modifier.width(18.dp))
                        IconButton(onClick = onOpenSessions) {
                            Icon(Icons.Filled.PhotoLibrary, "Sessions", tint = Color.White)
                        }
                    }
                }
            }
        }

        // ---- Session overlays cover the viewfinder while working ----
        when (val st = state) {
            is CaptureState.WaitingStill -> CenterCard {
                Text("Hold still", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (st.delayRemaining > 0) "starting in ${st.delayRemaining}s"
                    else if (st.waitingForGyro) "waiting for tripod stillness…" else "…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (st.waitingForGyro) {
                    Spacer(Modifier.height(8.dp))
                    GyroDot(isStill)
                    // The number says *why* it is still waiting: wind, a soft tripod, a passing
                    // truck. Without it a long wait looks like the app hanging.
                    GyroReadout(vm)
                }
                BusyRow(busy)
            }
            is CaptureState.Probing -> CenterCard {
                Text("Reading the sky", style = MaterialTheme.typography.titleLarge)
                Text("one exposure to pick ISO and shutter", fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                BusyRow(busy)
            }
            is CaptureState.Focusing -> CenterCard {
                CircularProgressIndicator(progress = { st.step.toFloat() / st.total })
                Text("Star autofocus ${st.step}/${st.total}", Modifier.padding(top = 12.dp))
                Text(st.label, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                BusyRow(busy)
            }
            is CaptureState.CapturingLights -> CaptureOverlay(vm, st, busy)
            is CaptureState.AwaitingDarkCover -> CenterCard {
                Text("Dark frames", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Cover the lens completely (cap / dark cloth), then confirm.\nSkipping in ${st.secondsLeft}s",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { vm.engine?.confirmDarksReady() }) { Text("Lens covered") }
                    OutlinedButton(onClick = { vm.engine?.skipDarks() }) { Text("Skip darks") }
                }
            }
            is CaptureState.CapturingDarks -> CenterCard {
                CircularProgressIndicator(progress = { st.current.toFloat() / st.total })
                Text("Dark frame ${st.current}/${st.total}", Modifier.padding(top = 12.dp))
                BusyRow(busy)
            }
            is CaptureState.Stacking -> CenterCard {
                Text("Stacking on device", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { st.percent / 100f },
                    modifier = Modifier.fillMaxWidth(0.7f),
                )
                Text("${st.stage} · ${st.percent}%", Modifier.padding(top = 8.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                BusyRow(busy)
                Spacer(Modifier.height(10.dp))
                LiveStackImage(vm, refreshKey = st.percent)
            }
            is CaptureState.Done -> DoneCard(vm, st)
            is CaptureState.Error -> CenterCard {
                Text("Something went wrong", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error)
                Text(st.message, Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
                st.sessionDir?.let {
                    Text(
                        (if (st.framesCaptured > 0) "${st.framesCaptured} frame(s) are safe in\n"
                        else "Session folder:\n") + it.name,
                        Modifier.padding(top = 8.dp), fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // The full story is already on disk; this is how it leaves the field.
                Text(
                    "Everything that happened is in the log. Share it, or bring the phone home " +
                        "and plug it in.",
                    Modifier.padding(top = 8.dp), fontSize = 11.sp, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShareDebugButton(vm, outlined = true)
                    Button(onClick = { vm.engine?.dismissResult() }) { Text("OK") }
                }
            }
            CaptureState.Idle -> Unit
        }

        // ---- Camera unavailable ----
        // The engine's own Error card names the session folder, so once a failed session has
        // produced one it wins; this card would otherwise cover it.
        if (state !is CaptureState.Error) {
            cameraError?.let {
                CenterCard {
                    Text("Camera problem", style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error)
                    Text(it, Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
                    if (cameraLost) {
                        Text(
                            "Frames already captured are safe on disk.",
                            Modifier.padding(top = 6.dp), fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val sessionRunning = state !is CaptureState.Idle && state !is CaptureState.Done
                    if (sessionRunning) {
                        // Reopening from here would tear the camera down under the session; the
                        // session's own repair reopens it, or stacks what it has if it cannot.
                        Text(
                            "The session is trying to bring the camera back by itself.",
                            Modifier.padding(top = 6.dp), fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Losing the camera is recoverable; without this the only way back was
                        // to kill the app.
                        if (camInfo != null && !sessionRunning) {
                            Button(onClick = { vm.reopenCamera() }) { Text("Reopen camera") }
                        }
                        OutlinedButton(onClick = onOpenProbe) { Text("Probe") }
                    }
                }
            }
        }

        // ---- In-progress controls: above the overlays so Cancel is always reachable ----
        if (state !is CaptureState.Idle && state !is CaptureState.Done &&
            state !is CaptureState.Error
        ) {
            Row(
                Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { vm.engine?.cancel() }) { Text("Cancel") }
                if (state is CaptureState.CapturingLights) {
                    Button(onClick = { vm.engine?.finishEarly() }) { Text("Finish & stack now") }
                }
                if (shieldEligible) {
                    IconButton(onClick = { shieldOn = !shieldOn }) {
                        Icon(Icons.Filled.DarkMode, "Screen shield", tint = Color.White)
                    }
                }
            }
        }

        // ---- Screen shield: last child so it draws (and hit-tests) above everything ----
        if (shieldOn && shieldEligible) {
            val (cur, tot) = when (val st = state) {
                is CaptureState.CapturingLights -> st.current to st.total
                is CaptureState.CapturingDarks -> st.current to st.total
                else -> 0 to 0
            }
            Box(
                Modifier.fillMaxSize().background(Color.Black)
                    // Swallows every touch so nothing underneath can be pressed by accident.
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { shieldOn = false }) },
                contentAlignment = Alignment.Center,
            ) {
                // Dim, but still ticking: the shield is on for the longest stretches of a
                // session, exactly when "is it still alive?" is hardest to answer.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "frame $cur/$tot",
                        color = Color(0xFF3A3A3A), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                    )
                    ShieldClock(sessionStart)
                    Text(
                        "double-tap to show the screen",
                        color = Color(0xFF2A2A2A), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- quick bar

/**
 * Most-used settings as one-tap toggles in the dead space above the preview, iPhone/Pixel style.
 * The chevron reveals a second row of less-frequent toggles plus the diagnostics screens.
 */
@Composable
private fun QuickBar(
    vm: StarCamViewModel,
    framingBoost: Boolean,
    onBoostToggle: () -> Unit,
    onOpenProbe: () -> Unit,
    onOpenSelfTest: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val s by vm.settingsStore.settings.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            QuickToggle(
                Icons.Filled.Timer, "${s.startDelaySec}s", s.startDelaySec > 0,
            ) {
                val next = when (s.startDelaySec) { 0 -> 3; 3 -> 10; else -> 0 }
                vm.settingsStore.update { it.copy(startDelaySec = next) }
            }
            QuickToggle(
                if (s.focusMode == FocusMode.AUTO_STAR) Icons.Filled.CenterFocusStrong
                else Icons.Filled.CenterFocusWeak,
                when (s.focusMode) {
                    FocusMode.AUTO_STAR -> "stars"
                    FocusMode.INFINITY -> "infinity"
                    FocusMode.MANUAL -> "manual"
                },
                s.focusMode == FocusMode.AUTO_STAR,
            ) {
                val next = FocusMode.entries[(s.focusMode.ordinal + 1) % FocusMode.entries.size]
                vm.settingsStore.update { it.copy(focusMode = next) }
            }
            QuickToggle(Icons.Filled.Tonality, "darks", s.darksEnabled) {
                vm.settingsStore.update { it.copy(darksEnabled = !it.darksEnabled) }
            }
            QuickToggle(Icons.Filled.RawOn, "DNG", s.saveDngs) {
                vm.settingsStore.update { it.copy(saveDngs = !it.saveDngs) }
            }
            QuickToggle(Icons.Filled.Visibility, "boost", framingBoost, onBoostToggle)
            QuickToggle(Icons.Filled.Bedtime, "night", s.nightMode) {
                vm.settingsStore.update { it.copy(nightMode = !it.nightMode) }
            }
            QuickToggle(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                "more", expanded,
            ) { expanded = !expanded }
        }
        if (expanded) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                QuickToggle(Icons.Filled.Layers, "live", s.livePreview) {
                    vm.settingsStore.update { it.copy(livePreview = !it.livePreview) }
                }
                QuickToggle(Icons.Filled.PhoneAndroid, "stack", s.stackOnDevice) {
                    vm.settingsStore.update { it.copy(stackOnDevice = !it.stackOnDevice) }
                }
                QuickToggle(Icons.Filled.Archive, "cache", s.keepRawCache) {
                    vm.settingsStore.update { it.copy(keepRawCache = !it.keepRawCache) }
                }
                QuickToggle(Icons.Filled.History, "re-focus", s.reuseLastFocus) {
                    vm.settingsStore.update { it.copy(reuseLastFocus = !it.reuseLastFocus) }
                }
                QuickToggle(Icons.Filled.Info, "probe", false, onOpenProbe)
                QuickToggle(Icons.Filled.BugReport, "test", false, onOpenSelfTest)
                QuickToggle(Icons.Filled.Description, "logs", false, onOpenDiagnostics)
            }
        }
    }
}

/** One icon + tiny label; active = accent tint. Sized for gloves in the dark. */
@Composable
private fun QuickToggle(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.75f)
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, Modifier.size(22.dp), tint = tint)
        Text(
            label, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = tint,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

// ---------------------------------------------------------------- overlays & pieces

@Composable
private fun CaptureOverlay(vm: StarCamViewModel, st: CaptureState.CapturingLights, busy: Busy?) {
    Column(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Frame ${st.current} / ${st.total}", style = MaterialTheme.typography.titleLarge)
        RemainingTime(st)
        Text(
            "${Fmt.exposure(st.exposureNs)} · ISO ${st.iso}" +
                if (st.rejectedSoFar > 0) " · ${st.rejectedSoFar} bumped" else "",
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (st.cappedByStorage) {
            Text(
                "frame count reduced — storage low",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = WarnAmber, modifier = Modifier.padding(top = 2.dp),
            )
        }
        st.lastStats?.let { s ->
            Text(
                "stars ${s.starCount} · FWHM %.2f px · drift %.1f,%.1f px".format(s.medianFwhm, s.dx, s.dy),
                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                color = if (s.aligned) Color(0xFF6EDB8F) else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { st.current.toFloat() / st.total },
            modifier = Modifier.fillMaxWidth(0.7f),
        )
        BusyRow(busy)
        Spacer(Modifier.height(16.dp))
        LiveStackImage(vm, refreshKey = st.current)
        Text("live stack", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Live-stack preview: refreshed whenever [refreshKey] changes. */
@Composable
private fun LiveStackImage(vm: StarCamViewModel, refreshKey: Int) {
    var image by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    // One reusable buffer: a fresh 2.7 MB bitmap per frame tick would churn the heap all session.
    val buffer = remember { Bitmap.createBitmap(960, 720, Bitmap.Config.ARGB_8888) }
    LaunchedEffect(refreshKey) {
        withContext(Dispatchers.Default) {
            if (vm.engine?.fillPreview(buffer) == true) {
                // Copy so Compose never reads the buffer while the next fill is writing it.
                image = buffer.copy(Bitmap.Config.ARGB_8888, false)?.asImageBitmap()
            }
        }
    }
    image?.let {
        Image(
            it, "live stack preview",
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp)),
            contentScale = ContentScale.FillWidth,
        )
    }
}

@Composable
private fun DoneCard(vm: StarCamViewModel, st: CaptureState.Done) {
    Column(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f))
            .verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        st.warning?.let {
            Text(
                it, fontSize = 12.sp, color = WarnAmber, textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        if (st.result == null) {
            Text("RAWs saved", style = MaterialTheme.typography.titleLarge)
            Text("${st.framesCaptured} frames in ${st.sessionDir.name}\nStack later from Sessions, or copy to PC.",
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            SessionTotal(st.elapsedMs)
        } else {
            var image by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
            LaunchedEffect(st.jpegFile) {
                withContext(Dispatchers.IO) {
                    st.jpegFile?.let { f ->
                        // Downsample: a full-res stack is up to 50 MP, and decoding that straight
                        // to ARGB_8888 for a phone-width preview is ~200 MB in one allocation.
                        image = decodeDownsampled(f.absolutePath, 1440)?.asImageBitmap()
                    }
                }
            }
            image?.let {
                Image(it, "final image", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            }
            Text(
                "Stacked ${st.result.framesUsed}/${st.result.framesTotal} frames · " +
                    "FWHM %.1f→%.1f px · %.0fs processing".format(
                        st.result.fwhmRef, st.result.fwhmStack, st.result.elapsedMs / 1000.0),
                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp), textAlign = TextAlign.Center,
            )
            if (st.result.rejected.isNotEmpty()) {
                Text("rejected: ${st.result.rejected}", fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
            }
            if (st.result.meteorCount > 0) {
                Text(
                    "${st.result.meteorCount} meteor candidate(s) — frames ${st.result.meteorFrames}",
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp), textAlign = TextAlign.Center,
                )
            }
            if (st.result.foreground.isNotEmpty()) {
                Text(
                    "foreground blend: ${st.result.foreground}",
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                if (st.galleryUri != null) "Saved to gallery ✓ (and to the session folder)"
                else "Saved to session folder (gallery insert failed)",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            SessionTotal(st.elapsedMs)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { vm.engine?.dismissResult() }) { Text("Done") }
    }
}

/**
 * A suggestion, never a switch: when another lens tolerates a meaningfully longer sub-exposure
 * than the one selected, say so and say why.
 *
 * The NPF limit falls with focal length, so an ultrawide can hold stars point-like for several
 * times as long as the main lens — which is the whole game for a wide Milky Way frame, where
 * total light per frame matters more than reach. Shown only when the difference is worth acting
 * on (1.5x or better), and only ever as text.
 */
@Composable
private fun WiderLensHint(lenses: List<CamInfo>, current: CamInfo?) {
    if (current == null || lenses.size < 2) return
    val currentLimit = current.trailLimitSeconds()
    // The widest lens is the one with the shortest focal length that still reports one.
    val widest = lenses.filter { it.focalLengthMm > 0f }.minByOrNull { it.focalLengthMm } ?: return
    if (widest.cameraId == current.cameraId) return
    val widestLimit = widest.trailLimitSeconds()
    if (widestLimit < currentLimit * 1.5) return
    val mainFocal = lenses.first().focalLengthMm
    val label =
        if (mainFocal > 0f) "%.1fx".format(widest.focalLengthMm / mainFocal)
        else "cam ${widest.cameraId}"
    Text(
        "$label holds stars for ${widestLimit.toInt()}s vs ${currentLimit.toInt()}s — " +
            "more light per frame for wide Milky Way shots",
        fontSize = 10.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp, start = 12.dp, end = 12.dp),
    )
}

/** Decodes [path] scaled down so its longest edge is roughly [maxEdge] px. */
private fun decodeDownsampled(path: String, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull()
}

@Composable
private fun CenterCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)), Alignment.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}

// ---------------------------------------------------------------- live clocks
//
// Everything below exists for one reason: a capture phase can hold a single CaptureState for a
// 30 s exposure plus a native ingest, and a screen that shows nothing moving for 35 s is
// indistinguishable from a crash — which is how it read on the first night out.

/**
 * Elapsed milliseconds since [sinceMs], ticking ~5× a second.
 *
 * Deliberately called from leaf composables only: the ticker invalidates whatever composable
 * reads it, so keeping it in a Text-sized scope means the overlay around it is not rebuilt five
 * times a second for a whole session.
 */
@Composable
private fun rememberElapsedMs(sinceMs: Long): Long {
    var now by remember(sinceMs) { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(sinceMs) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(200)
        }
    }
    return (now - sinceMs).coerceAtLeast(0L)
}

/**
 * What the engine is doing right now, with a clock that always moves.
 *
 * A step with a known cost (an exposure, a cooldown) counts down inside the ring. A step of
 * unknown cost (a native ingest, a JPEG encode) spins and counts up. When a known step overruns,
 * the ring goes indeterminate and says so, rather than sitting on a full circle pretending to be
 * finished — an honest "still running" beats a progress bar that lies.
 */
@Composable
private fun BusyRow(busy: Busy?, modifier: Modifier = Modifier) {
    if (busy == null) return
    val elapsedMs = rememberElapsedMs(busy.startedAtMs)
    val expected = busy.expectedMs
    val overrun = if (expected > 0L) elapsedMs > expected + 3_000L else elapsedMs > 25_000L
    Row(
        modifier.padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (expected > 0L && !overrun) {
                CircularProgressIndicator(
                    progress = { (elapsedMs.toFloat() / expected).coerceIn(0f, 1f) },
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp,
                )
                Text(
                    "${(expected - elapsedMs + 999L) / 1000L}",
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
            }
        }
        Column {
            Text(busy.label, fontSize = 13.sp)
            val timing = buildString {
                append(Fmt.clock((elapsedMs / 1000L).toInt()))
                if (expected > 0L) append(" / ").append(Fmt.clock((expected / 1000L).toInt()))
                if (overrun) append(" · slower than usual, still running")
            }
            Text(
                timing, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = if (overrun) WarnAmber else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Wall-clock length of the running session. */
@Composable
private fun SessionClock(startedAtMs: Long, modifier: Modifier = Modifier) {
    if (startedAtMs == 0L) return
    val elapsed = rememberElapsedMs(startedAtMs)
    Text(
        "session ${Fmt.clock((elapsed / 1000L).toInt())}",
        modifier, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The same clock, dimmed to shield brightness. */
@Composable
private fun ShieldClock(startedAtMs: Long) {
    if (startedAtMs == 0L) return
    val elapsed = rememberElapsedMs(startedAtMs)
    Text(
        Fmt.clock((elapsed / 1000L).toInt()),
        color = Color(0xFF3A3A3A), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
    )
}

/** How long the finished session took, end to end. */
@Composable
private fun SessionTotal(elapsedMs: Long) {
    if (elapsedMs <= 0L) return
    Text(
        "took ${Fmt.seconds((elapsedMs / 1000L).toInt())}",
        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * Time left in the burst, ticking down in real time.
 *
 * The old line was computed once per frame from the frame counter, so it sat on the same number
 * for the whole exposure — the single most convincing "this app is stuck" signal on the screen.
 * Still approximate: reshoots and thermal pauses stretch it, hence the "~".
 */
@Composable
private fun RemainingTime(st: CaptureState.CapturingLights) {
    val perFrameSec = st.exposureNs / 1e9 + st.interFrameGapMs / 1000.0
    val inFrameMs = rememberElapsedMs(st.frameStartedAtMs)
    val thisFrameLeft =
        if (st.frameStartedAtMs == 0L) perFrameSec
        else (perFrameSec - inFrameMs / 1000.0).coerceIn(0.0, perFrameSec)
    val remainingSec = (st.total - st.current) * perFrameSec + thisFrameLeft
    Text(
        "~" + Fmt.seconds(remainingSec.toInt()) + " left",
        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Live gyro magnitude against the stillness threshold, sampled at 5 Hz — the sensor itself
 * delivers ~50 Hz, which is a lot of recomposition for a number nobody can read that fast.
 */
@Composable
private fun GyroReadout(vm: StarCamViewModel) {
    var mag by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            mag = vm.motion.magnitude.value
            delay(200)
        }
    }
    val threshold = vm.motion.stillThreshold
    Text(
        "gyro %.4f (needs < %.4f rad/s)".format(mag, threshold),
        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        modifier = Modifier.padding(top = 6.dp),
        color = if (mag <= threshold) Color(0xFF6EDB8F) else WarnAmber,
    )
}

@Composable
private fun GyroDot(still: Boolean) {
    Box(
        Modifier.size(10.dp).background(
            if (still) Color(0xFF6EDB8F) else Color(0xFFFFC66E), CircleShape
        )
    )
}

@Composable
private fun BigShutterButton(enabled: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(84.dp)
            .border(3.dp, if (enabled) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
            .padding(6.dp)
            .background(
                if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                else Color.DarkGray.copy(alpha = 0.4f),
                CircleShape,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}
