package com.wm.starcam.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wm.starcam.ui.theme.GeistMono
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScShapes
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.ui.theme.ScreenMargin

/*
 * StarCam components (design/handoff/screens/Components.html), sized a step tighter than the
 * mockups so controls leave the viewfinder room: 48 dp buttons instead of 56, a 76 dp shutter
 * instead of 84, 34-40 dp chips and segments. Nothing here may light up a dark-adapted eye:
 * no white fills, primary is deep blue with light text, night vision swaps every colour to red.
 */

enum class Tone { Normal, Accent, Warning, Error }

// ------------------------------------------------------------------------- screen + surfaces

/** A full screen on the app background, inset from the status and navigation bars. */
@Composable
fun ScScreen(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(Sc.colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        content = content,
    )
}

/** Back chevron, title, optional subtitle and trailing actions. */
@Composable
fun ScTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .clickable(onClick = onBack, role = Role.Button)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(ScIcons.ChevronLeft, null, Modifier.size(22.dp), tint = Sc.colors.text)
            }
        } else {
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, style = ScType.screenTitle, color = Sc.colors.text, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = ScType.caption, color = Sc.colors.text3, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** The basic card: surface fill, hairline border, 20 dp corners. */
@Composable
fun ScCard(
    modifier: Modifier = Modifier,
    color: Color = Sc.colors.surface,
    borderColor: Color = Sc.colors.hairline,
    shape: Shape = ScShapes.card,
    padding: PaddingValues = PaddingValues(14.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(shape)
            .background(color)
            .border(1.dp, borderColor, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Translucent dark pill for text laid over imagery (viewfinder, photos). */
@Composable
fun ScGlass(
    modifier: Modifier = Modifier,
    shape: Shape = ScShapes.pill,
    padding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.clip(shape).background(Sc.colors.glass).border(1.dp, Sc.colors.hairline, shape)
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** Uppercase section label: FRAME, TIME LEFT, STILLNESS. */
@Composable
fun ScLabel(text: String, modifier: Modifier = Modifier, color: Color = Sc.colors.text3) {
    Text(text.uppercase(), style = ScType.label, color = color, modifier = modifier)
}

// ------------------------------------------------------------------------------------ buttons

enum class ScButtonKind { Primary, Secondary, Accent, Danger, Text }

@Composable
fun ScButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ScButtonKind = ScButtonKind.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 48.dp,
) {
    val c = Sc.colors
    val bg: Color
    val border: Color
    val fg: Color
    when (kind) {
        ScButtonKind.Primary -> { bg = c.primaryFill; border = c.primaryBorder; fg = c.primaryText }
        ScButtonKind.Secondary -> { bg = c.buttonSecondary; border = c.hairlineStrong; fg = c.text }
        ScButtonKind.Accent -> { bg = c.selectedFill; border = c.selectedBorder; fg = c.onSelected }
        ScButtonKind.Danger -> { bg = c.errorFill; border = c.errorBorder; fg = c.error }
        ScButtonKind.Text -> { bg = Color.Transparent; border = Color.Transparent; fg = c.accentText }
    }
    Row(
        modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(ScShapes.button)
            .background(bg)
            .border(1.dp, border, ScShapes.button)
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = fg)
            Spacer(Modifier.width(7.dp))
        }
        Text(text, style = ScType.button, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Round icon button; always 44 dp or larger to stay a real touch target with gloves. */
@Composable
fun ScIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp,
    selected: Boolean = false,
    tint: Color? = null,
    enabled: Boolean = true,
    filled: Boolean = true,
) {
    val c = Sc.colors
    Box(
        modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .then(
                if (filled) Modifier
                    .background(if (selected) c.selectedFill else c.buttonSecondary)
                    .border(1.dp, if (selected) c.selectedBorder else c.hairline, CircleShape)
                else Modifier
            )
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(iconSize),
            tint = tint ?: if (selected) c.onSelected else c.text)
    }
}

// ------------------------------------------------------------------------ chips and segments

/** Pill-shaped choice chip, optionally with an icon and a small badge (e.g. BETA). */
@Composable
fun ScChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    mono: Boolean = false,
    height: Dp = 34.dp,
    enabled: Boolean = true,
) {
    val c = Sc.colors
    Row(
        modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(if (selected) c.selectedFill else c.buttonSecondary)
            .border(1.dp, if (selected) c.selectedBorder else c.hairline, CircleShape)
            .clickable(enabled = enabled, onClick = onClick, role = Role.Tab)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp), tint = if (selected) c.onSelected else c.text2)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = if (mono) ScType.dataLarge else ScType.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = if (selected) c.onSelected else c.text2,
            maxLines = 1,
        )
    }
}

