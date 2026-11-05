package com.wm.starcam.ui.theme

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.wm.starcam.R

/*
 * Stelli's design system: a deep purple night sky, lavender light and a soft violet glow, from
 * the app icon and the owner's main-screen design (Nov 2026). Dark only, plus a red night-vision
 * variant of every token: in night vision nothing on screen may be blue, white or grey, because a
 * single bright glance undoes 20 minutes of dark adaptation.
 */

@Immutable
data class ScColors(
    val night: Boolean,
    val background: Color,
    /** The screen's gradient, top to bottom: night sky above, a violet floor below. */
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val surface: Color,
    val raised: Color,
    val sunken: Color,
    val panel: Color,
    val tile: Color,
    val buttonSecondary: Color,
    /** A secondary button's lavender edge (the owner's designs draw it clearly). */
    val buttonBorder: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val accentText: Color,
    /** The pink-violet of the icon's horizon glow, for highlights. */
    val accent2: Color,
    val onSelected: Color,
    val selectedFill: Color,
    val selectedBorder: Color,
    val accentGlow: Color,
    val primaryFill: Color,
    /** The primary button's gradient, top to bottom. */
    val primaryGradient: List<Color>,
    val primaryBorder: Color,
    val primaryText: Color,
    val switchTrackOn: Color,
    val switchKnobOn: Color,
    val switchTrackOff: Color,
    val switchKnobOff: Color,
    val sliderTrack: Color,
    val warning: Color,
    val warningFill: Color,
    val warningBorder: Color,
    val error: Color,
    val errorFill: Color,
    val errorBorder: Color,
    /** Done: a finished step's check and circle in a session's step strip. */
    val success: Color,
    val successFill: Color,
    /** Translucent panel laid over imagery (viewfinder, photos). */
    val glass: Color,
    val shieldText: Color,
    /** Shutter disk gradient, top to bottom. */
    val shutterDisk: List<Color>,
    /** Two-tone icons: the line work, and the bright detail (a star, a pin, a dot). */
    val iconBase: Color,
    val iconAccent: Color,
)

val DarkColors = ScColors(
    night = false,
    background = Color(0xFF0E0820),
    backgroundTop = Color(0xFF0F0822),
    backgroundBottom = Color(0xFF1B0D35),
    surface = Color(0xFF1B1431),
    raised = Color(0xFF261D40),
    sunken = Color(0xFF140C27),
    panel = Color(0xFF150E2A),
    tile = Color(0xFF1F1737),
    buttonSecondary = Color(0xFF1E1535),
    buttonBorder = Color(0x8CB184FF),    // 55 %
    hairline = Color(0x29C9B2FF),        // lavender, 16 %
    hairlineStrong = Color(0x45C9B2FF),  // 27 %
    text = Color(0xFFF5F0FF),
    text2 = Color(0xFFC9BEE4),
    text3 = Color(0xFF948CB1),
    accent = Color(0xFFB88AFF),
    accentText = Color(0xFFD3B6FF),
    accent2 = Color(0xFFE7A0FF),
    onSelected = Color(0xFFF6EDFF),
    selectedFill = Color(0x618C55EA),    // 38 %
    selectedBorder = Color(0xDDB184FF),
    accentGlow = Color(0x8C9F5CFF),      // 55 %
    primaryFill = Color(0xFF55299F),
    primaryGradient = listOf(Color(0xFF6B37C6), Color(0xFF45208A)),
    primaryBorder = Color(0xFFB993FF),
    primaryText = Color(0xFFF8F2FF),
    switchTrackOn = Color(0xFF8656E2),
    switchKnobOn = Color(0xFFF8F2FF),
    switchTrackOff = Color(0xFF2B2244),
    switchKnobOff = Color(0xFF968DB3),
    sliderTrack = Color(0xFF2D2448),
    warning = Color(0xFFFFC870),
    warningFill = Color(0x1FFFC870),
    warningBorder = Color(0x66FFC870),
    error = Color(0xFFFF7E96),
    errorFill = Color(0x24FF7E96),
    errorBorder = Color(0x6BFF7E96),
    success = Color(0xFF5FE0A4),
    successFill = Color(0x2E5FE0A4),     // 18 %
    glass = Color(0xC4130A28),           // dark violet, 77 %
    shieldText = Color(0xFF6F6690),
    shutterDisk = listOf(Color(0xFFF1E9FF), Color(0xFFD9C8FC), Color(0xFFB99CF0)),
    iconBase = Color(0xFFE6D9FB),
    iconAccent = Color(0xFFC98DFF),
)

