package com.wm.starcam.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wm.starcam.StarCamViewModel
import com.wm.starcam.camera.AutoExposure
import com.wm.starcam.camera.Busy
import com.wm.starcam.camera.CaptureEngine
import com.wm.starcam.camera.CaptureState
import com.wm.starcam.camera.FocusSweep
import com.wm.starcam.camera.StopRequest
import com.wm.starcam.engine.FrameStats
import com.wm.starcam.session.AppSettings
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.components.ScCard
import com.wm.starcam.ui.components.ScConfirmDialog
import com.wm.starcam.ui.components.ScGlass
import com.wm.starcam.ui.components.ScIconButton
import com.wm.starcam.ui.components.ScLabel
import com.wm.starcam.ui.components.ScNotice
import com.wm.starcam.ui.components.ScPipeline
import com.wm.starcam.ui.components.ScRing
import com.wm.starcam.ui.components.ScSpinner
import com.wm.starcam.ui.components.ScStatTile
import com.wm.starcam.ui.components.ScStatusPill
import com.wm.starcam.ui.components.SessionStep
import com.wm.starcam.ui.components.TapeCell
import com.wm.starcam.ui.components.Tone
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScShapes
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.ui.theme.ScreenMargin
import com.wm.starcam.ui.theme.scImageFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

/*
 * The session, one full screen per step. Everything here only reads the engine's state and calls
 * its five verbs (cancel, finishEarly, confirmDarksReady, skipDarks, dismissResult).
 *
 * The rule throughout: something always moves. A step can sit on one CaptureState for a 30 s
 * exposure plus a native ingest, so every screen carries a clock driven by the engine's Busy
 * label - counting down when the step's length is known, up when it is not, and saying so when
 * it runs long instead of sitting on a full ring.
 */

@Composable
fun SessionFlow(
    vm: StarCamViewModel,
    engine: CaptureEngine?,
    state: CaptureState,
    settings: AppSettings,
    onShield: () -> Unit,
    onOpenSession: (File) -> Unit,
) {
    val busy by engine?.busy.collectOr(null)
    val decision by engine?.decision.collectOr(null)
    val focus by engine?.focus.collectOr(null)
    val calibration by engine?.calibration.collectOr(null)
    val sessionStart by engine?.sessionStartedAtMs.collectOr(0L)
    val pending by engine?.stopRequest.collectOr(null)
    var confirmStop by remember { mutableStateOf(false) }
    val cancelNow = { engine?.cancel(); Unit }
    val askStop = { confirmStop = true }

    Box(Modifier.fillMaxSize().background(Sc.colors.background).blockTouches()) {
        when (state) {
            is CaptureState.WaitingStill -> HoldStillStep(vm, state, busy, settings, pending, cancelNow)
            CaptureState.Probing -> ReadingSkyStep(vm, busy, settings, pending, cancelNow)
            is CaptureState.Focusing -> StarFocusStep(state, busy, pending, cancelNow)
            is CaptureState.CapturingLights -> CapturingStep(
                vm, state, busy, decision, focus, calibration, sessionStart, settings, pending,
                onCancel = askStop, onFinish = { engine?.finishEarly() }, onShield = onShield,
            )
            is CaptureState.AwaitingDarkCover -> CoverLensStep(
                state, settings, pending,
                onCovered = { engine?.confirmDarksReady() },
                onSkip = { engine?.skipDarks() },
                onCancel = askStop,
            )
            is CaptureState.CapturingDarks -> DarksStep(state, busy, pending, onCancel = askStop, onShield = onShield)
            is CaptureState.Stacking -> StackingStep(vm, state, busy, settings, pending, onStop = askStop)
            is CaptureState.Done -> DoneStep(
                state, decision,
                onClose = { engine?.dismissResult() },
                onOpenSession = onOpenSession,
            )
            is CaptureState.Error -> ErrorStep(
                vm, state,
                onOk = { engine?.dismissResult() },
                onOpenSession = onOpenSession,
            )
            CaptureState.Idle -> Unit
        }
    }

    // Back closes a result, and otherwise does what the step's own Cancel or Stop does. Left to
    // the system it sent the app to the background mid-session and closed nothing.
    BackHandler {
        when (state) {
            is CaptureState.Done, is CaptureState.Error -> engine?.dismissResult()
            is CaptureState.WaitingStill, CaptureState.Probing, is CaptureState.Focusing -> cancelNow()
            CaptureState.Idle -> Unit
            else -> if (pending != StopRequest.Cancel) askStop()
        }
    }

    // A session that ends while the question is open must not be "stopped" afterwards: cancel()
    // on a finished session dismisses its result.
    val cancellable = state is CaptureState.CapturingLights || state is CaptureState.AwaitingDarkCover ||
        state is CaptureState.CapturingDarks || state is CaptureState.Stacking
    LaunchedEffect(cancellable) { if (!cancellable) confirmStop = false }
    if (confirmStop && cancellable && pending != StopRequest.Cancel) {
        ScConfirmDialog(
            title = if (state is CaptureState.Stacking) "Stop stacking?" else "Stop this capture?",
            text = when (state) {
                is CaptureState.Stacking ->
                    "The frames stay saved in Sessions. If working files are kept (the default), " +
                        "you can stack them there later."
                is CaptureState.AwaitingDarkCover ->
                    "The light frames stay saved in Sessions, but no photo is made now. To get the " +
                        "photo without dark frames, use Skip dark frames instead."
                is CaptureState.CapturingDarks ->
                    "The light frames stay saved in Sessions, but no photo is made now."
                else ->
                    "Frames already taken stay saved in Sessions, but no photo is made now. To get " +
                        "a photo from them, use Finish & stack now instead."
            },
            confirmText = "Stop",
            dismissText = "Keep going",
            danger = true,
            onConfirm = {
                confirmStop = false
                engine?.cancel()
            },
            onDismiss = { confirmStop = false },
        )
    }
}

