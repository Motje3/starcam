package com.wm.starcam.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import com.wm.starcam.R
import com.wm.starcam.ui.components.ScNotice
import com.wm.starcam.ui.components.Tone
import com.wm.starcam.ui.theme.NotoSans
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.ui.theme.scGlow
import com.wm.starcam.ui.theme.scImageFilter
import kotlin.math.roundToInt

/*
 * The first-launch intro and the camera question, built from the owner's five finished screens
 * (design/STYLE.md, "The intro"). The pictures are those screens with their words painted out; the
 * words, buttons and labels are drawn here, at the design's own numbers: it is one page 360 dp wide
 * and 800 tall, and every size below is in its units.
 */

private const val CANVAS_WIDTH = 360f
private const val CANVAS_HEIGHT = 800f

/** How much further the picture may reach behind the words before it is moved up. */
private const val ART_LEEWAY = 14f

// The design's colours. In night vision they all go through [night].
private val IntroFloor = Color(0xFF140A2E)
private val IntroWhite = Color(0xFFF7F6FA)
private val IntroBody = Color(0xFFC0A1F2)
private val IntroTipText = Color(0xFFC9A9EE)
private val IntroTipIcon = Color(0xFFC79CF3)
private val IntroSkip = Color(0xFFD2ABF8)
private val IntroDotOn = Color(0xFFBD87F9)
private val IntroDotOff = Color(0xFF463170)

// Under the button, the button, the dots and the gap over them, bottom to top. The dots and the
// button are the same on every page of the intro, and do not move while its pages change.
private val UnderButton = 25.5.dp
private val ButtonHeight = 51.dp
private val DotsGap = 17.5.dp
private val DotsHeight = 7.dp

/**
 * The design is 800 dp tall; on a shorter screen (most 20:9 phones are, a little) the footer's gaps
 * give way, so that the picture and the words stay where the design has them.
 */
private val LocalIntroSqueeze = compositionLocalOf { 0.dp }

/** How much of the picture's top, in the design's units, is off the screen. */
private val LocalPictureCropped = compositionLocalOf { 0f }

/** Everything about one page, in the design's units and as measured from its finished screen. */
private class IntroPage(
    @DrawableRes val art: Int,
    /** The picture's height (it is always 360 wide). */
    val artHeight: Float,
    /** How far the picture reaches down behind the words, past the top of the headline. */
    val artReach: Float,
    val title: String,
    /** Broken where the design breaks it. */
    val body: String,
    val tip: String,
    val tipIcon: ImageVector,
    val tipIconSize: Float,
    /** The tip's icon stands in this much, centred; the words start after it. */
    val tipIconRoom: Float = 41f,
    val tipEnd: Float = 14.5f,
    val titleSize: Float,
    val titleLine: Float = 33f,
    val titleStart: Float = 29f,
    val titleGap: Float = 0.7f,
    val bodySize: Float = 15f,
    val bodyLine: Float = 19.5f,
    val bodyStart: Float = 30.4f,
    val tipSize: Float,
    val tipGap: Float = 15.1f,
    val tipStart: Float = 27.5f,
    /** The gap between the tip and what the footer holds. */
    val footerGap: Dp,
    val underButton: Dp = UnderButton,
    val hasDots: Boolean = true,
    /** The labels standing on the picture. They are part of it: they scale and move with it. */
    val onPicture: (@Composable () -> Unit)? = null,
)