/** Night vision: red on near-black only. */
val NightColors = ScColors(
    night = true,
    background = Color(0xFF120404),
    backgroundTop = Color(0xFF110303),
    backgroundBottom = Color(0xFF1C0605),
    surface = Color(0xFF1C0807),
    raised = Color(0xFF260B09),
    sunken = Color(0xFF170606),
    panel = Color(0xFF160505),
    tile = Color(0xFF1F0807),
    buttonSecondary = Color(0xFF1C0807),
    // At night no brighter than the other edges.
    buttonBorder = Color(0x47FF5544),
    hairline = Color(0x2EFF5544),        // 0.18
    hairlineStrong = Color(0x47FF5544),
    text = Color(0xFFFF5544),
    text2 = Color(0xFFD9483A),
    text3 = Color(0xFFA8392E),
    accent = Color(0xFFFF5544),
    accentText = Color(0xFFFF6A5A),
    accent2 = Color(0xFFFF7A5C),
    onSelected = Color(0xFFFF7563),
    selectedFill = Color(0x33FF5544),
    selectedBorder = Color(0x99FF5544),
    accentGlow = Color(0x40FF4433),
    primaryFill = Color(0xFF3A0E09),
    primaryGradient = listOf(Color(0xFF4A120B), Color(0xFF320A06)),
    primaryBorder = Color(0xA6FF5544),
    primaryText = Color(0xFFFF6A5A),
    switchTrackOn = Color(0xFF7A1C12),
    switchKnobOn = Color(0xFFFF5544),
    switchTrackOff = Color(0xFF2A0B08),
    switchKnobOff = Color(0xFFA8392E),
    sliderTrack = Color(0xFF3A0F0B),
    warning = Color(0xFFFF7B5C),
    warningFill = Color(0x1AFF7B5C),
    warningBorder = Color(0x59FF7B5C),
    error = Color(0xFFFF5544),
    errorFill = Color(0x26FF5544),
    errorBorder = Color(0x73FF5544),
    // No green in night vision: done is told by the check alone.
    success = Color(0xFFFF6A5A),
    successFill = Color(0x26FF5544),
    glass = Color(0xC7160505),
    shieldText = Color(0xFF6A1A12),
    shutterDisk = listOf(Color(0xFFC83C2C), Color(0xFF9A2A1C), Color(0xFF6A170E)),
    iconBase = Color(0xFFD9483A),
    iconAccent = Color(0xFFFF5544),
)

val LocalScColors = staticCompositionLocalOf { DarkColors }

/**
 * Night vision's extra dimming (0 = none). The activity draws it over its own window; dialogs and
 * sheets are separate windows above that, so they read this and draw it themselves.
 */
val LocalNightDim = staticCompositionLocalOf { 0f }

/**
 * Night vision over a live camera preview, which Compose cannot recolour (the camera draws into
 * its own surface): a dark red veil that lets only about a fifth of the scene's own colour through.
 */
val NightPreviewTint = Color(0xCC2A0604)

/** Design-system access point: `Sc.colors.text`, `Sc.type.body`, `Sc.shapes.card`. */
object Sc {
    val colors: ScColors
        @Composable get() = LocalScColors.current
    val type = ScType
    val shapes = ScShapes
}

/**
 * Photos and live previews in night vision: grayscale, then multiplied with #FF3B2A, so even an
 * image of the Milky Way stays red-only.
 */
