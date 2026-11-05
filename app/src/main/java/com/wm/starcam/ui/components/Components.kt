package com.wm.starcam.ui.components

import android.graphics.BlurMaskFilter
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import android.view.WindowManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wm.starcam.ui.theme.LocalNightDim
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScShapes
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.ui.theme.ScreenMargin
import com.wm.starcam.ui.theme.scBackground
import com.wm.starcam.ui.theme.scGlow

/*
 * Stelli's components: pills with lavender edges, softly rounded square icon buttons, cards of
 * deep violet glass, and a glow on whatever is chosen or primary. Touch targets stay at 44 dp or
 * more for gloves. Nothing here may light up a dark-adapted eye: the brightest fill is the
 * shutter's lavender disk, and night vision swaps every colour to red.
 */

enum class Tone { Normal, Accent, Warning, Error }

// ------------------------------------------------------------------------- screen + surfaces

/** A full screen on the app's night-sky gradient, inset from the status and navigation bars. */
@Composable
fun ScScreen(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .scBackground()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        content = content,
    )
}

/** Back button, title, optional subtitle and trailing actions. */
@Composable
fun ScTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Sc.colors
    Row(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                Modifier.size(44.dp).clip(ScShapes.iconButton).background(c.buttonSecondary)
                    .border(1.dp, c.hairlineStrong, ScShapes.iconButton)
                    .clickable(onClick = onBack, role = Role.Button)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(ScIcons.ChevronLeft, null, Modifier.size(22.dp), tint = c.text)
            }
            Spacer(Modifier.width(12.dp))
        } else {
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = ScType.screenTitle, color = c.text, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = ScType.caption, color = c.text3, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = actions,
        )
    }
}

/** The basic card: violet glass, a hairline edge, 22 dp corners. */
@Composable
fun ScCard(
    modifier: Modifier = Modifier,
    color: Color = Sc.colors.surface,
    borderColor: Color = Sc.colors.hairline,
    shape: Shape = ScShapes.card,
    padding: PaddingValues = PaddingValues(16.dp),
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

/** Uppercase section label, letter-spaced: TONIGHT, TIME LEFT, STILLNESS. */
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
    val fill: Brush
    val border: Color
    val fg: Color
    when (kind) {
        ScButtonKind.Primary -> { fill = Brush.verticalGradient(c.primaryGradient); border = c.primaryBorder; fg = c.primaryText }
        ScButtonKind.Secondary -> { fill = Brush.verticalGradient(listOf(c.raised, c.buttonSecondary)); border = c.buttonBorder; fg = c.text }
        ScButtonKind.Accent -> { fill = Brush.verticalGradient(listOf(c.selectedFill, c.selectedFill)); border = c.selectedBorder; fg = c.onSelected }
        ScButtonKind.Danger -> { fill = Brush.verticalGradient(listOf(c.errorFill, c.errorFill)); border = c.errorBorder; fg = c.error }
        ScButtonKind.Text -> { fill = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)); border = Color.Transparent; fg = c.accentText }
    }
    val glow = enabled && (kind == ScButtonKind.Primary || kind == ScButtonKind.Accent)
    Row(
        modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.4f)
            .scGlow(c.accentGlow.copy(alpha = c.accentGlow.alpha * (if (kind == ScButtonKind.Primary) 0.8f else 0.45f)),
                radius = 14.dp, enabled = glow)
            .clip(ScShapes.button)
            .background(fill)
            .border(1.dp, border, ScShapes.button)
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(19.dp), tint = if (kind == ScButtonKind.Secondary) c.accent else fg)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = ScType.button, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Icon button, a softly rounded square; always 44 dp or larger to stay a real touch target with gloves. */
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
    shape: Shape = ScShapes.iconButton,
) {
    val c = Sc.colors
    Box(
        modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.4f)
            .scGlow(c.accentGlow.copy(alpha = c.accentGlow.alpha * 0.5f), radius = 12.dp,
                cornerRadius = 16.dp, enabled = selected && filled)
            .clip(shape)
            .then(
                if (filled) Modifier
                    .background(if (selected) c.selectedFill else c.buttonSecondary)
                    .border(1.dp, if (selected) c.selectedBorder else c.hairlineStrong, shape)
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

/** Pill-shaped choice chip, optionally with an icon. */
@Composable
fun ScChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    mono: Boolean = false,
    height: Dp = 36.dp,
    enabled: Boolean = true,
) {
    val c = Sc.colors
    Row(
        modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.4f)
            .scGlow(c.accentGlow.copy(alpha = c.accentGlow.alpha * 0.4f), radius = 10.dp, enabled = selected)
            .clip(CircleShape)
            .background(if (selected) c.selectedFill else c.buttonSecondary)
            .border(1.dp, if (selected) c.selectedBorder else c.hairline, CircleShape)
            .clickable(enabled = enabled, onClick = onClick, role = Role.Tab)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp), tint = if (selected) c.onSelected else c.accent)
            Spacer(Modifier.width(7.dp))
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
 * Segmented control: one of [options], the chosen one lit on a violet fill. Compact by default
 * (40 dp); used for focus method, start delay, style, etc.
 */