private val IntroPages = listOf(
    IntroPage(
        R.drawable.intro_1, artHeight = 553.33f, artReach = 74.1f,
        title = "A steady phone.\nA sky full of stars.",
        body = "Set your phone on a tripod or a sturdy\nsurface. Stelli waits until it\u2019s still before\ncapturing the night.",
        tip = "No tripod? A solid ledge works too.",
        tipIcon = ScIcons.IntroTripod, tipIconSize = 19.6f, tipIconRoom = 36.2f, tipEnd = 18f,
        titleSize = 30.7f, titleLine = 35.5f, titleGap = 4.7f,
        bodySize = 14.8f, bodyLine = 20f, bodyStart = 28.9f,
        tipSize = 12.6f, footerGap = 10.5.dp,
        onPicture = { StabilityLabel() },
    ),
    IntroPage(
        R.drawable.intro_2, artHeight = 546.67f, artReach = 64.8f,
        title = "One tap.\nStelli does the rest.",
        body = "Stelli finds focus, captures the frames and\nstacks them for you. Just step back and\nlet it work.",
        tip = "Stars take minutes. Trails take longer.",
        tipIcon = ScIcons.Clock, tipIconSize = 20.7f,
        titleSize = 29.35f, titleLine = 31.7f, titleGap = 0f,
        bodySize = 15.4f, bodyLine = 18.5f,
        tipSize = 12.6f, tipGap = 8.6f, footerGap = 29.dp,
        onPicture = { StepStrip() },
    ),
    IntroPage(
        R.drawable.intro_3, artHeight = 546.67f, artReach = 65.8f,
        title = "Cover the lens.\nGet cleaner stars.",
        body = "When Stelli asks, cover the camera for a\nfew dark frames. They help remove\nunwanted bright pixels from your photo.",
        tip = "No lens cap? A dark cloth works too.",
        tipIcon = ScIcons.Cloth, tipIconSize = 24f,
        titleSize = 29.85f,
        bodySize = 14.98f, bodyLine = 19.2f,
        tipSize = 12.46f, tipGap = 12.6f, footerGap = 21.dp,
        onPicture = { DarkFramesLabel() },
    ),
    IntroPage(
        R.drawable.intro_4, artHeight = 540f, artReach = 64.1f,
        title = "Plan the night.\nPerfect the shot.",
        body = "Find darker skies, follow the Moon and\nMilky Way, then bring out the best in your\nphotos. Your originals stay untouched.",
        tip = "Compare single frames with your final stack.",
        tipIcon = ScIcons.CompareFrames, tipIconSize = 24.5f,
        titleSize = 29.95f, titleLine = 31.3f,
        bodySize = 15.2f, bodyStart = 30.6f,
        tipSize = 12.14f, tipGap = 12.1f, footerGap = 26.5.dp,
    ),
)

private val PermissionPage = IntroPage(
    R.drawable.intro_5, artHeight = 540f, artReach = 31.2f,
    title = "Your camera.\nA sky full of possibilities.",
    body = "Allow camera access to capture long\nexposures of the night sky. Your photos stay\non your phone \u2014 nothing is uploaded.",
    tip = "Private by design \u00b7 Works offline",
    tipIcon = ScIcons.ShieldCheck, tipIconSize = 23f,
    titleSize = 26.3f, titleLine = 30.3f, titleStart = 22.5f, titleGap = 4.8f,
    bodySize = 14.77f, bodyLine = 19.2f, bodyStart = 25.4f,
    tipSize = 12.77f, tipGap = 11.8f, tipStart = 24.7f,
    footerGap = 22.8.dp, underButton = 22.5.dp, hasDots = false,
    onPicture = { CameraAccessLabel() },
)

/** The first-launch pages. Skippable, and replayable from Settings › Interface. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = page > 0) { page-- }
    IntroCanvas {
        Crossfade(page, Modifier.fillMaxSize(), label = "intro") { p -> IntroPageContent(IntroPages[p]) }
        BrandRow(onSkip = onDone)
        Footer(
            dots = { Dots(page) },
            text = if (page == IntroPages.lastIndex) "Got it" else "Next",
            onClick = { if (page < IntroPages.lastIndex) page++ else onDone() },
        )
    }
}

/**
 * Asked for in context, after the intro, instead of a bare system dialog on first start. When the
 * system will no longer show the dialog (denied twice), the way to Settings is offered instead.
 */
@Composable
fun PermissionScreen(deniedForever: Boolean, onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    IntroCanvas {
        IntroPageContent(PermissionPage) {
            if (deniedForever) {
                ScNotice(
                    "Camera access is turned off for Stelli. Turn it on in Settings › Apps › " +
                        "Stelli › Permissions, then come back.",
                    tone = Tone.Warning, icon = ScIcons.Warning,
                    modifier = Modifier.padding(end = 24.dp),
                )
            } else {
                TipPill(PermissionPage)
            }
        }
        BrandRow(onSkip = null)
        Footer(
            dots = null,
            text = if (deniedForever) "Open app settings" else "Allow camera",
            icon = ScIcons.IntroCamera,
            onClick = if (deniedForever) onOpenSettings else onAllow,
            underButton = PermissionPage.underButton,
        )
    }
}