// ------------------------------------------------------------------------------- scaffold

/**
 * Step header (the seven-stage strip), a content area that scrolls when a small screen needs it,
 * and the step's buttons pinned to the bottom. [centered] centres short content vertically.
 */
@Composable
private fun StepScaffold(
    step: SessionStep,
    progress: Float,
    centered: Boolean = false,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Spacer(Modifier.height(12.dp))
        ScPipeline(step, progress)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val minH = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = minH)
                        .padding(horizontal = ScreenMargin, vertical = 14.dp),
                    verticalArrangement = if (centered) Arrangement.spacedBy(14.dp, Alignment.CenterVertically)
                    else Arrangement.spacedBy(12.dp),
                    horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
                    content = content,
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = ScreenMargin, end = ScreenMargin, bottom = 12.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = actions,
        )
    }
}

@Composable
private fun ActionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A step's Cancel or Stop. Once pressed it says so, until the session gets there. */
@Composable
private fun StopButton(
    label: String,
    pending: StopRequest?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
) {
    val stopping = pending == StopRequest.Cancel
    ScButton(if (stopping) "Stopping…" else label, onClick, modifier, enabled = !stopping, height = height)
}

/**
 * What the engine is doing right now, with a clock that always moves: a known-length step counts
 * down; an unknown one spins and counts up; a known step that overruns says so rather than
 * pretending to be finished.
 */
@Composable
private fun BusyLine(busy: Busy?, modifier: Modifier = Modifier) {
    if (busy == null) return
    val c = Sc.colors
    val elapsed = rememberElapsedMs(busy.startedAtMs)
    val expected = busy.expectedMs
    val overrun = if (expected > 0L) elapsed > expected + 3_000L else elapsed > 25_000L
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (expected > 0L && !overrun) {
                ScRing((elapsed.toFloat() / expected).coerceIn(0f, 1f), Modifier.size(18.dp),
                    strokeWidth = 2.5.dp, glow = false, animate = false)
            } else {
                ScSpinner(size = 18.dp, color = if (overrun) c.warning else c.accent)
            }
            Spacer(Modifier.width(10.dp))
            Text(busy.label.replaceFirstChar { it.uppercase() }, style = ScType.bodySmall, color = c.text2,
                modifier = Modifier.weight(1f), maxLines = 1)
            Text(
                durationClock(elapsed / 1000L) +
                    if (expected > 0L) " / " + durationClock((expected + 999L) / 1000L) else "",
                style = ScType.data, color = if (overrun) c.warning else c.text3,
            )
        }
        if (overrun) {
            Text("Slower than usual — still running", style = ScType.caption, color = c.warning,
                modifier = Modifier.padding(start = 28.dp, top = 2.dp))
        }
    }
}

/** A ring driven by the Busy clock: fills over the step's expected length, spins when unknown. */
@Composable
private fun BusyRing(
    busy: Busy?,
    size: Dp,
    stroke: Dp = 7.dp,
    content: @Composable () -> Unit,
) {
    val c = Sc.colors
    val elapsed = if (busy != null) rememberElapsedMs(busy.startedAtMs) else 0L
    val expected = busy?.expectedMs ?: 0L
    val known = expected > 0L && elapsed <= expected + 3_000L
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (known) {
            ScRing((elapsed.toFloat() / expected).coerceIn(0f, 1f), Modifier.fillMaxSize(), strokeWidth = stroke,
                animate = false)
        } else {
            ScSpinner(Modifier.padding(stroke), size = size - stroke * 2, strokeWidth = stroke, color = c.accent)
        }
        content()
    }
}