@Composable
fun ScSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 42.dp,
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

/** One tab of [ScTabs]: an icon, a label, an optional small badge after the label (β). */
class ScTab(val icon: ImageVector?, val label: String, val badge: String? = null, val description: String? = null)

/**
 * Tabs in a row: icon and label, the chosen one lit in the accent colour with a glowing bar
 * underneath, the rest quiet. A hairline under the whole row.
 */
@Composable
fun ScTabs(
    tabs: List<ScTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    weights: List<Float>? = null,
    height: Dp = 52.dp,
) {
    val c = Sc.colors
    // With a large system font the labels alone fill the tabs; the icons go first.
    val showIcons = LocalDensity.current.fontScale <= 1.2f
    BoxWithConstraints(modifier.fillMaxWidth().height(height)) {
        // A phone under 400 dp across gets a step smaller type and icons, so four tabs fit.
        val compact = maxWidth < 400.dp
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(c.hairline))
        Row(Modifier.fillMaxSize()) {
            tabs.forEachIndexed { i, tab ->
                val sel = i == selectedIndex
                val tint = if (sel) c.accentText else c.text
                Box(
                    Modifier.weight(weights?.getOrNull(i) ?: 1f).fillMaxHeight()
                        .clickable(role = Role.Tab) { onSelect(i) }
                        .semantics {
                            tab.description?.let { contentDescription = it }
                            selected = sel
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showIcons && tab.icon != null) {
                            Icon(tab.icon, null, Modifier.size(if (compact) 18.dp else 20.dp),
                                tint = if (sel) c.accent else c.text2)
                            Spacer(Modifier.width(if (compact) 5.dp else 7.dp))
                        }
                        Text(tab.label, style = ScType.button.copy(
                            fontSize = if (compact) 14.sp else 15.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal),
                            color = tint, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        if (tab.badge != null) {
                            Text(tab.badge, style = ScType.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                color = c.accent2, modifier = Modifier.padding(start = 2.dp).offset(y = (-6).dp))
                        }
                    }
                    if (sel) {
                        Box(
                            Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp)
                                .fillMaxWidth(0.62f).height(3.dp)
                                .scGlow(c.accentGlow, radius = 6.dp)
                                .clip(CircleShape).background(c.accent),
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------- switch, slider, help

/** The toggle: 46 × 28 track, a light knob when on, a soft glow. Draws no white in night vision. */
@Composable
fun ScSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = Sc.colors
    val knobX by animateDpAsState(if (checked) 21.dp else 3.dp, tween(140), label = "knob")
    Box(
        modifier
            .size(width = 50.dp, height = 44.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .then(
                if (onCheckedChange != null) Modifier.clickable(
                    enabled = enabled, role = Role.Switch, onClick = { onCheckedChange(!checked) })
                else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = 46.dp, height = 28.dp)
                .scGlow(c.accentGlow.copy(alpha = c.accentGlow.alpha * 0.5f), radius = 8.dp, enabled = checked)
                .clip(CircleShape)
                .background(
                    if (checked) Brush.horizontalGradient(listOf(c.switchTrackOn, c.accent))
                    else Brush.horizontalGradient(listOf(c.switchTrackOff, c.switchTrackOff))
                )
                .border(1.dp, if (checked) c.selectedBorder else c.hairlineStrong, CircleShape),
        ) {
            Box(
                Modifier.offset(x = knobX, y = 3.dp).size(22.dp).clip(CircleShape)
                    .background(if (checked) c.switchKnobOn else c.switchKnobOff),
            )
        }
    }
}

/** The slider: a thin rounded track lit from the left, and a round glowing knob. */
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
                Modifier.size(22.dp)
                    .scGlow(c.accentGlow, radius = 8.dp, enabled = enabled)
                    .clip(CircleShape)
                    .background(if (enabled) c.switchKnobOn else c.text3)
                    .border(2.dp, if (enabled) c.accent else c.background.copy(alpha = 0.5f), CircleShape),
            )
        },
        track = { state ->
            val span = state.valueRange.endInclusive - state.valueRange.start
            val frac = if (span > 0f) ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.sliderTrack)) {
                Box(
                    Modifier.fillMaxWidth(frac).height(6.dp).clip(CircleShape)
                        .background(
                            if (enabled) Brush.horizontalGradient(listOf(c.switchTrackOn, c.accent))
                            else Brush.horizontalGradient(listOf(c.text3, c.text3))
                        ),
                )
            }
        },
    )
}