/**
 * The floor, and the design's 360 dp mapped onto the phone's width: everything inside scales with
 * it, so the words wrap where the design wraps them. A very large system font is held back, so a
 * page still fits on the screen.
 */
@Composable
private fun IntroCanvas(content: @Composable BoxScope.() -> Unit) {
    val c = Sc.colors
    BoxWithConstraints(Modifier.fillMaxSize().background(if (c.night) c.background else IntroFloor)) {
        val density = LocalDensity.current
        val scale = (maxWidth.value / CANVAS_WIDTH).coerceIn(0.85f, 1.25f)
        val scaled = remember(density, scale) {
            Density(density.density * scale, density.fontScale.coerceAtMost(1.3f))
        }
        val squeeze = (CANVAS_HEIGHT - maxHeight.value / scale).coerceIn(0f, 16f).dp
        CompositionLocalProvider(LocalDensity provides scaled, LocalIntroSqueeze provides squeeze) {
            Box(Modifier.fillMaxSize(), content = content)
        }
    }
}

/**
 * The picture and the words of one page. The words stand above the footer; the picture ends a
 * set distance below the top of the words, so however tall the screen and the words are, the
 * two meet as they do in the design. It is scaled to fill what is left, from the bottom up.
 */
@Composable
private fun IntroPageContent(
    page: IntroPage,
    tip: @Composable () -> Unit = { TipPill(page) },
) {
    val squeeze = LocalIntroSqueeze.current
    val footer = page.footerGap + (if (page.hasDots) DotsHeight + DotsGap else 0.dp) + ButtonHeight + page.underButton - squeeze
    val bottom = footer + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            IntroPicture(page)
            IntroWordsBlock(page, tip)
        },
    ) { measurables, constraints ->
        val words = measurables[1].measure(Constraints(maxWidth = constraints.maxWidth))
        val wordsTop = constraints.maxHeight - bottom.roundToPx() - words.height
        // A screen a little shorter than the design's leaves the picture where it is, top at the
        // top, and lets it reach a little further behind the words. Beyond that it moves up.
        val nominal = wordsTop + page.artReach.dp.roundToPx()
        val full = (page.artHeight.dp.toPx() * constraints.maxWidth / CANVAS_WIDTH.dp.toPx()).roundToInt()
        val artHeight = maxOf(nominal, minOf(full, nominal + ART_LEEWAY.dp.roundToPx())).coerceIn(1, constraints.maxHeight)
        val art = measurables[0].measure(Constraints.fixed(constraints.maxWidth, artHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            art.place(0, 0)
            words.place((constraints.maxWidth - words.width) / 2, wordsTop)
        }
    }
}

/** The picture, and what stands on it, laid out in the design's units and scaled as one. */
@Composable
private fun IntroPicture(page: IntroPage) {
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().clearAndSetSemantics { }) {
        val outer = LocalDensity.current
        // The system font size must not reach the labels: they have to fit their places on the picture.
        val flat = remember(outer) { Density(outer.density, 1f) }
        val scale = maxOf(maxWidth.value / CANVAS_WIDTH, maxHeight.value / page.artHeight)
        val cropped = page.artHeight - maxHeight.value / scale
        Box(
            Modifier.layout { measurable, c ->
                val w = (CANVAS_WIDTH * density).roundToInt()
                val h = (page.artHeight * density).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(c.maxWidth, c.maxHeight) {
                    placeable.placeWithLayer(0, 0) {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = scale
                        scaleY = scale
                        translationX = (c.maxWidth - w * scale) / 2f
                        translationY = c.maxHeight - h * scale
                    }
                }
            },
        ) {
            CompositionLocalProvider(LocalDensity provides flat, LocalPictureCropped provides cropped) {
                Image(
                    painterResource(page.art), null, Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds, colorFilter = scImageFilter,
                )
                page.onPicture?.invoke()
            }
        }
    }
}

