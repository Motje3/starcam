package com.wm.starcam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Deep-space palette (PLAN.md §8). The app is dark-only by design — it's a night tool.
val SpaceBlack = Color(0xFF0A0B10)
val SpaceSurface = Color(0xFF12141C)
val SpaceSurfaceHigh = Color(0xFF1A1D28)
val StarWhite = Color(0xFFE8ECF4)
val AstroBlue = Color(0xFF7FB4FF)
val AstroBlueDim = Color(0xFF3D5E8C)
val WarnAmber = Color(0xFFFFC66E)
val ErrorRed = Color(0xFFFF6E6E)

// Night-vision palette: preserves dark adaptation.
val NightBlack = Color(0xFF120404)
val NightSurface = Color(0xFF1C0808)
val NightSurfaceHigh = Color(0xFF260C0C)
val NightRed = Color(0xFFFF5544)
val NightRedDim = Color(0xFF7A2A22)

private val DayScheme = darkColorScheme(
    primary = AstroBlue,
    onPrimary = SpaceBlack,
    secondary = AstroBlueDim,
    background = SpaceBlack,
    onBackground = StarWhite,
    surface = SpaceSurface,
    onSurface = StarWhite,
    surfaceVariant = SpaceSurfaceHigh,
    onSurfaceVariant = StarWhite.copy(alpha = 0.7f),
    error = ErrorRed,
)

private val NightScheme = darkColorScheme(
    primary = NightRed,
    onPrimary = NightBlack,
    secondary = NightRedDim,
    background = NightBlack,
    onBackground = NightRed,
    surface = NightSurface,
    onSurface = NightRed,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = NightRed.copy(alpha = 0.7f),
    error = NightRed,
)

private val StarCamTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 22.sp,
    ),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 0.5.sp,
    ),
)

@Composable
fun StarCamTheme(nightMode: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (nightMode) NightScheme else DayScheme,
        typography = StarCamTypography,
        content = content,
    )
}
