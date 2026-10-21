package com.wm.starcam.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.wm.starcam.R

/*
 * StarCam design system (design/handoff/design-tokens.json). Dark only, plus a red night-vision
 * variant of every token: in night vision nothing on screen may be blue, white or grey, because a
 * single bright glance undoes 20 minutes of dark adaptation.
 */

@Immutable
data class ScColors(
    val night: Boolean,
    val background: Color,
    val surface: Color,
    val raised: Color,
    val sunken: Color,
    val panel: Color,
    val tile: Color,
    val buttonSecondary: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val accentText: Color,
    val onSelected: Color,
    val selectedFill: Color,
    val selectedBorder: Color,
    val accentGlow: Color,
    val primaryFill: Color,
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
    /** Translucent panel laid over imagery (viewfinder, photos). */
    val glass: Color,
    val shieldText: Color,
    /** Shutter disk radial gradient, centre to edge. */
    val shutterDisk: List<Color>,
)

val DarkColors = ScColors(
    night = false,
    background = Color(0xFF0A0B10),
    surface = Color(0xFF12141C),
    raised = Color(0xFF1A1D28),
    sunken = Color(0xFF0F1118),
    panel = Color(0xFF0E1018),
    tile = Color(0xFF171A24),
    buttonSecondary = Color(0xFF161925),
    hairline = Color(0x1FAABEE6),        // rgba(170,190,230,0.12)
    hairlineStrong = Color(0x2EAABEE6),  // 0.18
    text = Color(0xFFE8ECF4),
    text2 = Color(0xFFAEB6C7),
    text3 = Color(0xFF8891A5),
    accent = Color(0xFF7FB4FF),
    accentText = Color(0xFFA9CCFF),
    onSelected = Color(0xFFCFE2FF),
    selectedFill = Color(0x297FB4FF),    // 0.16
    selectedBorder = Color(0x807FB4FF),  // 0.50
    accentGlow = Color(0x597FB4FF),      // 0.35
    primaryFill = Color(0xFF24426F),
    primaryBorder = Color(0x8C7FB4FF),   // 0.55
    primaryText = Color(0xFFE3EEFF),
    switchTrackOn = Color(0xFF2E5591),
    switchKnobOn = Color(0xFFE3EEFF),
    switchTrackOff = Color(0xFF232736),
    switchKnobOff = Color(0xFF8891A5),
    sliderTrack = Color(0xFF232736),
    warning = Color(0xFFFFC66E),
    warningFill = Color(0x1AFFC66E),
    warningBorder = Color(0x59FFC66E),
    error = Color(0xFFFF6E6E),
    errorFill = Color(0x1FFF6E6E),
    errorBorder = Color(0x59FF6E6E),
    glass = Color(0xC20C0E16),           // rgba(12,14,22,0.76)
    shieldText = Color(0xFF6E7790),
    shutterDisk = listOf(Color(0xFFDCE6F6), Color(0xFFB4C6E2), Color(0xFF93AACB)),
)

/** Night vision: red on near-black only. Tokens the handoff leaves out are derived in red. */
val NightColors = ScColors(
    night = true,
    background = Color(0xFF120404),
    surface = Color(0xFF1C0807),
    raised = Color(0xFF240A08),
    sunken = Color(0xFF170606),
    panel = Color(0xFF160505),
    tile = Color(0xFF1F0807),
    buttonSecondary = Color(0xFF1A0706),
    hairline = Color(0x2EFF5544),        // 0.18
    hairlineStrong = Color(0x40FF5544),
    text = Color(0xFFFF5544),
    text2 = Color(0xFFD9483A),
    text3 = Color(0xFFA8392E),
    accent = Color(0xFFFF5544),
    accentText = Color(0xFFFF6A5A),
    onSelected = Color(0xFFFF7563),
    selectedFill = Color(0x29FF5544),
    selectedBorder = Color(0x80FF5544),
    accentGlow = Color(0x4DFF5544),
    primaryFill = Color(0xFF3A0E09),
    primaryBorder = Color(0x8CFF5544),
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
    glass = Color(0xC7160505),
    shieldText = Color(0xFF6A1A12),
    shutterDisk = listOf(Color(0xFFC2392B), Color(0xFF8E2418), Color(0xFF6A170E)),
)

val LocalScColors = staticCompositionLocalOf { DarkColors }

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

// ------------------------------------------------------------------------------------- type

val Geist = FontFamily(
    Font(R.font.geist_light, FontWeight.Light),
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

/** Every number in the app (exposure, ISO, timers, counts) is set in this, tabular. */
val GeistMono = FontFamily(
    Font(R.font.geistmono_light, FontWeight.Light),
    Font(R.font.geistmono_regular, FontWeight.Normal),
    Font(R.font.geistmono_medium, FontWeight.Medium),
    Font(R.font.geistmono_semibold, FontWeight.SemiBold),
)

object ScType {
    private const val TNUM = "tnum"
    val display = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Light, fontSize = 64.sp,
        letterSpacing = (-0.03).em, fontFeatureSettings = TNUM, lineHeight = 64.sp)
    val countdown = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Light, fontSize = 80.sp,
        fontFeatureSettings = TNUM, lineHeight = 80.sp)
    val readout = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 32.sp,
        fontFeatureSettings = TNUM, lineHeight = 34.sp)
    val stat = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 22.sp,
        fontFeatureSettings = TNUM, lineHeight = 26.sp)
    val title = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 26.sp,
        letterSpacing = (-0.02).em, lineHeight = 30.sp)
    val titleSmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 22.sp,
        letterSpacing = (-0.02).em, lineHeight = 26.sp)
    val screenTitle = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
        lineHeight = 24.sp)
    val headline = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
        lineHeight = 21.sp)
    val body = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 15.sp,
        lineHeight = 22.sp)
    val bodySmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 13.sp,
        lineHeight = 19.sp)
    val button = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
        lineHeight = 18.sp)
    /** Uppercase section label, e.g. FRAME, TIME LEFT. Pass already-uppercased text. */
    val label = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
        letterSpacing = 0.08.em, lineHeight = 14.sp)
    val caption = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 12.sp,
        lineHeight = 16.sp)
    val data = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 12.sp,
        fontFeatureSettings = TNUM, lineHeight = 16.sp)
    val dataLarge = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 14.sp,
        fontFeatureSettings = TNUM, lineHeight = 18.sp)
}

object ScShapes {
    val chip = RoundedCornerShape(14.dp)
    val button = RoundedCornerShape(16.dp)
    val segmentItem = RoundedCornerShape(11.dp)
    val card = RoundedCornerShape(20.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val viewfinder = RoundedCornerShape(22.dp)
    val pill = CircleShape
}

/** Screen edge margin from the handoff. */
val ScreenMargin = 14.dp

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