/** The headline, the text and the tip (or what replaces it). */
@Composable
private fun IntroWordsBlock(page: IntroPage, tip: @Composable () -> Unit) {
    // A large system font wraps by itself; the design's line breaks would only get in its way.
    val body = if (LocalDensity.current.fontScale > 1.05f) page.body.replace('\n', ' ') else page.body
    Column(Modifier.width(CANVAS_WIDTH.dp)) {
        Text(
            page.title,
            style = TextStyle(
                fontFamily = NotoSans, fontWeight = FontWeight.Bold,
                fontSize = page.titleSize.sp, lineHeight = page.titleLine.sp,
                textMotion = TextMotion.Animated,
            ),
            color = IntroWhite.night(),
            modifier = Modifier.padding(start = page.titleStart.dp, end = 4.dp),
        )
        Text(
            body,
            style = ScType.body.copy(
                fontSize = page.bodySize.sp, lineHeight = page.bodyLine.sp, textMotion = TextMotion.Animated,
            ),
            color = IntroBody.night(),
            modifier = Modifier.padding(start = page.bodyStart.dp, end = 4.dp, top = page.titleGap.dp),
        )
        Spacer(Modifier.height(page.tipGap.dp))
        Box(Modifier.padding(start = page.tipStart.dp, end = 12.dp)) { tip() }
    }
}

@Composable
private fun TipPill(page: IntroPage) {
    val rim = Color(0xFF473372).night()
    val ends = Color(0xFF3B2A5F).night()
    Row(
        Modifier.heightIn(min = 28.dp)
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF423069).night(),
                    0.16f to Color(0xFF362558).night(),
                    0.5f to Color(0xFF302152).night(),
                    0.84f to Color(0xFF342456).night(),
                    1f to Color(0xFF413067).night(),
                ),
                CircleShape,
            )
            .background(
                Brush.horizontalGradient(
                    0f to ends,
                    0.07f to Color.Transparent,
                    0.93f to Color.Transparent,
                    1f to ends,
                ),
                CircleShape,
            )
            .border(0.8.dp, rim, CircleShape)
            .padding(end = page.tipEnd.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The icons differ in size; none of them may make the pill taller than its words do.
        Box(Modifier.width(page.tipIconRoom.dp).height(16.dp), contentAlignment = Alignment.Center) {
            Icon(page.tipIcon, null, Modifier.requiredSize(page.tipIconSize.dp), tint = IntroTipIcon.night())
        }
        Text(
            page.tip,
            modifier = Modifier.padding(vertical = 4.dp),
            style = ScType.bodySmall.copy(fontSize = page.tipSize.sp, lineHeight = 16.sp, textMotion = TextMotion.Animated),
            color = IntroTipText.night(),
        )
    }
}

/** The app's icon and name, and Skip: over the sky, under the status bar. */
@Composable
private fun BoxScope.BrandRow(onSkip: (() -> Unit)?) {
    val c = Sc.colors
    // The design's status bar is 36 dp tall; a shorter one does not pull the row up.
    val top = max(WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), 36.dp)
    Row(
        Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = top).height(56.dp)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The app's own icon: the owner's artwork.
        Image(
            painterResource(R.drawable.ic_launcher_art), null,
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)),
            colorFilter = scImageFilter,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "Stelli", style = ScType.headline.copy(fontSize = 18.sp), color = IntroWhite.night(),
            modifier = Modifier.weight(1f),
        )
        if (onSkip != null) {
            Box(
                Modifier.height(44.dp).clip(CircleShape)
                    .clickable(onClick = onSkip, role = Role.Button)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Skip", style = ScType.button, color = (if (c.night) c.accentText else IntroSkip))
            }
        }
    }
}