/**
 * Segmented control: one of [options], the selected one lifted onto a tinted fill. Compact by
 * default (40 dp); used for focus method, start delay, style, etc.
 */
@Composable
fun ScSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    mono: Boolean = false,
    enabled: Boolean = true,
) {
    val c = Sc.colors
    Row(
        modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(ScShapes.chip)
            .background(c.sunken)
            .border(1.dp, c.hairline, ScShapes.chip)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(ScShapes.segmentItem)
                    .then(
                        if (sel) Modifier.background(c.selectedFill).border(1.dp, c.selectedBorder, ScShapes.segmentItem)
                        else Modifier
                    )
                    .clickable(enabled = enabled, onClick = { onSelect(i) }, role = Role.RadioButton),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = if (mono) ScType.dataLarge else ScType.bodySmall.copy(
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (sel) c.onSelected else c.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// -------------------------------------------------------------------- switch, slider, help

/** The design's toggle: 44 × 26 track, light knob when on. Draws no white in night vision. */
@Composable
fun ScSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = Sc.colors
    val knobX by animateDpAsState(if (checked) 20.dp else 2.dp, tween(140), label = "knob")
    Box(
        modifier
            .size(width = 48.dp, height = 44.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .then(
                if (onCheckedChange != null) Modifier.clickable(
                    enabled = enabled, role = Role.Switch, onClick = { onCheckedChange(!checked) })
                else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = 44.dp, height = 26.dp).clip(CircleShape)
                .background(if (checked) c.switchTrackOn else c.switchTrackOff),
        ) {
            Box(
                Modifier.offset(x = knobX, y = 3.dp).size(20.dp).clip(CircleShape)
                    .background(if (checked) c.switchKnobOn else c.switchKnobOff),
            )
        }
    }
}

/** The design's slider: a thin rounded track and a round knob (Material's default is a bar). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val c = Sc.colors
    val interaction = remember { MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interaction,
        steps = steps,
        valueRange = valueRange,
        thumb = {
            Box(
                Modifier.size(22.dp).clip(CircleShape)
                    .background(if (enabled) c.switchKnobOn else c.text3)
                    .border(2.dp, c.background.copy(alpha = 0.5f), CircleShape),
            )
        },
        track = { state ->
            val span = state.valueRange.endInclusive - state.valueRange.start
            val frac = if (span > 0f) ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.sliderTrack)) {
                Box(
                    Modifier.fillMaxWidth(frac).height(6.dp).clip(CircleShape)
                        .background(if (enabled) c.accent else c.text3),
                )
            }
        },
    )
}

/** The (i) button: a 44 dp target that expands an explanation in place. */
@Composable
fun ScInfoButton(expanded: Boolean, onClick: () -> Unit, contentDescription: String) {
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .clickable(onClick = onClick, role = Role.Button)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(ScIcons.Info, null, Modifier.size(20.dp),
            tint = if (expanded) Sc.colors.accent else Sc.colors.text3)
    }
}

/** The explanation an (i) expands. */
@Composable
fun ScHelpText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = ScType.bodySmall,
        color = Sc.colors.text2,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Sc.colors.sunken)
            .border(1.dp, Sc.colors.hairline, RoundedCornerShape(14.dp))
            .padding(12.dp),
    )
}

// ------------------------------------------------------------------------------- rings

/**
 * Progress ring filling clockwise from 12 o'clock, with a soft glow on the arc. [content] sits in
 * the middle (a countdown, a readout).
 */