@Composable
private fun StepTitle(title: String, body: String?, centered: Boolean = false) {
    val c = Sc.colors
    Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Text(title, style = ScType.title, color = c.text, textAlign = if (centered) TextAlign.Center else TextAlign.Start)
        if (body != null) {
            Text(body, style = ScType.body, color = c.text2,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ------------------------------------------------------------------------------- hold still

@Composable
private fun HoldStillStep(
    vm: StarCamViewModel,
    st: CaptureState.WaitingStill,
    busy: Busy?,
    s: AppSettings,
    pending: StopRequest?,
    onCancel: () -> Unit,
) {
    val c = Sc.colors
    val progress = if (st.delayRemaining > 0 && s.startDelaySec > 0)
        (s.startDelaySec - st.delayRemaining).toFloat() / s.startDelaySec else 0.6f
    StepScaffold(
        SessionStep.Still, progress, centered = true,
        actions = { StopButton("Cancel", pending, onCancel, Modifier.fillMaxWidth()) },
    ) {
        BusyRing(busy, 188.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (st.delayRemaining > 0) {
                    Text("Starting in", style = ScType.bodySmall, color = c.text2)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${st.delayRemaining}", style = ScType.countdown, color = c.text)
                        Text("s", style = ScType.stat, color = c.text3, modifier = Modifier.padding(bottom = 12.dp))
                    }
                } else {
                    Icon(ScIcons.Tripod, null, Modifier.size(40.dp), tint = c.accent)
                    Text(if (st.waitingForGyro) "Settling" else "Getting ready", style = ScType.headline,
                        color = c.text, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        StepTitle(
            "Hold still",
            "Waiting for the tripod to settle. Hands off — it starts by itself.",
            centered = true,
        )
        StillnessCard(vm)
        BusyLine(busy)
    }
}

/**
 * The gyroscope, live: the last six seconds on a log scale against the stillness threshold. Says
 * *why* it is still waiting - wind, a soft tripod, a passing truck - where a bare spinner would
 * look like the app hanging.
 */
@Composable
private fun StillnessCard(vm: StarCamViewModel) {
    val c = Sc.colors
    val samples = remember { mutableStateListOf<Float>() }
    var mag by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            val m = vm.motion.magnitude.value
            mag = m
            samples.add(m)
            if (samples.size > 60) samples.removeAt(0)
            delay(100)
        }
    }
    val still by vm.motion.isStill.collectAsState()
    val threshold = vm.motion.stillThreshold
    ScCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScLabel("Stillness")
            Spacer(Modifier.weight(1f))
            Text(if (still) "Steady" else "Moving", style = ScType.headline,
                color = if (still) c.accentText else c.warning)
            Text(String.format(Locale.US, " · %.4f rad/s", mag), style = ScType.data, color = c.text2)
        }
        val lineColor = c.accent
        val warnColor = c.warning
        val guide = c.text3
        Canvas(Modifier.fillMaxWidth().height(64.dp).padding(top = 10.dp)) {
            val lo = 0.0005f
            val hi = 0.5f
            val span = ln(hi / lo)
            fun y(v: Float): Float = size.height * (1f - (ln(v.coerceIn(lo, hi) / lo) / span))
            val ty = y(threshold)
            drawLine(guide, Offset(0f, ty), Offset(size.width, ty), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            if (samples.size >= 2) {
                val step = size.width / 59f
                val x0 = size.width - (samples.size - 1) * step
                val path = Path()
                samples.forEachIndexed { i, v ->
                    val p = Offset(x0 + i * step, y(v))
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, lineColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val last = samples.last()
                drawCircle(if (last <= threshold) lineColor else warnColor, 4.dp.toPx(),
                    Offset(size.width, y(last)))
            }
        }
        Text(
            String.format(Locale.US,
                "Starts once it stays under %.4f rad/s (dashed line) for 1.5 s, or after %d min at most.",
                threshold, com.wm.starcam.camera.CaptureEngine.STILL_TIMEOUT_MS / 60_000L),
            style = ScType.caption, color = c.text3, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

// ------------------------------------------------------------------------------- reading the sky

private enum class RowState { Done, Active, Pending }

@Composable
private fun ReadingSkyStep(
    vm: StarCamViewModel,
    busy: Busy?,
    s: AppSettings,
    pending: StopRequest?,
    onCancel: () -> Unit,
) {
    val c = Sc.colors
    val camInfo by vm.camInfo.collectAsState()
    val lenses by vm.lenses.collectAsState()
    val plan = remember(s, camInfo) { planFor(s, camInfo) }
    val label = busy?.label ?: ""
    val measuring = label.startsWith("measuring")
    StepScaffold(
        SessionStep.Sky, if (measuring) 0.7f else 0.35f,
        actions = { StopButton("Cancel", pending, onCancel, Modifier.fillMaxWidth()) },
    ) {
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BusyRing(busy, 150.dp, stroke = 6.dp) {
                Icon(ScIcons.Astro, null, Modifier.size(40.dp), tint = c.accent)
            }
        }
        StepTitle(
            "Reading the sky",
            "One test exposure measures how bright your sky is and how many stars it holds. " +
                "The settings are chosen from that.",
        )
        ScCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
            CheckRow("Test exposure", if (measuring) RowState.Done else RowState.Active)
            CheckRow("Measuring sky brightness and stars", if (measuring) RowState.Active else RowState.Pending)
            CheckRow("Choosing shutter, ISO and frames", RowState.Pending)
        }
        ScCard(Modifier.fillMaxWidth(), color = c.tile) {
            val cam = camInfo
            if (plan.manual) {
                FactRow("Your settings", "${exposureText(plan.exposureSec)} · ISO ${plan.iso} · ${plan.frames} frames")
            } else {
                val lens = if (cam != null && lenses.size >= 2) " on ${lensLabel(lenses, cam)}" else ""
                FactRow(
                    if (s.autoTrailLimit) "Longest sharp exposure" else "Your exposure cap",
                    exposureText(plan.exposureSec) + lens,
                )
                FactRow("ISO", "from the test frame")
            }
        }
        BusyLine(busy)
    }
}

@Composable
private fun CheckRow(title: String, state: RowState, detail: String? = null, trailing: String? = null) {
    val c = Sc.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when (state) {
                RowState.Done -> Box(
                    Modifier.size(26.dp).clip(CircleShape).background(c.selectedFill),
                    contentAlignment = Alignment.Center,
                ) { Icon(ScIcons.Check, null, Modifier.size(15.dp), tint = c.accent) }
                RowState.Active -> ScSpinner(size = 24.dp)
                RowState.Pending -> Box(Modifier.size(24.dp).border(1.5.dp, c.hairlineStrong, CircleShape))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = ScType.body.copy(fontWeight = if (state == RowState.Active) FontWeight.SemiBold else FontWeight.Normal),
                color = if (state == RowState.Pending) c.text3 else c.text)
            if (detail != null) Text(detail, style = ScType.caption, color = c.text3)
        }
        if (trailing != null) Text(trailing, style = ScType.data, color = c.accentText)
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    val c = Sc.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = ScType.bodySmall, color = c.text2, modifier = Modifier.weight(1f))
        Text(value, style = ScType.data, color = c.text)
    }
}

// ------------------------------------------------------------------------------- star focus

@Composable
private fun StarFocusStep(st: CaptureState.Focusing, busy: Busy?, pending: StopRequest?, onCancel: () -> Unit) {
    val c = Sc.colors
    StepScaffold(
        SessionStep.Focus, st.step.toFloat() / st.total.coerceAtLeast(1),
        actions = { StopButton("Cancel", pending, onCancel, Modifier.fillMaxWidth()) },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Star focus", style = ScType.title, color = c.text)
                val left = (st.total - st.step + 1).coerceAtLeast(0) * FocusSweep.STEP_DURATION_MS / 1000L
                Text("about ${durationText(left)} left", style = ScType.bodySmall, color = c.text2,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                ScLabel("Step")
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${st.step}", style = ScType.display, color = c.text)
                    Text(" / ${st.total}", style = ScType.stat, color = c.text3, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
        Text(
            "Sweeping the lens and measuring how small the stars are. Smaller stars mean sharper focus. " +
                "About half a minute, once per night per lens.",
            style = ScType.body, color = c.text2,
        )
        SweepCard(st)
        BusyLine(busy)
    }
}

/**
 * Where the lens is in the sweep: seven coarse positions from infinity outwards, then a fine pass
 * around the sharpest. Read from the engine's own step label ("coarse 0.10", "fine 0.123").
 */
@Composable
private fun SweepCard(st: CaptureState.Focusing) {
    val c = Sc.colors
    val parts = st.label.split(' ')
    val fine = parts.firstOrNull() == "fine"
    val dpt = parts.getOrNull(1)?.toFloatOrNull()
    val coarseDone = if (fine) 7 else (st.step - 1).coerceIn(0, 7)
    ScCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScLabel("Lens position")
            Spacer(Modifier.weight(1f))
            Text(
                (if (fine) "fine pass" else "coarse sweep") +
                    (dpt?.let { String.format(Locale.US, " · %.3f dpt", it) } ?: ""),
                style = ScType.data, color = c.accentText,
            )
        }
        val accent = c.accent
        val track = c.raised
        val pending = c.hairlineStrong
        Canvas(Modifier.fillMaxWidth().height(44.dp).padding(top = 14.dp)) {
            val maxD = 0.32f
            fun x(d: Float) = 8.dp.toPx() + (size.width - 16.dp.toPx()) * (d / maxD).coerceIn(0f, 1f)
            val y = size.height / 2f
            drawLine(track, Offset(x(0f), y), Offset(x(maxD), y), 3.dp.toPx(), StrokeCap.Round)
            for (i in 0 until 7) {
                val d = i * 0.05f
                val done = i < coarseDone
                drawCircle(if (done) accent else pending, if (done) 4.dp.toPx() else 3.dp.toPx(), Offset(x(d), y))
            }
            if (dpt != null) {
                drawCircle(accent.copy(alpha = 0.25f), 11.dp.toPx(), Offset(x(dpt), y))
                drawCircle(accent, 7.dp.toPx(), Offset(x(dpt), y), style = Stroke(2.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("∞ infinity", style = ScType.caption, color = c.text3)
            Spacer(Modifier.weight(1f))
            Text("nearer", style = ScType.caption, color = c.text3)
        }
    }
}

// ------------------------------------------------------------------------------- capturing

@Composable
private fun CapturingStep(
    vm: StarCamViewModel,
    st: CaptureState.CapturingLights,
    busy: Busy?,
    decision: AutoExposure.Decision?,
    focus: FocusSweep.Result?,
    calibration: String?,
    sessionStart: Long,
    s: AppSettings,
    pending: StopRequest?,
    onCancel: () -> Unit,
    onFinish: () -> Unit,
    onShield: () -> Unit,
) {
    val c = Sc.colors
    val completed = if (st.lastStats != null) st.current else st.current - 1
    val kept = (completed - st.rejectedSoFar).coerceAtLeast(0)

    // Which frames were bumped, watched as they happen: the engine reports a running count.
    val rejectedIdx = remember(sessionStart) { mutableStateListOf<Int>() }
    var seenRejected by remember(sessionStart) { mutableIntStateOf(0) }
    LaunchedEffect(st.rejectedSoFar, st.current, st.lastStats) {
        if (st.lastStats != null && st.rejectedSoFar > seenRejected) {
            rejectedIdx.add(st.current - 1)
            seenRejected = st.rejectedSoFar
        }
    }

    StepScaffold(
        SessionStep.Capture, completed.toFloat() / st.total.coerceAtLeast(1),
        actions = {
            // Two buttons only: with a large system font a third pushed "Finish & stack now" into
            // an ellipsis. The shield sits on the live stack instead.
            ActionRow {
                StopButton("Cancel", pending, onCancel, Modifier.weight(1f))
                ScButton(
                    if (pending == StopRequest.Finish) "Finishing…" else "Finish & stack now", onFinish,
                    Modifier.weight(1.8f), kind = ScButtonKind.Accent, enabled = pending == null, height = 52.dp,
                )
            }
        },
    ) {
        LiveStackCard(
            vm = vm,
            refreshKey = st.current * 2 + if (st.lastStats != null) 1 else 0,
            enabled = s.livePreview,
            chip = "Live stack · $kept frame" + if (kept == 1) "" else "s",
            stats = st.lastStats,
            onShield = onShield,
        )
        NowLine(busy, st, pending)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ScLabel("Frame")
                // Up to 900 frames: three digits at full size no longer fit beside the ring.
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${st.current}", color = c.text, maxLines = 1, softWrap = false,
                        style = if (st.total >= 100) ScType.display.copy(fontSize = 44.sp, lineHeight = 44.sp)
                        else ScType.display)
                    Text(" / ${st.total}", style = ScType.stat, color = c.text3, maxLines = 1, softWrap = false,
                        modifier = Modifier.padding(bottom = if (st.total >= 100) 4.dp else 8.dp))
                }
                Spacer(Modifier.height(8.dp))
                ScLabel("Time left")
                RemainingTime(st)
            }
            ExposureRing(busy, st)
        }
        FrameTape(st, completed, rejectedIdx)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$kept kept · ${st.rejectedSoFar} bumped" +
                    " · " + exposureText(st.exposureNs) + " · ISO ${st.iso}",
                style = ScType.caption, color = c.text2, modifier = Modifier.weight(1f),
            )
            SessionClock(sessionStart)
        }
        if (st.cappedByStorage) {
            ScNotice("Fewer frames than planned: the phone is low on storage.", tone = Tone.Warning,
                icon = ScIcons.Warning)
        }
        // The app's reasoning stays visible while it matters; reused calibration is never silent.
        val notes = listOfNotNull(decision?.reasoning, focus?.message, calibration)
        if (notes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                notes.forEach { Text(it, style = ScType.data.copy(fontSize = ScType.caption.fontSize), color = c.text3) }
            }
        }
    }
}