/** The (i) button: a 44 dp target that expands an explanation in place. */
@Composable
fun ScInfoButton(
    expanded: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    Box(
        modifier.size(44.dp).clip(CircleShape)
            .clickable(onClick = onClick, role = Role.Button)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(ScIcons.Info, null, Modifier.size(20.dp),
            tint = tint ?: if (expanded) Sc.colors.accent else Sc.colors.text3)
    }
}

/** The chevron of a row that opens: down when closed, up when open. */
@Composable
fun ScChevron(open: Boolean, modifier: Modifier = Modifier, tint: Color = Sc.colors.text2) {
    Icon(ScIcons.ChevronDown, null, modifier.size(22.dp).rotate(if (open) 180f else 0f), tint = tint)
}

/**
 * A tile row: an icon, a thin divider, the name over a short line, and what trails it (a switch,
 * a button, a chevron). The Adjust sheet's rows (the owner's design, 5 Nov 2026); the rows that
 * open more on every other screen are built from it.
 */
@Composable
fun ScOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    role: Role = Role.Button,
    onClick: (() -> Unit)? = null,
    iconTint: Color? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = Sc.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp).clip(ScShapes.tile).background(c.tile)
            .border(1.dp, c.hairline, ScShapes.tile)
            .then(if (onClick != null) Modifier.clickable(role = role, onClick = onClick) else Modifier)
            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = iconTint ?: c.accent)
        Spacer(Modifier.width(16.dp))
        Box(Modifier.width(1.dp).height(28.dp).background(c.hairlineStrong))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = ScType.headline.copy(fontWeight = FontWeight.Medium), color = c.text)
            if (subtitle != null) {
                Text(subtitle, style = ScType.bodySmall, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

/**
 * [ScOptionRow] that opens [content] under it when tapped: "More options", "Details". What is
 * under it stays out of sight, and out of the way, until it is wanted.
 */
@Composable
fun ScMoreRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScOptionRow(icon, title, subtitle, onClick = onToggle) {
            ScChevron(open)
            Spacer(Modifier.width(8.dp))
        }
        if (open) content()
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
            .clip(RoundedCornerShape(16.dp))
            .background(Sc.colors.sunken)
            .border(1.dp, Sc.colors.hairline, RoundedCornerShape(16.dp))
            .padding(12.dp),
    )
}

// ------------------------------------------------------------------------------- rings

/**
 * Progress ring filling clockwise from 12 o'clock in the accent colour, with a soft glow on the
 * arc. [content] sits in the middle (a countdown, a readout).
 */
@Composable
fun ScRing(
    progress: Float,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 6.dp,
    color: Color = Sc.colors.accent,
    trackColor: Color = Sc.colors.raised,
    glow: Boolean = true,
    // Off for rings a clock drives several times a second: a new 400 ms tween every 200 ms never
    // settles, which kept the display redrawing at full rate for a whole session.
    animate: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val c = Sc.colors
    val target = progress.coerceIn(0f, 1f)
    val p = if (animate) animateFloatAsState(target, tween(400), label = "ring").value else target
    val glowColor = if (c.night) color.copy(alpha = 0.25f) else color.copy(alpha = 0.55f)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = strokeWidth.toPx()
            val inset = sw / 2f + (if (glow) sw else 0f)
            val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
            val topLeft = Offset(inset, inset)
            drawArc(trackColor, 0f, 360f, false, topLeft, arcSize, style = Stroke(sw))
            if (p > 0f) {
                if (glow) {
                    val paint = Paint().also { gp ->
                        val fp = gp.asFrameworkPaint()
                        fp.isAntiAlias = true
                        fp.style = android.graphics.Paint.Style.STROKE
                        fp.strokeWidth = sw * 1.6f
                        fp.strokeCap = android.graphics.Paint.Cap.ROUND
                        fp.color = glowColor.toArgb()
                        fp.maskFilter = BlurMaskFilter(sw * 1.4f, BlurMaskFilter.Blur.NORMAL)
                    }
                    drawIntoCanvas {
                        it.drawArc(topLeft.x, topLeft.y, topLeft.x + arcSize.width, topLeft.y + arcSize.height,
                            -90f, 360f * p, false, paint)
                    }
                }
                drawArc(
                    Brush.linearGradient(listOf(c.accent2, color), Offset(0f, 0f), Offset(size.width, size.height)),
                    -90f, 360f * p, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round),
                )
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
    // Seven labels share one phone width, so each stays at five letters or fewer.
    Still("Still"), Sky("Sky"), Focus("Focus"), Capture("Shoot"), Darks("Darks"),
    Stack("Stack"), Done("Done"),
}