val NightImageFilter: ColorFilter = run {
    val r = 0xFF / 255f
    val g = 0x3B / 255f
    val b = 0x2A / 255f
    ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                0.299f * r, 0.587f * r, 0.114f * r, 0f, 0f,
                0.299f * g, 0.587f * g, 0.114f * g, 0f, 0f,
                0.299f * b, 0.587f * b, 0.114f * b, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
}

/** The filter every photo/preview Image should use: null in normal mode, red in night vision. */
val scImageFilter: ColorFilter?
    @Composable get() = if (LocalScColors.current.night) NightImageFilter else null

// ------------------------------------------------------------------------------ background

/** The app's background: the night-sky gradient, deep violet at the bottom. */
fun Modifier.scBackground(): Modifier = composed {
    val c = Sc.colors
    background(Brush.verticalGradient(listOf(c.backgroundTop, c.background, c.backgroundBottom)))
}

/**
 * A soft glow behind a control (the design's lit edges): a blurred shape of [color] around the
 * bounds. Drawn outside the bounds, so the parent must not clip it. Half strength in night vision.
 */
fun Modifier.scGlow(
    color: Color,
    radius: Dp = 12.dp,
    cornerRadius: Dp = 999.dp,
    enabled: Boolean = true,
): Modifier = if (!enabled) this else composed {
    val night = Sc.colors.night
    drawBehind {
        val blur = radius.toPx()
        if (blur <= 0f) return@drawBehind
        val paint = Paint().also { p ->
            val fp = p.asFrameworkPaint()
            fp.isAntiAlias = true
            fp.color = (if (night) color.copy(alpha = color.alpha * 0.5f) else color).toArgb()
            fp.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        val r = minOf(cornerRadius.toPx(), size.minDimension / 2f)
        drawIntoCanvas { it.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint) }
    }
}

// ------------------------------------------------------------------------------------- type

/** Noto Sans, one variable file (weights 100-900), cut to the Latin and Greek the app uses. */
val NotoSans = FontFamily(
    notoFont(FontWeight.Light),
    notoFont(FontWeight.Normal),
    notoFont(FontWeight.Medium),
    notoFont(FontWeight.SemiBold),
    notoFont(FontWeight.Bold),
)

