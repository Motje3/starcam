package com.wm.starcam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.components.ScNotice
import com.wm.starcam.ui.components.ScScreen
import com.wm.starcam.ui.components.Tone
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScType

private class IntroPage(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val tipIcon: ImageVector,
    val tip: String,
)

private val IntroPages = listOf(
    IntroPage(
        ScIcons.Tripod, "Use a tripod",
        "Long exposures need the phone perfectly still. Any tripod works — even a steady rock or a " +
            "bag of rice. StarCam waits until the phone is still before it starts.",
        ScIcons.Tripod, "A phone clamp costs less than a pizza.",
    ),
    IntroPage(
        ScIcons.Astro, "Press once, step back",
        "One button does it all: it reads the sky, focuses on the stars and shoots. Touching the " +
            "phone shakes it, so let it work and glance at the big numbers from a distance.",
        ScIcons.Timer, "An astro photo takes a few minutes; trails and meteor watches run longer.",
    ),
    IntroPage(
        ScIcons.DarkFrame, "Cover the lens when asked",
        "At the end, StarCam asks for a few frames with the lens covered. They find and remove hot " +
            "pixels. A lens cap or a dark cloth works best. If you miss it, it skips after 60 seconds.",
        ScIcons.Night, "Night vision turns the whole app red, to keep your eyes dark-adapted.",
    ),
    IntroPage(
        ScIcons.Moon, "Plan the night, finish the photo",
        "The Tonight chip on the viewfinder tells you when it gets properly dark, when the Moon is out " +
            "of the way and where the Milky Way stands. Afterwards, Edit brightens the sky, the " +
            "landscape or the colours of any photo - the original stays.",
        ScIcons.Compare, "\"1 frame vs stacked\" shows what the stacking did. Share that one.",
    ),
)

/** Three first-launch cards. Skippable, and replayable from Settings › Interface. */
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
                "StarCam needs the camera to take long exposures of the sky. Your photos stay on " +
                    "your phone — nothing is uploaded.",
                style = ScType.body, color = c.text2,
            )
            if (deniedForever) {
                ScNotice(
                    "Camera access is turned off for StarCam. Turn it on in Settings › Apps › " +
                        "StarCam › Permissions, then come back.",
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
        Icon(ScIcons.Astro, null, Modifier.size(20.dp), tint = c.accent)
        Spacer(Modifier.width(10.dp))
        Text("StarCam", style = ScType.headline, color = c.text, modifier = Modifier.weight(1f))
        if (onSkip != null) ScButton("Skip", onSkip, kind = ScButtonKind.Text, height = 44.dp)
    }
}

/** Concentric rings and a crosshair - an instrument's reticle - around one large icon. */
@Composable
private fun IntroArt(icon: ImageVector) {
    val c = Sc.colors
    val ring = c.hairline
    val faint = c.hairline.copy(alpha = c.hairline.alpha * 0.6f)
    Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f
            drawCircle(c.surface, r, center)
            listOf(1f, 0.74f, 0.48f).forEach { f -> drawCircle(ring, r * f, center, style = Stroke(1.dp.toPx())) }
            drawLine(faint, Offset(center.x - r, center.y), Offset(center.x + r, center.y), 1.dp.toPx())
            drawLine(faint, Offset(center.x, center.y - r), Offset(center.x, center.y + r), 1.dp.toPx())
            // A few fixed "stars".
            listOf(0.72f to 0.22f, 0.26f to 0.68f, 0.81f to 0.63f, 0.33f to 0.27f).forEach { (x, y) ->
                drawCircle(c.text.copy(alpha = 0.5f), 1.6.dp.toPx(), Offset(size.width * x, size.height * y))
            }
        }
        Box(
            Modifier.size(120.dp).clip(CircleShape).background(c.raised).background(c.selectedFill)
                .border(1.dp, c.selectedBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(64.dp), tint = c.accent)
        }
    }
}
