package com.wm.starcam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wm.starcam.R
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.components.ScNotice
import com.wm.starcam.ui.components.ScScreen
import com.wm.starcam.ui.components.Tone
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.ui.theme.scGlow
import com.wm.starcam.ui.theme.scImageFilter

private class IntroPage(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val tipIcon: ImageVector,
    val tip: String,
)

private val IntroPages = listOf(
    // A line or two each: the rest is learnt by doing, and explained where it happens.
    IntroPage(
        ScIcons.Tripod, "Use a tripod",
        "Long exposures need the phone perfectly still. Any tripod works, even a steady rock.",
        ScIcons.Tripod, "A phone clamp costs less than a pizza.",
    ),
    IntroPage(
        ScIcons.Astro, "Press once, step back",
        "One button reads the sky, focuses on the stars and shoots. Let it work.",
        ScIcons.Timer, "A photo takes a few minutes.",
    ),
    IntroPage(
        ScIcons.DarkFrame, "Cover the lens when asked",
        "A few frames with the lens covered remove hot pixels. A cap or a dark cloth works best.",
        ScIcons.Night, "Night vision turns the app red, for dark-adapted eyes.",
    ),
    IntroPage(
        ScIcons.Moon, "Plan the night, finish the photo",
        "Tonight says what is worth a shot and when it gets dark. Edit finishes the photo.",
        ScIcons.Compare, "Share \"1 frame vs stacked\": it shows what stacking does.",
    ),
)

/** The first-launch cards. Skippable, and replayable from Settings › Interface. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val c = Sc.colors
    var page by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = page > 0) { page-- }
    ScScreen {
        BrandRow(onSkip = onDone)
        Crossfade(page, Modifier.weight(1f), label = "intro") { p ->
            val pg = IntroPages[p]
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    IntroArt(pg.icon)
                }
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(pg.title, style = ScType.title.copy(fontSize = ScType.title.fontSize * 1.15f), color = c.text)
                    Text(pg.body, style = ScType.body, color = c.text2, modifier = Modifier.padding(top = 10.dp))
                    Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(pg.tipIcon, null, Modifier.size(18.dp), tint = c.text3)
                        Spacer(Modifier.width(10.dp))
                        Text(pg.tip, style = ScType.bodySmall, color = c.text3)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            IntroPages.indices.forEach { i ->
                Box(
                    Modifier.padding(horizontal = 3.dp).height(6.dp).width(if (i == page) 22.dp else 6.dp)
                        .clip(CircleShape).background(if (i == page) c.accent else c.hairlineStrong),
                )
            }
        }
        ScButton(
            if (page == IntroPages.lastIndex) "Got it" else "Next",
            onClick = { if (page < IntroPages.lastIndex) page++ else onDone() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            kind = ScButtonKind.Primary,
            height = 54.dp,
        )
    }
}

/**
 * Asked for in context, after the intro, instead of a bare system dialog on first start. When the
 * system will no longer show the dialog (denied twice), the way to Settings is offered instead.
 */
@Composable
fun PermissionScreen(deniedForever: Boolean, onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    val c = Sc.colors
    ScScreen {
        BrandRow(onSkip = null)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            IntroArt(ScIcons.Camera)
        }
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Allow camera access", style = ScType.title.copy(fontSize = ScType.title.fontSize * 1.15f), color = c.text)
            Text(
                "For long exposures of the sky. Your photos stay on your phone.",
                style = ScType.body, color = c.text2,
            )
            if (deniedForever) {
                ScNotice(
                    "Camera access is turned off for Stelli. Turn it on in Settings › Apps › " +
                        "Stelli › Permissions, then come back.",
                    tone = Tone.Warning, icon = ScIcons.Warning,
                )
            }
        }
        ScButton(
            if (deniedForever) "Open app settings" else "Allow camera",
            onClick = if (deniedForever) onOpenSettings else onAllow,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            kind = ScButtonKind.Primary,
            icon = ScIcons.Camera,
            height = 54.dp,
        )
    }
}

@Composable
private fun BrandRow(onSkip: (() -> Unit)?) {
    val c = Sc.colors
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The app's own icon: the owner's artwork.
        Image(
            painterResource(R.drawable.ic_launcher_art), null,
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)),
            colorFilter = scImageFilter,
        )
        Spacer(Modifier.width(12.dp))
        Text("Stelli", style = ScType.headline.copy(fontSize = 18.sp), color = c.text, modifier = Modifier.weight(1f))
        if (onSkip != null) ScButton("Skip", onSkip, kind = ScButtonKind.Text, height = 44.dp)
    }
}

/** Concentric rings and a crosshair - an instrument's reticle - around one large icon. */
@Composable
private fun IntroArt(icon: ImageVector) {
    val c = Sc.colors
    val ring = c.hairline
    val faint = c.hairline.copy(alpha = c.hairline.alpha * 0.6f)
    val glow = c.accent.copy(alpha = if (c.night) 0.08f else 0.16f)
    Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f
            drawCircle(c.surface, r, center)
            // A violet glow from the middle, like the icon's sky.
            drawCircle(Brush.radialGradient(listOf(glow, Color.Transparent), center, r), r, center)
            listOf(1f, 0.74f, 0.48f).forEach { f -> drawCircle(ring, r * f, center, style = Stroke(1.dp.toPx())) }
            drawLine(faint, Offset(center.x - r, center.y), Offset(center.x + r, center.y), 1.dp.toPx())
            drawLine(faint, Offset(center.x, center.y - r), Offset(center.x, center.y + r), 1.dp.toPx())
            // A few fixed "stars".
            listOf(0.72f to 0.22f, 0.26f to 0.68f, 0.81f to 0.63f, 0.33f to 0.27f).forEach { (x, y) ->
                drawCircle(c.text.copy(alpha = 0.5f), 1.6.dp.toPx(), Offset(size.width * x, size.height * y))
            }
        }
        Box(
            Modifier.size(120.dp).scGlow(c.accentGlow.copy(alpha = c.accentGlow.alpha * 0.6f), radius = 26.dp)
                .clip(CircleShape).background(c.raised)
                .background(Brush.verticalGradient(listOf(c.selectedFill, c.selectedFill.copy(alpha = c.selectedFill.alpha * 0.5f))))
                .border(1.5.dp, c.selectedBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(64.dp), tint = c.accent)
        }
    }
}