@Composable
fun ScRing(
    progress: Float,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 6.dp,
    color: Color = Sc.colors.accent,
    trackColor: Color = Sc.colors.raised,
    // A hard-edged translucent band reads as a second ring rather than a glow; off by default.
    glow: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(400), label = "ring")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = strokeWidth.toPx()
            val inset = sw / 2f + (if (glow) sw else 0f)
            val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
            val topLeft = Offset(inset, inset)
            drawArc(trackColor, 0f, 360f, false, topLeft, arcSize, style = Stroke(sw))
            if (p > 0f) {
                if (glow) {
                    drawArc(color.copy(alpha = 0.18f), -90f, 360f * p, false, topLeft, arcSize,
                        style = Stroke(sw * 3f, cap = StrokeCap.Round))
                }
                drawArc(color, -90f, 360f * p, false, topLeft, arcSize,
                    style = Stroke(sw, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

/** Indeterminate spinner for steps of unknown length. */
@Composable
fun ScSpinner(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = Sc.colors.accent,
              strokeWidth: Dp = 2.5.dp) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "a")
    val track = Sc.colors.raised
    Canvas(modifier.size(size)) {
        val sw = strokeWidth.toPx()
        val arc = Size(this.size.width - sw, this.size.height - sw)
        drawArc(track, 0f, 360f, false, Offset(sw / 2, sw / 2), arc, style = Stroke(sw))
        drawArc(color, angle - 90f, 100f, false, Offset(sw / 2, sw / 2), arc,
            style = Stroke(sw, cap = StrokeCap.Round))
    }
}

// --------------------------------------------------------------------- session pipeline

enum class SessionStep(val label: String) {
    Still("Still"), Sky("Sky"), Focus("Focus"), Capture("Capture"), Darks("Darks"),
    Stack("Stack"), Done("Done"),
}

/**
 * The seven-step progress strip at the top of every session screen. Finished steps are solid,
 * the current one fills by [progressInStep], later ones are dim.
 */
@Composable
fun ScPipeline(
    current: SessionStep,
    progressInStep: Float,
    modifier: Modifier = Modifier,
) {
    val c = Sc.colors
    Row(modifier.fillMaxWidth().padding(horizontal = ScreenMargin),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SessionStep.entries.forEach { step ->
            val done = step.ordinal < current.ordinal
            val isCurrent = step == current
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(c.raised)) {
                    val frac = when {
                        done -> 1f
                        isCurrent -> progressInStep.coerceIn(0.08f, 1f)
                        else -> 0f
                    }
                    if (frac > 0f) {
                        Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(CircleShape)
                            .background(if (done) c.accent.copy(alpha = 0.6f) else c.accent))
                    }
                }
                Text(
                    step.label,
                    style = ScType.caption.copy(
                        fontSize = 11.sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (isCurrent) c.text else c.text3,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
    }
}

enum class TapeCell { Kept, Rejected, Exposing, Pending }

/**
 * One square per frame: kept, bumped/rejected (amber), exposing now (outlined), still to go.
 * Long sessions (trails, meteor) have hundreds of frames, so past [maxCells] it becomes a bar.
 */
@Composable
fun ScFrameTape(
    kept: Int,
    rejected: Int,
    exposing: Boolean,
    total: Int,
    modifier: Modifier = Modifier,
    maxCells: Int = 40,
) {
    val c = Sc.colors
    if (total <= 0) return
    if (total > maxCells) {
        val done = (kept + rejected).toFloat() / total
        Box(modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.raised)) {
            Box(Modifier.fillMaxWidth(done.coerceIn(0f, 1f)).height(8.dp).clip(CircleShape)
                .background(c.accent))
        }
        return
    }
    Row(modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 0 until total) {
            val cell = when {
                i < kept + rejected -> if (i >= kept) TapeCell.Rejected else TapeCell.Kept
                i == kept + rejected && exposing -> TapeCell.Exposing
                else -> TapeCell.Pending
            }
            val shape = RoundedCornerShape(4.dp)
            Box(
                Modifier.weight(1f).fillMaxSize().clip(shape).then(
                    when (cell) {
                        TapeCell.Kept -> Modifier.background(c.accent.copy(alpha = 0.75f))
                        TapeCell.Rejected -> Modifier.background(c.warning.copy(alpha = 0.7f))
                        TapeCell.Exposing -> Modifier.background(c.selectedFill).border(1.5.dp, c.accent, shape)
                        TapeCell.Pending -> Modifier.background(c.raised)
                    }
                )
            )
        }
    }
}

// ------------------------------------------------------------------------------ shutter

/** The big round button: accent ring, soft blue (or red) disk, gentle glow when it can fire. */
@Composable
fun ScShutter(
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
) {
    val c = Sc.colors
    Box(
        modifier
            .size(size)
            .then(if (enabled) Modifier.shadow(18.dp, CircleShape, ambientColor = c.accentGlow, spotColor = c.accentGlow) else Modifier)
            .clip(CircleShape)
            .background(c.background)
            .border(3.dp, if (enabled) c.accent else c.text3.copy(alpha = 0.5f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size - 18.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) Brush.radialGradient(c.shutterDisk)
                    else Brush.radialGradient(listOf(c.raised, c.surface))
                ),
        )
    }
}

// ------------------------------------------------------------------------ status & notices

/** Small pill with a status dot: "Battery 86% · cool", "Phone 41 °C · warm". */
@Composable
fun ScStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Normal,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = Sc.colors
    val (fg, bg, border) = when (tone) {
        Tone.Warning -> Triple(c.warning, c.warningFill, c.warningBorder)
        Tone.Error -> Triple(c.error, c.errorFill, c.errorBorder)
        else -> Triple(c.text, c.surface, c.hairlineStrong)
    }
    Row(
        modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, border, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(15.dp), tint = fg)
        else Box(Modifier.size(7.dp).clip(CircleShape).background(if (tone == Tone.Normal) c.accent else fg))
        Text(text, style = ScType.bodySmall.copy(fontWeight = FontWeight.Medium), color = fg, maxLines = 1)
    }
}