/** The one sentence that says what is happening now. */
@Composable
private fun NowLine(busy: Busy?, st: CaptureState.CapturingLights, pending: StopRequest?) {
    val c = Sc.colors
    val label = busy?.label ?: ""
    val (text, warn) = when {
        pending == StopRequest.Cancel -> "Stopping once this frame is in" to false
        pending == StopRequest.Finish -> "Stacking once this frame is in" to false
        label.startsWith("exposing") -> "Exposing frame ${st.current} — hands off the tripod" to false
        label.startsWith("aligning") -> "Aligning and stacking frame ${st.current}" to false
        label.startsWith("writing DNG") -> "Saving the RAW file" to false
        label.startsWith("cooling") -> "Letting the sensor cool between frames" to false
        label.startsWith("repairing") -> "Repairing the camera — frames so far are safe" to true
        label.isNotEmpty() -> label.replaceFirstChar { it.uppercase() } to false
        else -> "Working" to false
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(14.dp).clip(CircleShape).background((if (warn) c.warning else c.accent).copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(7.dp).clip(CircleShape).background(if (warn) c.warning else c.accent)) }
        Spacer(Modifier.width(10.dp))
        Text(text, style = ScType.body, color = if (warn) c.warning else c.text, maxLines = 2)
    }
}