/** The dots and the button: these stay where they are while the pages change. */
@Composable
private fun BoxScope.Footer(
    dots: (@Composable () -> Unit)?,
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    underButton: Dp = UnderButton,
) {
    val c = Sc.colors
    // The squeeze is shared out: most of it under the button, a little around the dots.
    val squeeze = LocalIntroSqueeze.current
    Column(
        Modifier.align(Alignment.BottomCenter).width(CANVAS_WIDTH.dp).navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        if (dots != null) {
            dots()
            Spacer(Modifier.height(DotsGap - squeeze * 0.15f))
        }
        Row(
            Modifier.fillMaxWidth().height(ButtonHeight)
                .scGlow(Color(0x99925EFF).night(), radius = 15.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF804EF2).night(), Color(0xFF6F3FD6).night(), Color(0xFF512BB2).night()),
                    ),
                )
                .border(1.dp, (if (c.night) c.primaryBorder else Color(0xFFC698FB)), CircleShape)
                .clickable(onClick = onClick, role = Role.Button),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(24.5.dp), tint = Color(0xFFF2EDFA).night())
                Spacer(Modifier.width(7.7.dp))
            }
            Text(
                text,
                style = ScType.button.copy(fontWeight = FontWeight.Bold, fontSize = 15.3.sp),
                color = Color(0xFFF2EDFA).night(),
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(underButton - squeeze * 0.75f))
    }
}

@Composable
private fun Dots(page: Int) {
    Row(Modifier.fillMaxWidth().height(DotsHeight), horizontalArrangement = Arrangement.Center) {
        IntroPages.indices.forEach { i ->
            Box(
                Modifier.padding(horizontal = 3.15.dp).height(DotsHeight).width(if (i == page) 22.dp else DotsHeight)
                    .clip(CircleShape).background((if (i == page) IntroDotOn else IntroDotOff).night()),
            )
        }
    }
}

// ------------------------------------------------------------------- what stands on the pictures

/**
 * A glass label, over the one in the picture: opaque and a little larger, so that one's edge stays
 * hidden and its glow still shows round this one. [shades] run top to bottom.
 */
@Composable
private fun PictureLabel(x: Float, y: Float, w: Float, h: Float, shades: List<Color>, ends: Color, rim: Color) {
    val stops = listOf(0f, 0.15f, 0.5f, 0.82f, 1f)
    val end = ends.night()
    Box(
        Modifier.absoluteOffset(x.dp, y.dp).size(w.dp, h.dp)
            .background(
                Brush.verticalGradient(*Array(stops.size) { stops[it] to shades[it].night() }),
                CircleShape,
            )
            .background(
                Brush.horizontalGradient(
                    0f to end,
                    0.13f to Color.Transparent,
                    0.87f to Color.Transparent,
                    1f to end,
                ),
                CircleShape,
            )
            .border(0.8.dp, rim.night(), CircleShape),
    )
}

@Composable
private fun PictureIcon(icon: ImageVector, centreX: Float, centreY: Float, size: Float, tint: Color) {
    Icon(
        icon, null,
        Modifier.absoluteOffset((centreX - size / 2).dp, (centreY - size / 2).dp).size(size.dp),
        tint = tint.night(),
    )
}

/** One line on the picture, its left edge (or centre) at [x] and its baseline at [baseline]. */
@Composable
private fun PictureText(
    text: String,
    x: Float,
    baseline: Float,
    size: Float,
    weight: FontWeight,
    color: Color,
    tracking: Float = 0f,
    centred: Boolean = false,
) {
    Text(
        text,
        style = TextStyle(
            fontFamily = NotoSans, fontWeight = weight, fontSize = size.sp, letterSpacing = tracking.em,
            textMotion = TextMotion.Animated,
        ),
        color = color.night(),
        maxLines = 1,
        modifier = Modifier.layout { measurable, c ->
            val p = measurable.measure(c.copy(minWidth = 0, minHeight = 0))
            val left = x.dp.toPx() - (if (centred) p.width / 2f else 0f)
            val top = baseline.dp.toPx() - p[FirstBaseline]
            layout(0, 0) { p.place(left.roundToInt(), top.roundToInt()) }
        },
    )
}