/**
 * The seven-step strip at the top of every session screen: a circle per step on one line. Done
 * steps are green with a check; the current one is lit, numbered and ringed by how far it has got;
 * later ones are dim and numbered. The line between them is green as far as the session has got.
 */
@Composable
fun ScPipeline(
    current: SessionStep,
    progressInStep: Float,
    modifier: Modifier = Modifier,
) {
    val c = Sc.colors
    // The labels follow the system font size up to 115 %, then stop growing: past that seven of
    // them no longer fit one row, and the step's own title says the same thing in full size. The
    // numbers stay put, inside their circles.
    val fontScale = LocalDensity.current.fontScale
    val labelSize = (12f * minOf(fontScale, 1.15f) / fontScale).sp
    val numberStyle = ScType.caption.copy(fontSize = (11.5f / fontScale).sp, lineHeight = (14f / fontScale).sp,
        fontWeight = FontWeight.SemiBold)
    val progress = progressInStep.coerceIn(0f, 1f)
    val doneLine = c.success.copy(alpha = 0.55f)
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        SessionStep.entries.forEach { step ->
            val done = step.ordinal < current.ordinal
            val isCurrent = step == current
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val y = size.height / 2f
                        val sw = 2.dp.toPx()
                        if (step.ordinal > 0) {
                            drawLine(if (step.ordinal <= current.ordinal) doneLine else c.hairlineStrong,
                                Offset(0f, y), Offset(size.width / 2f, y), sw)
                        }
                        if (step.ordinal < SessionStep.entries.lastIndex) {
                            drawLine(if (done) doneLine else c.hairlineStrong,
                                Offset(size.width / 2f, y), Offset(size.width, y), sw)
                        }
                    }
                    when {
                        done -> Box(
                            Modifier.size(24.dp).clip(CircleShape).background(c.background)
                                .background(c.successFill).border(1.5.dp, c.success, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(ScIcons.Check, null, Modifier.size(15.dp), tint = c.success)
                        }
                        isCurrent -> Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.fillMaxSize()) {
                                val sw = 2.5.dp.toPx()
                                val topLeft = Offset(sw / 2f, sw / 2f)
                                val arc = Size(size.width - sw, size.height - sw)
                                drawCircle(c.background)
                                drawArc(c.hairlineStrong, 0f, 360f, false, topLeft, arc, style = Stroke(sw))
                                if (progress > 0f) {
                                    drawArc(c.accent2, -90f, 360f * progress, false, topLeft, arc,
                                        style = Stroke(sw, cap = StrokeCap.Round))
                                }
                            }
                            Box(
                                Modifier.size(22.dp).scGlow(c.accentGlow, radius = 8.dp).clip(CircleShape)
                                    .background(Brush.verticalGradient(c.primaryGradient))
                                    .border(1.dp, c.primaryBorder, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${step.ordinal + 1}", style = numberStyle, color = c.primaryText)
                            }
                        }
                        else -> Box(
                            Modifier.size(22.dp).clip(CircleShape).background(c.background)
                                .border(1.5.dp, c.hairlineStrong, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${step.ordinal + 1}", style = numberStyle.copy(fontWeight = FontWeight.Medium),
                                color = c.text3)
                        }
                    }
                }
                Text(
                    step.label,
                    style = ScType.caption.copy(
                        fontSize = labelSize,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (isCurrent) c.text else if (done) c.text2 else c.text3,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(top = 4.dp),
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
                .background(Brush.horizontalGradient(listOf(c.switchTrackOn, c.accent))))
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
                        TapeCell.Kept -> Modifier.background(c.accent.copy(alpha = 0.8f))
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

/**
 * The big round button: a glowing lavender ring around a soft lavender disk (red in night
 * vision), dimmed and without glow when it cannot fire.
 */
@Composable
fun ScShutter(
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 84.dp,
) {
    val c = Sc.colors
    Box(
        modifier
            .size(size)
            .scGlow(c.accentGlow, radius = 20.dp, enabled = enabled)
            .clip(CircleShape)
            .background(c.backgroundBottom)
            .border(
                (size.value / 24f).coerceAtLeast(3f).dp,
                if (enabled) Brush.verticalGradient(listOf(c.accent2, c.accent, c.switchTrackOn))
                else Brush.verticalGradient(listOf(c.text3.copy(alpha = 0.5f), c.text3.copy(alpha = 0.5f))),
                CircleShape,
            )
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size * 0.78f)
                .clip(CircleShape)
                .background(
                    if (enabled) Brush.verticalGradient(c.shutterDisk)
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
        if (icon != null) Icon(icon, null, Modifier.size(15.dp), tint = if (tone == Tone.Normal) c.accent else fg)
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
        Tone.Accent -> Triple(c.accentText, c.glass, c.selectedBorder.copy(alpha = 0.6f))
        Tone.Normal -> Triple(c.text, c.surface, c.hairlineStrong)
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(bg)
            .border(1.dp, border, RoundedCornerShape(18.dp))
            .padding(start = 14.dp, top = 11.dp, bottom = 11.dp, end = if (onDismiss != null) 4.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(19.dp), tint = if (tone == Tone.Accent || tone == Tone.Normal) c.accent else fg)
            Spacer(Modifier.width(11.dp))
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

/**
 * Dialogs and sheets get their own window, above the activity's night-vision dim and with its own
 * status-bar appearance (white icons on a dark app). Called inside one, this applies night vision
 * there too: dark bar icons, and the same dim drawn over [content].
 */
@Composable
fun NightWindow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Sc.colors
    val dim = LocalNightDim.current
    val view = LocalView.current
    LaunchedEffect(c.night) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = c.night
            isAppearanceLightNavigationBars = c.night
        }
    }
    // [modifier] clips: a card's rounded corners must not get the dim's square ones.
    Box(modifier) {
        content()
        if (c.night && dim > 0f) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = dim)))
        }
    }
}