@OptIn(ExperimentalTextApi::class)
private fun notoFont(weight: FontWeight) = Font(
    R.font.noto_sans, weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** The same face for drawing on a Canvas (the sky labels): Noto Sans at [weight]. */
fun notoTypeface(context: Context, weight: Int): Typeface = runCatching {
    val font = android.graphics.fonts.Font.Builder(context.resources, R.font.noto_sans)
        .setFontVariationSettings("'wght' $weight")
        .setWeight(weight)
        .build()
    Typeface.CustomFallbackBuilder(android.graphics.fonts.FontFamily.Builder(font).build())
        .setSystemFallback("sans-serif")
        .build()
}.getOrElse { Typeface.create(Typeface.DEFAULT, weight, false) }

object ScType {
    /** Tabular figures: every number in the app keeps its width as it ticks. */
    private const val TNUM = "tnum"
    val display = TextStyle(fontFamily = NotoSans, fontWeight = FontWeight.Light, fontSize = 64.sp,
        letterSpacing = (-0.03).em, fontFeatureSettings = TNUM, lineHeight = 66.sp)
    val countdown = TextStyle(fontFamily = NotoSans, fontWeight = FontWeight.Light, fontSize = 80.sp,
        letterSpacing = (-0.02).em, fontFeatureSettings = TNUM, lineHeight = 82.sp)
    val readout = TextStyle(fontFamily = NotoSans, fontWeight = FontWeight.Normal, fontSize = 32.sp,
        fontFeatureSettings = TNUM, lineHeight = 36.sp)
    val stat = TextStyle(fontFamily = NotoSans, fontWeight = FontWeight.Medium, fontSize = 22.sp,
        fontFeatureSettings = TNUM, lineHeight = 27.sp)
    val title = TextStyle(fontFamily = NotoSans, fontWeight = FontWeight.SemiBold, fontSize = 26.sp,
        letterSpacing = (-0.01).em, lineHeight = 32.sp)
    val titleSmall = TextStyle(fontFamily = NotoSans, fontWeight = FontWeight.SemiBold, fontSize = 21.sp,
        letterSpacing = (-0.01).em, lineHeight = 27.sp)
    val screenTitle = TextStyle(fontFamily = NotoSans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
        lineHeight = 25.sp)
    val headline = TextStyle(fontFamily = NotoSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
        lineHeight = 22.sp)
    val body = TextStyle(fontFamily = NotoSans, fontWeight = FontWeight.Normal, fontSize = 15.sp,
        lineHeight = 22.sp)
    val bodySmall = TextStyle(fontFamily = NotoSans, fontWeight = FontWeight.Normal, fontSize = 13.sp,
        lineHeight = 19.sp)
    val button = TextStyle(fontFamily = NotoSans, fontWeight = FontWeight.Medium, fontSize = 15.sp,
        lineHeight = 19.sp)
    /** Uppercase section label, e.g. TONIGHT, TIME LEFT. Pass already-uppercased text. */
    val label = TextStyle(fontFamily = NotoSans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
        letterSpacing = 0.12.em, lineHeight = 15.sp)
    val caption = TextStyle(fontFamily = NotoSans, fontWeight = FontWeight.Normal, fontSize = 12.sp,
        lineHeight = 16.sp)
    val data = TextStyle(fontFamily = NotoSans, fontWeight = FontWeight.Normal, fontSize = 12.sp,
        fontFeatureSettings = TNUM, lineHeight = 16.sp)
    val dataLarge = TextStyle(fontFamily = NotoSans, fontWeight = FontWeight.Normal, fontSize = 14.sp,
        fontFeatureSettings = TNUM, lineHeight = 19.sp)
}

object ScShapes {
    val chip = RoundedCornerShape(16.dp)
    /** Buttons are pills, as in the design. */
    val button = CircleShape
    /** Square icon buttons (the gear, the sky view): softly rounded. */
    val iconButton = RoundedCornerShape(16.dp)
    val segmentItem = RoundedCornerShape(14.dp)
    /** A tile or a row inside a card or sheet. */
    val tile = RoundedCornerShape(18.dp)
    val card = RoundedCornerShape(22.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val viewfinder = RoundedCornerShape(22.dp)
    val pill = CircleShape
}

/** Screen edge margin. */
val ScreenMargin = 16.dp

// Kept for the few older call sites that still name these directly.
val WarnAmber = DarkColors.warning
val ErrorRed = DarkColors.error

@Composable
fun StarCamTheme(nightMode: Boolean, content: @Composable () -> Unit) {
    val c = if (nightMode) NightColors else DarkColors
    // Material components that remain (dialogs, sheets, text fields) read the same tokens.
    val scheme = darkColorScheme(
        primary = c.accent,
        onPrimary = c.background,
        primaryContainer = c.primaryFill,
        onPrimaryContainer = c.primaryText,
        secondary = c.text2,
        onSecondary = c.background,
        background = c.background,
        onBackground = c.text,
        surface = c.surface,
        onSurface = c.text,
        surfaceVariant = c.raised,
        onSurfaceVariant = c.text2,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.raised,
        surfaceContainerHighest = c.raised,
        surfaceContainerLow = c.surface,
        surfaceContainerLowest = c.background,
        outline = c.hairlineStrong,
        outlineVariant = c.hairline,
        error = c.error,
        onError = c.background,
        scrim = Color(0xCC000000),
    )
    val typography = Typography(
        displayLarge = ScType.display,
        headlineLarge = ScType.title,
        headlineMedium = ScType.titleSmall,
        titleLarge = ScType.screenTitle,
        titleMedium = ScType.headline,
        titleSmall = ScType.button,
        bodyLarge = ScType.body,
        bodyMedium = ScType.body,
        bodySmall = ScType.bodySmall,
        labelLarge = ScType.button,
        labelMedium = ScType.caption,
        labelSmall = ScType.data,
    )
    CompositionLocalProvider(LocalScColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