@Composable
private fun StabilityLabel() {
    PictureLabel(
        205.5f, 301.5f, 131f, 30f,
        listOf(Color(0xFF704DBA), Color(0xFF5F44A2), Color(0xFF43397C), Color(0xFF614A9E), Color(0xFF7F5DC3)),
        ends = Color(0xB07554B8), rim = Color(0x8C9B7BD8),
    )
    PictureIcon(ScIcons.ShieldCheck, 223.8f, 316.5f, 21.8f, Color(0xFFC79CF5))
    PictureText("Stability detected", 238.2f, 319.7f, 10.33f, FontWeight.Medium, Color(0xFFEAE3F3))
}

@Composable
private fun DarkFramesLabel() {
    PictureLabel(
        195.2f, 151.9f, 122f, 26.2f,
        listOf(Color(0xFF68518F), Color(0xFF443B6C), Color(0xFF2A294D), Color(0xFF3E3667), Color(0xFF6C5699)),
        ends = Color(0xA0654F90), rim = Color(0x8C8D74B8),
    )
    PictureIcon(ScIcons.Layers3, 213.2f, 164.8f, 28.6f, Color(0xFFCFAAEC))
    PictureText("DARK FRAMES", 229f, 168.2f, 10.3f, FontWeight.Medium, Color(0xFFE6DDF0), tracking = 0.095f)
}

@Composable
private fun CameraAccessLabel() {
    PictureLabel(
        205.2f, 193.9f, 137.3f, 26.6f,
        listOf(Color(0xFF7353A6), Color(0xFF5A4288), Color(0xFF3D2D67), Color(0xFF4D3779), Color(0xFF6E4AA0)),
        ends = Color(0xA06D529C), rim = Color(0x8C9173C0),
    )
    PictureIcon(ScIcons.IntroCamera, 225.8f, 206.7f, 24f, Color(0xFFE2C9F2))
    PictureText("CAMERA ACCESS", 243f, 210.2f, 9.9f, FontWeight.Medium, Color(0xFFE9E0F2), tracking = 0.088f)
}

private val StepCentres = listOf(64.83f, 122.4f, 180.25f, 237.9f, 295f)
private val StepLabels = listOf("1 Choose", "2 Steady", "3 Shoot", "4 Stack", "5 Ready")
private val StepLabelCentres = listOf(65.3f, 123f, 181.2f, 238.5f, 295.5f)
private const val STEP_Y = 104f

/** How a session goes, across the top of the second page. */
@Composable
private fun StepStrip() {
    // A short screen moves the picture up; the strip would run into the name at the top.
    if (LocalPictureCropped.current > 12f) return
    val ring = Color(0xFFDFBEE4).night()
    val line = Color(0xFFE5CBEC).night()
    Canvas(Modifier.size(CANVAS_WIDTH.dp, 140.dp)) {
        val y = STEP_Y.dp.toPx()
        StepCentres.forEach { cx ->
            drawCircle(ring, 12.9.dp.toPx(), Offset(cx.dp.toPx(), y), style = Stroke(1.15.dp.toPx()))
        }
        StepCentres.dropLast(1).forEach { cx ->
            drawLine(
                line,
                Offset((cx + 18.83f).dp.toPx(), y + 0.17.dp.toPx()),
                Offset((cx + 38.83f).dp.toPx(), y + 0.17.dp.toPx()),
                strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round,
            )
        }
    }
    val icons = listOf(ScIcons.StepList, ScIcons.StepTripod, ScIcons.StepAperture, ScIcons.Layers3, ScIcons.StepPhoto)
    PictureIcon(ScIcons.StepApertureRing, StepCentres[2], STEP_Y, 24f, Color(0xFFDDB8E8))
    icons.forEachIndexed { i, icon -> PictureIcon(icon, StepCentres[i], STEP_Y, 24f, Color(0xFFF0EEF2)) }
    StepLabels.forEachIndexed { i, label ->
        PictureText(label, StepLabelCentres[i], 128.8f, 9f, FontWeight.Normal, Color(0xFFDCC3E3), centred = true)
    }
}

/** A design colour as it may appear in night vision: its brightness, in red only. */
@Composable
private fun Color.night(): Color {
    if (!Sc.colors.night) return this
    val l = 0.299f * red + 0.587f * green + 0.114f * blue
    return Color(l, l * 0.23f, l * 0.16f, alpha)
}