/**
 * A dialog card in the design's style, dimming the screen behind it itself. Android turns the
 * status-bar icons white under any window that dims what is behind it, whatever that window asks
 * for - in night vision, bright icons over the red screen - so the window's own dim is switched
 * off and drawn here instead. Tapping outside the card dismisses, as before.
 */
@Composable
fun ScDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        LaunchedEffect(Unit) {
            (view.parent as? DialogWindowProvider)?.window
                ?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismissRequest)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            NightWindow(
                Modifier.widthIn(max = 520.dp).clip(ScShapes.card)
                    // A tap on the card is not a tap outside it.
                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
            ) { content() }
        }
    }
}

/** Confirmation dialog in the design's style (and night vision's, see [NightWindow]). */
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
    val c = Sc.colors
    ScDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(c.raised, c.surface)))
                .border(1.dp, c.hairlineStrong, ScShapes.card).padding(22.dp),
        ) {
            Text(title, style = ScType.titleSmall, color = c.text)
            Text(text, style = ScType.body, color = c.text2, modifier = Modifier.padding(top = 10.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                ScButton(dismissText, onDismiss, height = 46.dp)
                ScButton(confirmText, onConfirm,
                    kind = if (danger) ScButtonKind.Danger else ScButtonKind.Primary, height = 46.dp)
            }
        }
    }
}

/** A readout with a smaller unit: 22.5 s, 1600, 14.0 / 22.5 s. */
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
            Text(" $unit", style = ScType.dataLarge, color = unitColor,
                modifier = Modifier.padding(bottom = 3.dp), textAlign = TextAlign.Start)
        }
    }
}

/** A round icon badge: an icon on a softly lit disc, for the head of a card or an empty state. */
@Composable
fun ScIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = Sc.colors.accent,
    fill: Color = Sc.colors.selectedFill,
    border: Color = Sc.colors.selectedBorder.copy(alpha = 0.5f),
) {
    Box(
        modifier.size(size).clip(CircleShape).background(fill).border(1.dp, border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(size * 0.48f), tint = tint)
    }
}

/** A big empty-state: a glowing icon badge, a title and a line of text. */
@Composable
fun ScEmptyState(icon: ImageVector, title: String, text: String?, modifier: Modifier = Modifier) {
    val c = Sc.colors
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ScIconBadge(icon, Modifier.scGlow(c.accentGlow.copy(alpha = c.accentGlow.alpha * 0.5f), radius = 18.dp),
            size = 72.dp)
        Text(title, style = ScType.titleSmall, color = c.text, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 18.dp))
        if (text != null) {
            Text(text, style = ScType.body, color = c.text2, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}