/** Tip / warning / error / saved card with an optional title and dismiss button. */
@Composable
fun ScNotice(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Accent,
    icon: ImageVector? = null,
    title: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val c = Sc.colors
    val (fg, bg, border) = when (tone) {
        Tone.Warning -> Triple(c.warning, c.warningFill, c.warningBorder)
        Tone.Error -> Triple(c.error, c.errorFill, c.errorBorder)
        Tone.Accent -> Triple(c.accentText, c.glass, c.selectedBorder)
        Tone.Normal -> Triple(c.text, c.surface, c.hairlineStrong)
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg)
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = if (onDismiss != null) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = fg)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            if (title != null) Text(title, style = ScType.headline, color = if (tone == Tone.Accent) c.text else fg)
            Text(text, style = ScType.bodySmall,
                color = if (tone == Tone.Warning || tone == Tone.Error) fg else c.text2)
        }
        if (onDismiss != null) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onDismiss, role = Role.Button)
                    .semantics { contentDescription = "Dismiss" },
                contentAlignment = Alignment.Center,
            ) { Icon(ScIcons.Close, null, Modifier.size(18.dp), tint = c.text3) }
        }
    }
}

/** Number over a caption, on a tile: "20/20 · frames". */
@Composable
fun ScStatTile(value: String, label: String, modifier: Modifier = Modifier) {
    ScCard(modifier, padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), color = Sc.colors.tile) {
        Text(value, style = ScType.stat, color = Sc.colors.text, maxLines = 1)
        Text(label, style = ScType.caption, color = Sc.colors.text3, maxLines = 1)
    }
}

/** A settings tile with an icon, two lines of text and a switch (the Pro sheet's 2 × 2 grid). */
@Composable
fun ScToggleTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Sc.colors
    ScCard(
        modifier,
        color = c.tile,
        padding = PaddingValues(start = 12.dp, top = 8.dp, bottom = 10.dp, end = 4.dp),
        onClick = onToggle,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (checked) c.accent else c.text2)
            Spacer(Modifier.weight(1f))
            ScSwitch(checked, null)
        }
        Text(title, style = ScType.headline.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = ScType.caption, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One settings row: label, current value in the accent colour, a trailing control, and an (i)
 * that expands [help] in place. [expanded]/[onToggleHelp] are hoisted so a list can keep one
 * explanation open at a time.
 */
@Composable
fun ScSettingRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    help: String? = null,
    expanded: Boolean = false,
    onToggleHelp: () -> Unit = {},
    labelColor: Color? = null,
    valueColor: Color? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    control: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = ScType.headline.copy(fontWeight = FontWeight.Medium), color = labelColor ?: Sc.colors.text)
                if (value != null) {
                    Text(value, style = ScType.bodySmall, color = valueColor ?: Sc.colors.accentText, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = control)
            if (help != null) ScInfoButton(expanded, onToggleHelp, "What does $label do?")
        }
        if (below != null) below()
        if (help != null && expanded) {
            Spacer(Modifier.height(6.dp))
            ScHelpText(help)
        }
    }
}

/** Confirmation dialog in the design's style. */
@Composable
fun ScConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String = "Cancel",
    danger: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Sc.colors.surface,
        shape = ScShapes.card,
        title = { Text(title, style = ScType.titleSmall, color = Sc.colors.text) },
        text = { Text(text, style = ScType.body, color = Sc.colors.text2) },
        confirmButton = {
            ScButton(confirmText, onConfirm,
                kind = if (danger) ScButtonKind.Danger else ScButtonKind.Primary, height = 44.dp)
        },
        dismissButton = { ScButton(dismissText, onDismiss, height = 44.dp) },
    )
}

/** Mono readout with a smaller unit: 22.5 s, 1600, 14.0 / 22.5 s. */
@Composable
fun ScValueWithUnit(
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    valueStyle: androidx.compose.ui.text.TextStyle = ScType.stat,
    color: Color = Sc.colors.text,
    unitColor: Color = Sc.colors.text3,
) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(value, style = valueStyle, color = color, maxLines = 1)
        if (unit.isNotEmpty()) {
            Text(" $unit", style = ScType.dataLarge.copy(fontFamily = GeistMono), color = unitColor,
                modifier = Modifier.padding(bottom = 3.dp), textAlign = TextAlign.Start)
        }
    }
}