/** Exposure progress for the current frame, from the engine's busy clock. */
@Composable
private fun ExposureRing(busy: Busy?, st: CaptureState.CapturingLights) {
    val c = Sc.colors
    val label = busy?.label ?: ""
    val title = when {
        label.startsWith("exposing") -> "Exposing"
        label.startsWith("aligning") -> "Stacking"
        label.startsWith("writing") -> "Saving"
        label.startsWith("cooling") -> "Cooling"
        label.startsWith("repairing") -> "Repairing"
        else -> "Working"
    }
    BusyRing(busy, 132.dp, stroke = 6.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ScLabel(title, color = if (title == "Repairing") c.warning else c.text3)
            if (busy != null && label.startsWith("exposing") && busy.expectedMs > 0L) {
                val elapsed = rememberElapsedMs(busy.startedAtMs)
                val secs = (elapsed / 1000.0).coerceAtMost(busy.expectedMs / 1000.0 + 99.0)
                Text(String.format(Locale.US, "%.1f", secs), style = ScType.readout, color = c.text)
                Text("/ " + exposureText(st.exposureNs), style = ScType.data, color = c.text3)
            } else if (busy != null) {
                val elapsed = rememberElapsedMs(busy.startedAtMs)
                Text(durationClock(elapsed / 1000L), style = ScType.stat, color = c.text,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/**
 * Time left in the burst, ticking in real time. Approximate - reshoots and thermal pauses stretch
 * it - hence the "~".
 */
@Composable
private fun RemainingTime(st: CaptureState.CapturingLights) {
    val perFrameSec = st.exposureNs / 1e9 + st.interFrameGapMs / 1000.0
    val inFrameMs = rememberElapsedMs(st.frameStartedAtMs)
    val thisFrameLeft =
        if (st.frameStartedAtMs == 0L) perFrameSec
        else (perFrameSec - inFrameMs / 1000.0).coerceIn(0.0, perFrameSec)
    val remainingSec = (st.total - st.current) * perFrameSec + thisFrameLeft
    Text("~" + durationText(remainingSec.toLong()), style = ScType.stat, color = Sc.colors.text)
}

@Composable
private fun SessionClock(startedAtMs: Long) {
    if (startedAtMs == 0L) return
    val elapsed = rememberElapsedMs(startedAtMs)
    Text("session " + durationClock(elapsed / 1000L), style = ScType.data, color = Sc.colors.text3)
}

/** One cell per frame: kept, bumped, exposing now, still to come. A bar for very long bursts. */
@Composable
private fun FrameTape(st: CaptureState.CapturingLights, completed: Int, rejectedIdx: List<Int>) {
    val c = Sc.colors
    val total = st.total
    if (total <= 0) return
    if (total > 40) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.raised)) {
            Box(Modifier.fillMaxWidth((completed.toFloat() / total).coerceIn(0f, 1f)).height(8.dp)
                .clip(CircleShape).background(c.accent))
        }
        return
    }
    val exposing = st.lastStats == null
    Row(Modifier.fillMaxWidth().height(20.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 0 until total) {
            val cell = when {
                i < completed -> if (i in rejectedIdx) TapeCell.Rejected else TapeCell.Kept
                i == completed && exposing -> TapeCell.Exposing
                else -> TapeCell.Pending
            }
            val shape = RoundedCornerShape(4.dp)
            Box(
                Modifier.weight(1f).fillMaxSize().clip(shape).then(
                    when (cell) {
                        TapeCell.Kept -> Modifier.background(c.accent.copy(alpha = 0.75f))
                        TapeCell.Rejected -> Modifier.background(c.warning.copy(alpha = 0.75f))
                        TapeCell.Exposing -> Modifier.background(c.selectedFill).border(1.5.dp, c.accent, shape)
                        TapeCell.Pending -> Modifier.background(c.raised)
                    }
                )
            )
        }
    }
}

/**
 * The stack so far, refreshed whenever [refreshKey] changes. One reusable buffer: a fresh 2.7 MB
 * bitmap per tick would churn the heap all session.
 */
@Composable
private fun LiveStackCard(
    vm: StarCamViewModel,
    refreshKey: Int,
    enabled: Boolean,
    chip: String,
    stats: FrameStats?,
    placeholder: String = "The live stack appears after the first frame.",
    onShield: (() -> Unit)? = null,
) {
    val c = Sc.colors
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    // The engine draws the stack turned upright like the final photo: portrait when the phone
    // was upright.
    val rotation = vm.engine?.rotation?.collectAsState()?.value ?: 0
    val portrait = rotation == 90 || rotation == 270
    val buffer = remember(portrait) {
        if (portrait) Bitmap.createBitmap(720, 960, Bitmap.Config.ARGB_8888)
        else Bitmap.createBitmap(960, 720, Bitmap.Config.ARGB_8888)
    }
    LaunchedEffect(refreshKey, portrait) {
        withContext(Dispatchers.Default) {
            if (vm.engine?.fillPreview(buffer) == true) {
                // A copy, so Compose never reads the buffer while the next fill writes it.
                image = buffer.copy(Bitmap.Config.ARGB_8888, false)?.asImageBitmap()
            }
        }
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(if (portrait) 3f / 4f else 4f / 3f).clip(ScShapes.card)
            .background(c.sunken).border(1.dp, c.hairline, ScShapes.card),
    ) {
        val img = image
        if (img != null) {
            Image(img, "Live stack preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                colorFilter = scImageFilter)
        } else {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(ScIcons.Stack, null, Modifier.size(28.dp), tint = c.text3)
                Text(
                    if (enabled) placeholder else "Live preview is off (Settings › Stacking).",
                    style = ScType.bodySmall, color = c.text3, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        ScGlass(Modifier.align(Alignment.TopStart).padding(10.dp)) {
            Icon(ScIcons.Stack, null, Modifier.size(14.dp), tint = c.accent)
            Text(chip.uppercase(), style = ScType.label, color = c.text)
        }
        if (onShield != null) {
            ScGlass(
                Modifier.align(Alignment.TopEnd).padding(6.dp).heightIn(min = 44.dp)
                    .clip(ScShapes.pill).clickable(role = Role.Button, onClick = onShield)
                    .semantics { contentDescription = "Screen shield: black screen until double-tap" },
            ) {
                Icon(ScIcons.Shield, null, Modifier.size(16.dp), tint = c.text)
                Text("Shield", style = ScType.caption.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            }
        }
        if (stats != null) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, c.glass)))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val drift = sqrt(stats.dx * stats.dx + stats.dy * stats.dy)
                Text("stars ${stats.starCount}", style = ScType.data, color = c.text)
                Text(String.format(Locale.US, "FWHM %.1f px", stats.medianFwhm), style = ScType.data, color = c.text)
                Text(
                    if (stats.aligned) String.format(Locale.US, "drift %.1f px", drift) else "not aligned",
                    style = ScType.data, color = if (stats.aligned) c.text else c.warning,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------- dark frames

@Composable
private fun CoverLensStep(
    st: CaptureState.AwaitingDarkCover,
    s: AppSettings,
    pending: StopRequest?,
    onCovered: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = Sc.colors
    StepScaffold(
        SessionStep.Darks, 0.1f, centered = true,
        actions = {
            ScButton("Lens covered", onCovered, Modifier.fillMaxWidth(), kind = ScButtonKind.Primary,
                icon = ScIcons.Check, enabled = pending == null, height = 52.dp)
            ActionRow {
                StopButton("Cancel", pending, onCancel, Modifier.weight(1f), height = 48.dp)
                ScButton("Skip dark frames", onSkip, Modifier.weight(1.4f), enabled = pending == null, height = 48.dp)
            }
        },
    ) {
        Icon(ScIcons.DarkFrame, null, Modifier.size(56.dp), tint = c.accent)
        StepTitle(
            "Cover the lens",
            "Put the lens cap on, or hold a dark cloth or your palm firmly over the camera. " +
                "StarCam then takes ${s.darkCount} dark frames to find and remove hot pixels.",
            centered = true,
        )
        ScRing(st.secondsLeft / 60f, Modifier.size(150.dp), strokeWidth = 6.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${st.secondsLeft}", style = ScType.display, color = c.text)
                    Text("s", style = ScType.stat, color = c.text3, modifier = Modifier.padding(bottom = 8.dp))
                }
                Text("then skips", style = ScType.caption, color = c.text3)
            }
        }
        Text(
            "If nobody presses anything, the dark frames are skipped and the photo is stacked without them.",
            style = ScType.bodySmall, color = c.text3, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DarksStep(
    st: CaptureState.CapturingDarks,
    busy: Busy?,
    pending: StopRequest?,
    onCancel: () -> Unit,
    onShield: () -> Unit,
) {
    val c = Sc.colors
    StepScaffold(
        SessionStep.Darks, st.current.toFloat() / st.total.coerceAtLeast(1), centered = true,
        actions = {
            ActionRow {
                StopButton("Cancel", pending, onCancel, Modifier.weight(1f))
                ScIconButton(ScIcons.Shield, "Screen shield", onShield, size = 52.dp, iconSize = 22.dp)
            }
        },
    ) {
        BusyRing(busy, 180.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ScLabel("Dark frame")
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${st.current}", style = ScType.display, color = c.text)
                    Text(" / ${st.total}", style = ScType.stat, color = c.text3, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
        StepTitle("Keep the lens covered", "Dark frames map the sensor's hot pixels so they can be removed.", centered = true)
        BusyLine(busy)
    }
}

// ------------------------------------------------------------------------------- stacking

private val StageOrder = listOf("prepare", "align", "stack", "foreground", "post", "write", "saving")

private class StageGroup(val title: String, val detail: String, val stages: Set<String>)

@Composable
private fun StackingStep(
    vm: StarCamViewModel,
    st: CaptureState.Stacking,
    busy: Busy?,
    s: AppSettings,
    pending: StopRequest?,
    onStop: () -> Unit,
) {
    val c = Sc.colors
    val groups = buildList {
        add(StageGroup("Aligning & stacking", "lining up the stars, rejecting satellites and planes",
            setOf("prepare", "align", "stack")))
        if (s.stackConfig().foregroundComposite || st.stage == "foreground") {
            add(StageGroup("Foreground blend", "a sharp landscape under the sharp stars", setOf("foreground")))
        }
        add(StageGroup("Finishing", "light pollution, colour, faint detail", setOf("post")))
        add(StageGroup("Saving", "master files and the gallery photo", setOf("write", "saving")))
    }
    val idx = StageOrder.indexOf(st.stage).coerceAtLeast(0)
    StepScaffold(
        SessionStep.Stack, st.percent / 100f,
        actions = { StopButton("Stop", pending, onStop, Modifier.fillMaxWidth()) },
    ) {
        LiveStackCard(vm, refreshKey = st.percent, enabled = s.livePreview, chip = "Stacking", stats = null,
            placeholder = "The image appears here as it stacks.")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Processing your photo", style = ScType.bodySmall, color = c.text2)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${st.percent}", style = ScType.display, color = c.text)
                    Text("%", style = ScType.stat, color = c.text3, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
        ScCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
            groups.forEach { g ->
                val indices = g.stages.map { StageOrder.indexOf(it) }
                val rowState = when {
                    st.stage in g.stages -> RowState.Active
                    indices.all { it < idx } -> RowState.Done
                    else -> RowState.Pending
                }
                CheckRow(g.title, rowState, detail = g.detail,
                    trailing = if (rowState == RowState.Active) "${st.percent}%" else null)
            }
        }
        ScNotice("Capture is done — you can pick up the phone now.", tone = Tone.Accent, icon = ScIcons.CheckCircle)
        BusyLine(busy)
    }
}

// ------------------------------------------------------------------------------- done

@Composable
private fun DoneStep(
    st: CaptureState.Done,
    decision: AutoExposure.Decision?,
    onClose: () -> Unit,
    onOpenSession: (File) -> Unit,
) {
    val c = Sc.colors
    val context = LocalContext.current
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    // Null until the file has been looked at. False when the stack finished but no JPEG was
    // written (out of storage or memory): the screen used to spin on a preview that never came
    // and offer to share a file that did not exist.
    var jpegSaved by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(st.jpegFile) {
        val file = st.jpegFile
        val (saved, bitmap) = withContext(Dispatchers.IO) {
            val saved = file != null && file.isFile && file.length() > 0L
            // Downsampled: a full-res stack is up to 50 MP, ~200 MB as one ARGB bitmap.
            saved to if (saved) decodeDownsampled(file!!.absolutePath, 1440)?.asImageBitmap() else null
        }
        jpegSaved = saved
        image = bitmap
    }
    val result = st.result
    val photo = remember(st, jpegSaved) {
        if (jpegSaved == false) null else st.galleryUri ?: st.jpegFile?.let { photoUri(context, it) }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth()) {
                val img = image
                if (result != null && img != null) {
                    Image(img, "The finished photo", Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth, colorFilter = scImageFilter)
                } else {
                    Box(Modifier.fillMaxWidth().height(if (result == null) 200.dp else 260.dp).background(c.sunken),
                        contentAlignment = Alignment.Center) {
                        if (result != null && jpegSaved == null) ScSpinner(size = 28.dp)
                        else Icon(ScIcons.Stack, null, Modifier.size(44.dp), tint = c.text3)
                    }
                }
                ScIconButton(
                    ScIcons.Close, "Close", onClose,
                    modifier = Modifier.padding(12.dp).align(Alignment.TopStart),
                )
            }
            Column(Modifier.padding(ScreenMargin), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (result == null) {
                    StepTitle(
                        "RAWs saved",
                        if (st.framesCaptured == 1) "1 frame in ${st.sessionDir.name}."
                        else "${st.framesCaptured} frames in ${st.sessionDir.name}. Stack them later " +
                            "from Sessions, or copy the folder to a computer.",
                    )
                } else if (jpegSaved == false) {
                    ScNotice(
                        "Stacking worked, but the JPEG could not be written - the phone may be out of " +
                            "storage. The frames are kept: re-process the session from Sessions once " +
                            "there is room.",
                        tone = Tone.Warning, icon = ScIcons.Warning, title = "Photo not saved",
                    )
                } else if (st.galleryUri != null) {
                    ScNotice("Pictures / StarCam", tone = Tone.Accent, icon = ScIcons.CheckCircle,
                        title = "Saved to Gallery")
                } else if (jpegSaved == true) {
                    ScNotice("Saved in the session folder; the gallery copy failed.", tone = Tone.Warning,
                        icon = ScIcons.Warning)
                }
                st.warning?.let { ScNotice(it, tone = Tone.Warning, icon = ScIcons.Warning) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (result != null) {
                        ScStatTile("${result.framesUsed}/${result.framesTotal}", "frames", Modifier.weight(1f))
                        decision?.let {
                            val total = Math.round(result.framesUsed * (it.exposureNs / 1e9))
                            ScStatTile(durationShort(total), "total exposure", Modifier.weight(1f))
                        }
                    } else {
                        ScStatTile("${st.framesCaptured}", "frames", Modifier.weight(1f))
                    }
                    if (st.elapsedMs > 0L) {
                        ScStatTile(durationShort(st.elapsedMs / 1000L), "took", Modifier.weight(1f))
                    }
                }
                if (result != null) {
                    val lines = buildList {
                        add(String.format(Locale.US, "Star size %.1f → %.1f px · %.1f%% rejected · stacked in %s",
                            result.fwhmRef, result.fwhmStack, result.rejectionPct, durationText(result.elapsedMs / 1000L)))
                        if (result.meteorCount > 0) {
                            add("${result.meteorCount} meteor candidate${if (result.meteorCount == 1) "" else "s"} — frames ${result.meteorFrames}")
                        }
                        if (result.foreground.isNotEmpty()) add("Foreground blend: ${result.foreground}")
                    }
                    lines.forEach { Text(it, style = ScType.caption, color = c.text3) }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = ScreenMargin, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (result != null && photo != null) {
                ScButton("Share", { sharePhoto(context, photo) }, Modifier.fillMaxWidth(),
                    kind = ScButtonKind.Primary, icon = ScIcons.Share, height = 52.dp)
                ActionRow {
                    ScButton("Open", { openPhoto(context, photo) }, Modifier.weight(1f), icon = ScIcons.Open)
                    ScButton("Re-process", { onOpenSession(st.sessionDir) }, Modifier.weight(1f), icon = ScIcons.Refresh)
                }
            } else {
                ActionRow {
                    ScButton("Open session", { onOpenSession(st.sessionDir) }, Modifier.weight(1f))
                    ScButton("Done", onClose, Modifier.weight(1f), kind = ScButtonKind.Primary)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------- error

@Composable
private fun ErrorStep(
    vm: StarCamViewModel,
    st: CaptureState.Error,
    onOk: () -> Unit,
    onOpenSession: (File) -> Unit,
) {
    val c = Sc.colors
    val thermal by vm.thermal.status.collectAsState()
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = ScreenMargin, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (thermal >= 2) {
                ScStatusPill(
                    "Phone ${vm.thermal.label()}", tone = Tone.Warning, icon = ScIcons.Heat,
                    modifier = Modifier.align(Alignment.Start),
                )
            }
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(c.errorFill).border(1.dp, c.errorBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(ScIcons.Warning, null, Modifier.size(32.dp), tint = c.error) }
            // The engine reports a failed stack as "stacking failed: <reason>". Capture itself
            // went fine then, so the screen says what did fail, in its own words.
            val stackingFailed = st.message.startsWith("stacking failed")
            StepTitle(
                when {
                    stackingFailed -> "Couldn't stack the photo"
                    st.framesCaptured > 0 -> "Capture stopped"
                    else -> "Something went wrong"
                },
                if (stackingFailed) {
                    st.message.removePrefix("stacking failed").trimStart(':', ' ')
                        .replaceFirstChar { it.uppercase() }
                } else st.message,
                centered = true,
            )
            val dir = st.sessionDir
            if (dir != null && st.framesCaptured > 0) {
                ScCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.selectedFill),
                            contentAlignment = Alignment.Center,
                        ) { Icon(ScIcons.Stack, null, Modifier.size(22.dp), tint = c.accent) }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "${st.framesCaptured} frame${if (st.framesCaptured == 1) " is" else "s are"} safe",
                                style = ScType.headline, color = c.text,
                            )
                            Text("Saved in Sessions · ${dir.name}", style = ScType.caption, color = c.text3)
                        }
                    }
                    ScButton("Open in Sessions", { onOpenSession(dir) }, Modifier.fillMaxWidth().padding(top = 12.dp),
                        kind = ScButtonKind.Accent)
                }
            } else if (dir != null) {
                Text("Session folder: ${dir.name}", style = ScType.data, color = c.text3)
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ScLabel("What to do next")
                if (stackingFailed) {
                    NextStep(1, "Stacking lines the frames up by their stars. Clouds, a bright sky or a " +
                        "covered lens leave too few to do that.")
                    NextStep(2, "The frames are saved: re-stack them from Sessions, or copy the DNGs to a computer.")
                } else {
                    NextStep(1, "If the phone feels warm, let it cool for a few minutes — out of its case if you can.")
                    NextStep(2, "Start a new capture, or stack the saved frames from Sessions.")
                }
                NextStep(3, "Keeps happening? Share the log: everything that happened is in it.")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ScreenMargin, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ShareDebugButton(vm, Modifier.weight(1f), label = "Share log")
            ScButton("OK", onOk, Modifier.weight(1f), kind = ScButtonKind.Primary, height = 52.dp)
        }
    }
}

@Composable
private fun NextStep(n: Int, text: String) {
    val c = Sc.colors
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(26.dp).border(1.dp, c.hairlineStrong, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", style = ScType.data, color = c.text2)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = ScType.body, color = c.text, modifier = Modifier.padding(top = 2.dp))
    }
}
