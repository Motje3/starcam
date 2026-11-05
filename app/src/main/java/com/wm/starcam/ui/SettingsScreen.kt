package com.wm.starcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wm.starcam.StarCamViewModel
import com.wm.starcam.camera.CamInfo
import com.wm.starcam.session.AppSettings
import com.wm.starcam.session.FocusMode
import com.wm.starcam.session.Mode
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScCard
import com.wm.starcam.ui.components.ScChevron
import com.wm.starcam.ui.components.ScChip
import com.wm.starcam.ui.components.ScConfirmDialog
import com.wm.starcam.ui.components.ScDialog
import com.wm.starcam.ui.components.ScLabel
import com.wm.starcam.ui.components.ScScreen
import com.wm.starcam.ui.components.ScSegmented
import com.wm.starcam.ui.components.ScIconBadge
import com.wm.starcam.ui.components.ScSettingRow
import com.wm.starcam.ui.components.ScSlider
import com.wm.starcam.ui.components.ScSwitch
import com.wm.starcam.ui.components.ScTopBar
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScShapes
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.ui.theme.ScreenMargin
import com.wm.starcam.util.LegalPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The settings' tabs. Each shows the few settings most people change; the rest wait under
 * [moreLabel] at the bottom of the tab, and every setting is explained behind its (i).
 */
enum class SettingsTab(
    val label: String,
    val blurb: String,
    val moreLabel: String = "More options",
    /** Said once the more-options part is open. */
    val moreNote: String? = null,
) {
    Exposure("Exposure", "Shutter, ISO and frames"),
    Focus("Focus", "Sharp stars"),
    Capture("Capture", "What gets saved"),
    Autopilot("Autopilot", "What Stars fixes by itself"),
    Stacking("Stacking", "How the frames are combined"),
    Look("Look", "How photos are toned"),
    Motion("Motion", "Stillness and session length"),
    Interface("Interface", "Night vision and aids"),
    Advanced(
        "Advanced", "Help, privacy and the app",
        moreLabel = "Troubleshooting",
        moreNote = "Camera switches for tracking down problems. Leave them at their defaults for normal " +
            "shooting; flip one at a time and run the camera test in Diagnostics to see what changes. " +
            "Everything is logged either way.",
    ),
}

/** Each tab's icon, on its chip and over its page. */
private fun tabIcon(t: SettingsTab) = when (t) {
    SettingsTab.Exposure -> ScIcons.Timer
    SettingsTab.Focus -> ScIcons.Focus
    SettingsTab.Capture -> ScIcons.Camera
    SettingsTab.Autopilot -> ScIcons.Autopilot
    SettingsTab.Stacking -> ScIcons.Stack
    SettingsTab.Look -> ScIcons.Photo
    SettingsTab.Motion -> ScIcons.Tripod
    SettingsTab.Interface -> ScIcons.Night
    SettingsTab.Advanced -> ScIcons.Settings
}

private enum class SettingsAction { Diagnostics, Probe, SelfTest, Intro, Reset, Licences, Privacy, Terms }

/*
 * Every setting is described once, as data: its tab, label, control, the line under its name
 * (only where the control does not already show it) and the plain-language explanation behind its
 * (i). [Def.more] puts it under its tab's More options. The tabs and the search both read the same
 * list, so a setting can never be findable in one and missing from the other.
 */
private sealed class Def(val tab: SettingsTab, val label: String, val help: String?, val more: Boolean) {
    abstract fun visible(s: AppSettings): Boolean
}

private class Toggle(
    tab: SettingsTab,
    label: String,
    help: String?,
    /** What the switch cannot say by itself; null for a plain on or off. */
    val value: (AppSettings, CamInfo?) -> String? = { _, _ -> null },
    val get: (AppSettings) -> Boolean,
    val set: (AppSettings, Boolean) -> AppSettings,
    val show: (AppSettings) -> Boolean = { true },
    more: Boolean = false,
) : Def(tab, label, help, more) {
    override fun visible(s: AppSettings) = show(s)
}

private class Choice(
    tab: SettingsTab,
    label: String,
    help: String?,
    val options: (CamInfo?) -> List<String>,
    val selected: (AppSettings, CamInfo?) -> Int,
    val select: (AppSettings, CamInfo?, Int) -> AppSettings,
    /** Shown only for a long row of chips, where the chosen one may be scrolled out of sight. */
    val value: ((AppSettings, CamInfo?) -> String?)? = null,
    val mono: Boolean = false,
    val show: (AppSettings) -> Boolean = { true },
    more: Boolean = false,
) : Def(tab, label, help, more) {
    override fun visible(s: AppSettings) = show(s)
}

private class Slide(
    tab: SettingsTab,
    label: String,
    help: String?,
    val value: (AppSettings) -> String,
    val range: ClosedFloatingPointRange<Float>,
    val get: (AppSettings) -> Float,
    val set: (AppSettings, Float) -> AppSettings,
    val show: (AppSettings) -> Boolean = { true },
    more: Boolean = false,
) : Def(tab, label, help, more) {
    override fun visible(s: AppSettings) = show(s)
}

private class Link(
    tab: SettingsTab,
    label: String,
    help: String?,
    val value: String?,
    val action: SettingsAction,
    val danger: Boolean = false,
    more: Boolean = false,
) : Def(tab, label, help, more) {
    override fun visible(s: AppSettings) = true
}

private val ShutterChoices = listOf(0.5, 1.0, 2.0, 4.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0, 30.0)
private val IsoChoices = listOf(200, 400, 800, 1600, 3200, 6400)

private fun shutterOptions(cam: CamInfo?): List<Double> {
    val max = cam?.maxExposureSeconds ?: 30.0
    return ShutterChoices.filter { it <= max + 0.01 }
}

private fun isoOptions(cam: CamInfo?): List<Int> {
    val r = cam?.isoRange ?: return IsoChoices
    return IsoChoices.filter { it in r.lower..r.upper }
}

private fun manualShown(s: AppSettings) = s.manualExposure || s.mode == Mode.PRO

private val Defs: List<Def> = listOf(
    // ---------------------------------------------------------------- Exposure
    Toggle(
        SettingsTab.Exposure, "Let the app choose",
        "Before shooting, the app takes one quick throwaway frame and measures how bright your sky " +
            "actually is and how many stars it can see. It then picks settings to suit that sky. " +
            "In an unfamiliar location this is almost always a better starting point than a guess. " +
            "Turn it off when you know exactly what you want. The Manual mode is always manual.",
        get = { !it.manualExposure },
        set = { s, v -> s.copy(manualExposure = !v) },
    ),
    Choice(
        SettingsTab.Exposure, "Shutter",
        "Longer frames collect more light, but the earth is turning, so past a certain point " +
            "stars stop being dots and become short streaks. Because the app stacks many " +
            "frames, more-and-shorter usually beats fewer-and-longer: you get the same total " +
            "light with rounder stars and better outlier rejection.",
        options = { cam -> shutterOptions(cam).map { exposureText(it) } },
        selected = { s, cam -> shutterOptions(cam).indexOfFirst { (it * 1e9).toLong() == s.exposureNs } },
        select = { s, cam, i -> s.copy(exposureNs = (shutterOptions(cam)[i] * 1e9).toLong()) },
        value = { s, _ -> exposureText(s.exposureNs) },
        mono = true,
        show = ::manualShown,
    ),
    Choice(
        SettingsTab.Exposure, "ISO",
        "Higher ISO gives a brighter image but a noisier one. Since we stack, a moderate ISO " +
            "with more frames beats a high ISO with few. Your sensor's gain is analogue all " +
            "the way to 6400, so nothing in this list is fake digital gain.",
        options = { cam -> isoOptions(cam).map { "$it" } },
        selected = { s, cam -> isoOptions(cam).indexOf(s.iso) },
        select = { s, cam, i -> s.copy(iso = isoOptions(cam)[i]) },
        value = { s, _ -> "ISO ${s.iso}" },
        mono = true,
        show = ::manualShown,
    ),
    Slide(
        SettingsTab.Exposure, "Frames",
        "Random noise falls by roughly the square root of the frame count: 4 frames halves it, " +
            "16 quarters it. There are diminishing returns, but more frames are always " +
            "cleaner — the limit is your patience and the battery.",
        value = { "${it.frameCount} frames" },
        range = 4f..99f,
        get = { it.frameCount.toFloat() },
        set = { s, v -> s.copy(frameCount = v.roundToInt()) },
        show = ::manualShown,
    ),
    Choice(
        SettingsTab.Exposure, "Start delay",
        "Your finger tapping the screen shakes the whole tripod. This waits for that wobble to die " +
            "away before the first exposure begins. The app also waits for the gyroscope to go " +
            "quiet on top of this.",
        options = { listOf("0 s", "3 s", "10 s") },
        selected = { s, _ -> listOf(0, 3, 10).indexOf(s.startDelaySec) },
        select = { s, _, i -> s.copy(startDelaySec = listOf(0, 3, 10)[i]) },
        mono = true,
    ),
    Toggle(
        SettingsTab.Exposure, "Auto trail limit",
        "Works out the longest you can expose before stars smear into visible streaks, using your " +
            "lens's focal length, aperture and the size of the sensor's pixels (the NPF rule). " +
            "It's stricter and far more honest than the old \"500 rule\". Switch off to impose " +
            "your own cap — longer for deliberate trailing, shorter for the roundest possible stars.",
        value = { s, cam ->
            if (s.autoTrailLimit) cam?.let { String.format(Locale.US, "%.0f s at most on this lens", it.trailLimitSeconds()) }
            else null
        },
        get = { it.autoTrailLimit },
        set = { s, v -> s.copy(autoTrailLimit = v) },
        more = true,
    ),
    Slide(
        SettingsTab.Exposure, "Trail cap",
        "The app will never expose a single frame longer than this, even if the sky is dark " +
            "enough to allow it.",
        value = { String.format(Locale.US, "%.0f s at most", it.trailCapSec) },
        range = 2f..30f,
        get = { it.trailCapSec },
        set = { s, v -> s.copy(trailCapSec = v) },
        show = { !it.autoTrailLimit },
        more = true,
    ),

    // ---------------------------------------------------------------- Focus
    Choice(
        SettingsTab.Focus, "Focus method",
        "Auto (stars) is the reliable one: the app takes a series of test frames, measures how " +
            "tight the stars actually are at each lens position, and locks the sharpest. Takes " +
            "about 30 seconds and beats eyeballing it every time. Infinity trusts the lens's own " +
            "infinity position, which on phones is often slightly off and drifts with temperature. " +
            "Manual lets you dial it in yourself.",
        options = { listOf("Auto stars", "Infinity", "Manual") },
        selected = { s, _ -> s.focusMode.ordinal },
        select = { s, _, i -> s.copy(focusMode = FocusMode.entries[i]) },
    ),
    Slide(
        SettingsTab.Focus, "Focus distance",
        "0 focuses as far away as the lens can. Larger numbers focus closer. For stars you " +
            "want 0 or very near it — but note that exactly 0 is often not quite the sharpest " +
            "point, which is the whole reason the automatic star focus exists.",
        value = { String.format(Locale.US, "%.3f dioptres (0 = infinity)", it.manualFocusDiopters) },
        range = 0f..0.5f,
        get = { it.manualFocusDiopters },
        set = { s, v -> s.copy(manualFocusDiopters = v) },
        show = { it.focusMode == FocusMode.MANUAL },
    ),
    Toggle(
        SettingsTab.Focus, "Reuse last focus",
        "On a tripod the lens does not move between back-to-back sessions, so the app remembers " +
            "the last good star-focus position and reuses it for 90 minutes instead of spending " +
            "about 30 seconds re-sweeping. Turn this off to force a fresh sweep every time — for " +
            "example after a large temperature change.",
        get = { it.reuseLastFocus },
        set = { s, v -> s.copy(reuseLastFocus = v) },
        more = true,
    ),

    // ---------------------------------------------------------------- Capture
    Toggle(
        SettingsTab.Capture, "Save DNG files",
        "Saves each individual exposure as a DNG you can drag straight into Siril, Sequator, " +
            "Lightroom or PixInsight on a computer. It costs storage, but it's your safety net: " +
            "the phone's stack is good, a desktop stack with more time and better tools can be " +
            "better, and you can only do that if the frames still exist.",
        get = { it.saveDngs },
        set = { s, v -> s.copy(saveDngs = v) },
    ),
    Toggle(
        SettingsTab.Capture, "Dark frames",
        "Some sensor pixels glow on their own regardless of light, and they show up as coloured " +
            "speckles. Dark frames — identical exposures with the lens covered — map exactly which " +
            "pixels those are so they can be repaired. Measured on this phone: stacking alone " +
            "removes about 97% of them, dark frames remove all of them. Worth the extra minute. " +
            "The dark map is also saved with the session, so later re-stacks stay calibrated.",
        get = { it.darksEnabled },
        set = { s, v -> s.copy(darksEnabled = v) },
    ),
    Choice(
        SettingsTab.Capture, "Dark frame count",
        "More darks average out to a cleaner map of the bad pixels, at the cost of more time " +
            "sitting with the lens covered. Four is a good balance.",
        options = { listOf("2", "4", "8") },
        selected = { s, _ -> listOf(2, 4, 8).indexOf(s.darkCount) },
        select = { s, _, i -> s.copy(darkCount = listOf(2, 4, 8)[i]) },
        mono = true,
        show = { it.darksEnabled },
        more = true,
    ),
    Toggle(
        SettingsTab.Capture, "Reuse recent darks",
        "A dark set is only valid for the exact same lens, exposure and ISO, so it is filed " +
            "under all of those and only reused on an exact match. That means the second and " +
            "later sessions of a night usually skip the \"cover the lens\" minute entirely. " +
            "Reuse expires after two weeks — hot pixels and dark current drift with the " +
            "sensor's age and temperature, and an old dark is worse than none. The capture " +
            "screen always says which set is in use.",
        get = { it.reuseDarks },
        set = { s, v -> s.copy(reuseDarks = v) },
        show = { it.darksEnabled },
        more = true,
    ),
    Toggle(
        SettingsTab.Capture, "Full-resolution RAW",
        "Binned RAW combines neighbouring pixels on the sensor, which means less noise per pixel, " +
            "smaller files and faster stacking — usually the better choice for astro. Full " +
            "resolution keeps more fine detail but is noisier per pixel and much heavier to " +
            "process. If your camera exposes only one RAW size, this switch does nothing.",
        value = { _, cam -> if (cam != null && cam.binnedRawSize == null) "No effect on this camera" else null },
        get = { it.fullResRaw },
        set = { s, v -> s.copy(fullResRaw = v) },
        more = true,
    ),
    Toggle(
        SettingsTab.Capture, "Replace bumped frames",
        "The gyroscope watches during every exposure. If something bumps the tripod — wind, a " +
            "footstep, your sleeve — that frame is thrown out rather than being allowed to blur " +
            "the stack, and an extra frame is added at the end to make up the lost light.",
        get = { it.autoReshoot },
        set = { s, v -> s.copy(autoReshoot = v) },
        more = true,
    ),
    Slide(
        SettingsTab.Capture, "Gap between frames",
        "Sensors get noisier as they warm up, and a long session warms them a lot. A short pause " +
            "between exposures lets the sensor shed heat. The app also adds its own extra pause " +
            "automatically if the phone reports it is getting hot.",
        value = { String.format(Locale.US, "%.1f s", it.cooldownSec) },
        range = 0f..5f,
        get = { it.cooldownSec },
        set = { s, v -> s.copy(cooldownSec = v) },
        more = true,
    ),

    // ---------------------------------------------------------------- Autopilot
    Toggle(
        SettingsTab.Autopilot, "Autopilot",
        "While Stars shoots, the autopilot looks at every frame the way you would if you stood " +
            "next to the tripod. A frame spoiled by cloud or soft stars is left out and an extra " +
            "one is shot at the end, so you still get the frames you asked for. When cloud comes " +
            "over, it waits for the stars to come back and then carries on. When the stars soften " +
            "because the lens cooled and the focus drifted, it focuses on them again. When dawn " +
            "or the Moon brightens the sky for good, it finishes with the good frames it has. " +
            "Everything it did is listed with the photo. It runs in Stars only: Star trails keeps " +
            "every frame on purpose (a gap would break the trails), Meteor shoots until you stop " +
            "it, and Manual does exactly what you set.",
        get = { it.autopilot },
        set = { s, v -> s.copy(autopilot = v) },
    ),
    Choice(
        SettingsTab.Autopilot, "Shoot for",
        "Instead of a number of frames, give it the time you have. It shoots as many frames as fit, " +
            "replaces the spoiled ones, and finishes on time with the good ones. The preset (Fast, " +
            "Best, Insane) then only sets the least it aims for.",
        options = { listOf("Preset", "10 min", "20 min", "30 min", "60 min") },
        selected = { s, _ -> listOf(0, 10, 20, 30, 60).indexOf(s.autopilotMinutes) },
        select = { s, _, i -> s.copy(autopilotMinutes = listOf(0, 10, 20, 30, 60)[i]) },
        value = { s, _ -> if (s.autopilotMinutes == 0) "The preset's frames" else "${s.autopilotMinutes} min" },
        mono = true,
        show = { it.autopilot },
    ),
    Choice(
        SettingsTab.Autopilot, "Wait for cloud",
        "How long to wait for a passing cloud before finishing with the frames already shot. While " +
            "it waits, every exposure is only a check: measured, never stacked. Off: cloudy frames " +
            "are still left out and replaced, but it does not wait.",
        options = { listOf("Off", "5 min", "15 min", "30 min") },
        selected = { s, _ -> listOf(0, 5, 15, 30).indexOf(s.autopilotWaitMin) },
        select = { s, _, i -> s.copy(autopilotWaitMin = listOf(0, 5, 15, 30)[i]) },
        mono = true,
        show = { it.autopilot },
        more = true,
    ),
    Toggle(
        SettingsTab.Autopilot, "Refocus when stars soften",
        "A phone lens's sharpest point moves as it cools through the night. When the stars grow " +
            "soft but as many are still visible (cloud would hide them instead), the autopilot runs " +
            "a short star focus around the current position, about half a minute, and carries on. " +
            "If they stay soft after that it is dew or wind, which focusing cannot fix: it tells " +
            "you. Only with Focus › Auto (stars).",
        value = { s, _ -> if (s.focusMode != FocusMode.AUTO_STAR) "Needs Focus › Auto (stars)" else null },
        get = { it.autopilotRefocus },
        set = { s, v -> s.copy(autopilotRefocus = v) },
        show = { it.autopilot },
        more = true,
    ),
    Toggle(
        SettingsTab.Autopilot, "Finish when the sky brightens",
        "Once the sky stays more than two and a half times as bright as at the start - dawn, the " +
            "Moon rising, lights coming on - more frames only add noise. The session then finishes " +
            "with the good frames it has, and still takes its dark frames.",
        get = { it.autopilotFinishBright },
        set = { s, v -> s.copy(autopilotFinishBright = v) },
        show = { it.autopilot },
        more = true,
    ),

    // ---------------------------------------------------------------- Stacking
    Toggle(
        SettingsTab.Stacking, "Stack on the phone",
        "Off turns Stelli into a plain intervalometer: it shoots the frames, saves them, and does " +
            "no processing at all. Useful if you always process on a computer anyway, or if you " +
            "want to keep shooting and deal with the stacking later.",
        get = { it.stackOnDevice },
        set = { s, v -> s.copy(stackOnDevice = v) },
    ),
    Toggle(
        SettingsTab.Stacking, "Live stack preview",
        "Shows the stack so far, updating after every frame. Genuinely useful in the field: within " +
            "a few frames you can see whether focus and framing are right, instead of finding out " +
            "after wasting a full session.",
        get = { it.livePreview },
        set = { s, v -> s.copy(livePreview = v) },
    ),
    Toggle(
        SettingsTab.Stacking, "Keep working files",
        "Keeps the app's internal copies of each frame so you can go into Sessions afterwards and " +
            "re-process the same night with different settings — a different stretch, gentler " +
            "rejection — in seconds. The app keeps the working files of the last 5 sessions and " +
            "prunes older ones, so re-stacking works out of the box without filling your storage.",
        get = { it.keepRawCache },
        set = { s, v -> s.copy(keepRawCache = v) },
        more = true,
    ),
    Slide(
        SettingsTab.Stacking, "Outlier rejection",
        "When the frames are combined, each pixel is compared across all of them and values that " +
            "disagree with the rest are thrown away — that is what makes aeroplanes, satellites " +
            "and hot pixels disappear, since they only appear in one frame. The app uses " +
            "winsorized sigma clipping, the method Siril and PixInsight recommend for stacks of " +
            "8-40 frames: the statistics come from a clamped copy of the samples, so a bright " +
            "satellite cannot inflate the threshold and hide itself. Lower is more aggressive; " +
            "go too low and it starts discarding real detail as well.",
        value = { String.format(Locale.US, "%.1f sigma", it.sigmaFinal) },
        range = 2f..4f,
        get = { it.sigmaFinal },
        set = { s, v -> s.copy(sigmaFinal = v) },
        more = true,
    ),
    Toggle(
        SettingsTab.Stacking, "Repair hot pixels",
        "Replaces each known-bad pixel with an estimate from its neighbours of the same colour. " +
            "Needs dark frames to know which pixels are bad.",
        value = { s, _ -> if (!s.darksEnabled) "Needs dark frames" else null },
        get = { it.hotPixelFix },
        set = { s, v -> s.copy(hotPixelFix = v) },
        more = true,
    ),
    Toggle(
        SettingsTab.Stacking, "Correct lens vignetting",
        "A bright lens is noticeably darker at the corners than in the centre. The camera " +
            "publishes a measurement of exactly that falloff, and RAW frames are deliberately " +
            "left uncorrected so it can be applied later — which is what this does. It matters " +
            "for more than flat corners: without it the sky-gradient step has to model the " +
            "lens falloff too, and a Milky Way band across the frame looks a lot like falloff, " +
            "so some of it gets removed with the vignetting. The trade is honest: corners are " +
            "brightened, so their noise is brightened with them. Does nothing at all if this " +
            "phone publishes no usable map.",
        get = { it.lensShading },
        set = { s, v -> s.copy(lensShading = v) },
        more = true,
    ),

    // ---------------------------------------------------------------- Look
    Choice(
        SettingsTab.Look, "Style",
        "Natural keeps honest colour — real star temperatures, real dust tones — and is what you " +
            "want if you edit on a PC later (the 16-bit TIFF master is always saved un-graded " +
            "either way). Nightfall applies the cool purple-blue look you see in viral phone " +
            "astro shots: blue-shifted colour, deeper blacks. Taste, not science — and you can " +
            "re-stack any kept session with the other style in seconds.",
        options = { listOf("Natural", "Nightfall") },
        selected = { s, _ -> s.style },
        select = { s, _, i -> s.copy(style = i) },
    ),
    Choice(
        SettingsTab.Look, "Stretch",
        "A raw astro stack is almost entirely black — the interesting material sits in the bottom " +
            "few percent of the brightness range. Stretching brightens the faint parts far more " +
            "than the bright ones, so nebulosity appears without turning stars into white blobs. " +
            "Subtle looks natural, Strong digs deepest but also lifts the noise.",
        options = { listOf("Subtle", "Normal", "Strong") },
        selected = { s, _ -> s.stretchStrength },
        select = { s, _, i -> s.copy(stretchStrength = i) },
    ),
    Choice(
        SettingsTab.Look, "Processing",
        "How the stacked photo is developed. Stelli finds the landscape and treats it apart " +
            "from the sky: the light-pollution glow is removed from the sky only, the sky gets a " +
            "dark background instead of a grey one, the ground keeps its own detail, stars keep " +
            "their colour, and a light noise reduction cleans the background. Classic is the " +
            "original look, kept so you can compare: it flattens sky and ground together, which " +
            "lifts the landscape to grey. Kept sessions can be re-stacked with either.",
        options = { listOf("Stelli", "Classic") },
        selected = { s, _ -> if (s.look == 1) 1 else 0 },
        select = { s, _, i -> s.copy(look = if (i == 1) 1 else 2) },
        more = true,
    ),
    Slide(
        SettingsTab.Look, "Colour saturation",
        "Star colour is real information, not decoration — hot stars are genuinely blue and cool " +
            "ones orange, and the Milky Way has real dust tones. Stacking preserves that colour, " +
            "and this brings it out.",
        value = { String.format(Locale.US, "%.2f×", it.saturation) },
        range = 1f..1.6f,
        get = { it.saturation },
        set = { s, v -> s.copy(saturation = v) },
        more = true,
    ),
    Toggle(
        SettingsTab.Look, "Remove light pollution gradient",
        "Streetlights and towns make one part of the sky brighter than another, which looks like a " +
            "colour wash across the image. This measures that gradient across the frame and " +
            "subtracts it, leaving an even background. Turn it off for a genuinely dark site, or " +
            "if a large bright object fills the frame.",
        get = { it.gradientRemoval },
        set = { s, v -> s.copy(gradientRemoval = v) },
        more = true,
    ),
    Toggle(
        SettingsTab.Look, "Colour noise reduction",
        "Blurs the colour information while leaving brightness detail untouched. Your eyes are far " +
            "less sensitive to colour noise than to brightness noise, so this removes the ugly " +
            "red/green mottling without softening a single star.",
        get = { it.chromaDenoise },
        set = { s, v -> s.copy(chromaDenoise = v) },
        more = true,
    ),
    Toggle(
        SettingsTab.Look, "Sharpen stars",
        "Applies mild sharpening only where stars are, never to the background. Off by default " +
            "because it is easy to overdo and make an image look crunchy and artificial.",
        get = { it.starSharpen },
        set = { s, v -> s.copy(starSharpen = v) },
        more = true,
    ),
    Toggle(
        SettingsTab.Look, "Blend sharp foreground",
        "Aligning frames to the stars means the landscape moves instead, so it comes out blurred. " +
            "This stacks the ground separately without alignment, finds the horizon, and blends " +
            "the two — sharp stars above, sharp landscape below. Automatically on in Insane mode. " +
            "Still experimental, and switched off at full resolution because it needs too much memory.",
        value = { _, _ -> "Experimental · always on with Insane" },
        get = { it.foregroundComposite },
        set = { s, v -> s.copy(foregroundComposite = v) },
        more = true,
    ),

    // ---------------------------------------------------------------- Motion
    Slide(
        SettingsTab.Motion, "Star trails length",
        "How long Star trails keeps shooting. The arcs get longer the more time you give it — " +
            "15 minutes gives short arcs, an hour gives dramatic sweeping circles. Bring a battery.",
        value = { "${it.trailsMinutes} min" },
        range = 5f..60f,
        get = { it.trailsMinutes.toFloat() },
        set = { s, v -> s.copy(trailsMinutes = v.roundToInt()) },
    ),
    Slide(
        SettingsTab.Motion, "Meteor watch length",
        "How long Meteor keeps watching. Meteors are rare and random, so this is a numbers " +
            "game — the longer it runs, the more chances of catching one.",
        value = { "${it.meteorMinutes} min" },
        range = 10f..120f,
        get = { it.meteorMinutes.toFloat() },
        set = { s, v -> s.copy(meteorMinutes = v.roundToInt()) },
    ),
    Slide(
        SettingsTab.Motion, "Stillness required to start",
        "The app refuses to begin until the gyroscope says the phone has been still for a moment. " +
            "Lower is stricter. Raise it if it stubbornly refuses to start on a windy night or a " +
            "slightly springy tripod.",
        value = { String.format(Locale.US, "%.3f rad/s", it.gyroStillThreshold) },
        range = 0.002f..0.02f,
        get = { it.gyroStillThreshold },
        set = { s, v -> s.copy(gyroStillThreshold = v) },
        more = true,
    ),
    Slide(
        SettingsTab.Motion, "Knock sensitivity",
        "Movement above this during an exposure marks that frame as bumped, so it gets thrown out " +
            "instead of smearing the stack. Lower rejects more aggressively.",
        value = { String.format(Locale.US, "%.3f rad/s", it.gyroBumpThreshold) },
        range = 0.005f..0.05f,
        get = { it.gyroBumpThreshold },
        set = { s, v -> s.copy(gyroBumpThreshold = v) },
        more = true,
    ),

    // ---------------------------------------------------------------- Interface
    Toggle(
        SettingsTab.Interface, "Night vision (red)",
        "Turns the whole interface deep red. Your eyes take around 20 minutes to fully adapt to " +
            "darkness and a single glance at a white screen undoes it. Red light barely affects " +
            "that adaptation, so you can check settings without blinding yourself. The live " +
            "viewfinder is dimmed and tinted; photos are shown in red.",
        get = { it.nightMode },
        set = { s, v -> s.copy(nightMode = v) },
    ),
    Slide(
        SettingsTab.Interface, "Screen dimming",
        "Dims the display beyond the system's own minimum. Even a dim red screen is bright " +
            "next to a genuinely dark sky.",
        value = { String.format(Locale.US, "%.0f%%", it.nightDim * 100) },
        range = 0f..0.8f,
        get = { it.nightDim },
        set = { s, v -> s.copy(nightDim = v) },
        show = { it.nightMode },
    ),
    Toggle(
        SettingsTab.Interface, "Spirit level",
        "Shows the phone's tilt on the viewfinder — a horizon line that turns blue when level. " +
            "Pointless for pure sky shots (no horizon in frame, and the Milky Way runs diagonally " +
            "anyway), which is why it is off by default. Turn it on for landscape-astro " +
            "compositions: at night the ground is often too dark to judge in the preview, and a " +
            "two-degree tilt you cannot see is the classic ruined-horizon shot.",
        get = { it.showLevel },
        set = { s, v -> s.copy(showLevel = v) },
    ),
    Toggle(
        SettingsTab.Interface, "Vibration feedback",
        "A short buzz each time a frame finishes, so you know the session is progressing without " +
            "looking at the screen.",
        get = { it.haptics },
        set = { s, v -> s.copy(haptics = v) },
    ),
    Link(SettingsTab.Interface, "Show the intro again", null, null, SettingsAction.Intro),

    // ---------------------------------------------------------------- Advanced
    Link(SettingsTab.Advanced, "Diagnostics", null, "Log, camera test, debug report", SettingsAction.Diagnostics),
    Link(SettingsTab.Advanced, "Privacy policy", null, "Nothing leaves the phone unless you share it", SettingsAction.Privacy),
    Link(SettingsTab.Advanced, "Terms of use", null, null, SettingsAction.Terms),
    Link(SettingsTab.Advanced, "Licences and data", null, null, SettingsAction.Licences),
    Link(SettingsTab.Advanced, "Reset all settings", null, "Sessions and photos stay", SettingsAction.Reset, danger = true),
    Toggle(
        SettingsTab.Advanced, "Repair the camera when a frame fails",
        "When a frame fails, the app first repairs the camera and then retries: it cancels " +
            "anything stuck, then rebuilds the capture session, then closes and reopens the " +
            "camera completely. If the camera cannot be brought back, the frames already shot " +
            "are still stacked. Off reproduces the old behaviour — one immediate retry, then " +
            "the session ends — which is only useful for comparing.",
        get = { it.cameraRecovery },
        set = { s, v -> s.copy(cameraRecovery = v) },
        more = true,
    ),
    Toggle(
        SettingsTab.Advanced, "Fresh camera session after long exposures",
        "This phone's camera driver gets stuck on whatever is asked of it right after a long " +
            "exposure: the request never comes back, and after a minute everything on that " +
            "camera session fails. That is why every field session died at frame 2. Rebuilding " +
            "the session after each long exposure avoids it completely (tested: 3 of 3 long " +
            "frames on time, against 1 of 3 without) for about a fifth of a second per frame. " +
            "Off only to reproduce the old behaviour.",
        get = { it.freshSessionAfterLong },
        set = { s, v -> s.copy(freshSessionAfterLong = v) },
        more = true,
    ),
    Toggle(
        SettingsTab.Advanced, "Keep the viewfinder stream while shooting",
        "During a session the viewfinder is covered anyway, but its output stays part of the " +
            "camera session. When the screen turns off, that output dies while still being part " +
            "of the session. Off shoots with a RAW-only session instead, which takes the " +
            "viewfinder out of the picture entirely.",
        get = { it.debugPreviewInSession },
        set = { s, v -> s.copy(debugPreviewInSession = v) },
        more = true,
    ),
    Toggle(
        SettingsTab.Advanced, "Vignetting map on every frame",
        "The lens's vignetting map is only needed once per session, so only the probe frame " +
            "asks the camera for it. Version 0.1 asked on every long exposure. On reproduces " +
            "that behaviour, to test whether it causes trouble.",
        get = { it.debugShadingEveryFrame },
        set = { s, v -> s.copy(debugShadingEveryFrame = v) },
        more = true,
    ),
    Toggle(
        SettingsTab.Advanced, "Set frame duration explicitly",
        "Normally the camera stretches each frame to fit the exposure on its own. Some camera " +
            "drivers handle long exposures better when the frame duration is also requested " +
            "explicitly. On sets it to the exposure time.",
        get = { it.debugFrameDuration },
        set = { s, v -> s.copy(debugFrameDuration = v) },
        more = true,
    ),
    Toggle(
        SettingsTab.Advanced, "Detailed camera log",
        "Logs the camera's minor events too (exposure started, idle/active) with timings. The " +
            "log stays small either way; there is no reason to switch this off while the camera " +
            "is being investigated.",
        get = { it.verboseCameraLog },
        set = { s, v -> s.copy(verboseCameraLog = v) },
        more = true,
    ),
    Link(SettingsTab.Advanced, "Capability probe", null, "What this camera exposes to apps", SettingsAction.Probe, more = true),
    Link(SettingsTab.Advanced, "Engine self-test", null, "The stacking engine on a test starfield", SettingsAction.SelfTest, more = true),
)

@Composable
fun SettingsScreen(
    vm: StarCamViewModel,
    initialTab: SettingsTab,
    onTabChange: (SettingsTab) -> Unit,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenProbe: () -> Unit,
    onOpenSelfTest: () -> Unit,
    onReplayIntro: () -> Unit,
) {
    val c = Sc.colors
    val s by vm.settingsStore.settings.collectAsState()
    val cam by vm.camInfo.collectAsState()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var query by rememberSaveable { mutableStateOf("") }
    var openHelp by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var showLicences by remember { mutableStateOf(false) }
    // The legal page open in a dialog: its asset, docs/legal on the web.
    var legalPage by remember { mutableStateOf<String?>(null) }
    fun up(t: (AppSettings) -> AppSettings) = vm.settingsStore.update(t)

    val run: (SettingsAction) -> Unit = { a ->
        when (a) {
            SettingsAction.Diagnostics -> onOpenDiagnostics()
            SettingsAction.Probe -> onOpenProbe()
            SettingsAction.SelfTest -> onOpenSelfTest()
            SettingsAction.Intro -> onReplayIntro()
            SettingsAction.Reset -> confirmReset = true
            SettingsAction.Licences -> showLicences = true
            SettingsAction.Privacy -> legalPage = "privacy.html"
            SettingsAction.Terms -> legalPage = "terms.html"
        }
    }
    val version = remember {
        runCatching {
            val p = context.packageManager.getPackageInfo(context.packageName, 0)
            "Stelli ${p.versionName} (${p.longVersionCode})"
        }.getOrDefault("Stelli")
    }

    ScScreen {
        ScTopBar("Settings", onBack)
        SearchField(query, { query = it }, Modifier.padding(horizontal = ScreenMargin))
        val searching = query.isNotBlank()
        if (!searching) {
            // Opens scrolled to the remembered tab, so a selected chip is never off-screen.
            val tabsState = rememberLazyListState(initialFirstVisibleItemIndex = (tab.ordinal - 1).coerceAtLeast(0))
            LazyRow(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                state = tabsState,
                contentPadding = PaddingValues(horizontal = ScreenMargin),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(SettingsTab.entries) { t ->
                    ScChip(t.label, selected = t == tab, onClick = {
                        tab = t
                        onTabChange(t)
                        openHelp = null
                    }, height = 42.dp, icon = tabIcon(t))
                }
            }
        } else {
            Spacer(Modifier.height(10.dp))
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenMargin).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (searching) {
                val q = query.trim().lowercase(Locale.getDefault())
                // The search finds what is tucked under More options too.
                val hits = Defs.filter { d ->
                    d.visible(s) && (
                        d.label.lowercase().contains(q) ||
                            (d.help?.lowercase()?.contains(q) == true) ||
                            d.tab.label.lowercase().contains(q)
                        )
                }
                if (hits.isEmpty()) {
                    Text("Nothing matches \"$query\".", style = ScType.body, color = c.text3,
                        modifier = Modifier.padding(top = 24.dp))
                }
                hits.groupBy { it.tab }.forEach { (t, defs) ->
                    ScLabel(t.label, Modifier.padding(top = 4.dp))
                    DefCard(defs, s, cam, openHelp, { openHelp = if (openHelp == it) null else it }, ::up, run)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScIconBadge(tabIcon(tab), size = 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(tab.label, style = ScType.titleSmall, color = c.text)
                        Text(tab.blurb, style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                val defs = Defs.filter { it.tab == tab && it.visible(s) }
                // Closed again on every other tab: each opens on its few main settings.
                var moreOpen by remember(tab) { mutableStateOf(false) }
                DefCard(
                    defs.filter { !it.more }, s, cam, openHelp,
                    { openHelp = if (openHelp == it) null else it }, ::up, run,
                    more = defs.filter { it.more }, moreLabel = tab.moreLabel, moreNote = tab.moreNote,
                    moreOpen = moreOpen, onMoreToggle = { moreOpen = !moreOpen },
                )
                if (tab == SettingsTab.Advanced) {
                    Text(version, style = ScType.data, color = c.text3, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    if (confirmReset) {
        ScConfirmDialog(
            title = "Reset all settings?",
            text = "Every setting goes back to its default. Your sessions and photos stay.",
            confirmText = "Reset",
            danger = true,
            onConfirm = {
                confirmReset = false
                vm.settingsStore.update { AppSettings() }
            },
            onDismiss = { confirmReset = false },
        )
    }
    if (showLicences) LicencesDialog { showLicences = false }
    legalPage?.let { page -> LegalDialog(page) { legalPage = null } }
}

/**
 * One card of settings: [defs] as rows, then, when there are any, [more] folded under one row
 * ([moreLabel], naming what is inside) that opens them in place.
 */
@Composable
private fun DefCard(
    defs: List<Def>,
    s: AppSettings,
    cam: CamInfo?,
    openHelp: String?,
    onToggleHelp: (String) -> Unit,
    up: ((AppSettings) -> AppSettings) -> Unit,
    run: (SettingsAction) -> Unit,
    more: List<Def> = emptyList(),
    moreLabel: String = "More options",
    moreNote: String? = null,
    moreOpen: Boolean = false,
    onMoreToggle: () -> Unit = {},
) {
    if (defs.isEmpty() && more.isEmpty()) return
    val c = Sc.colors
    @Composable
    fun divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
    ScCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
        defs.forEachIndexed { i, d ->
            if (i > 0) divider()
            DefRow(d, s, cam, openHelp, onToggleHelp, up, run)
        }
        if (more.isNotEmpty()) {
            if (defs.isNotEmpty()) divider()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clickable(role = Role.Button, onClickLabel = if (moreOpen) "Close" else "Open", onClick = onMoreToggle)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(moreLabel, style = ScType.headline.copy(fontWeight = FontWeight.Medium), color = c.accentText)
                    if (!moreOpen) {
                        Text(more.joinToString(" · ") { it.label }, style = ScType.bodySmall, color = c.text3,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                ScChevron(moreOpen, tint = c.accentText)
                Spacer(Modifier.width(10.dp))
            }
            if (moreOpen) {
                moreNote?.let {
                    Text(it, style = ScType.bodySmall, color = c.text3, modifier = Modifier.padding(bottom = 8.dp))
                }
                more.forEach { d ->
                    divider()
                    DefRow(d, s, cam, openHelp, onToggleHelp, up, run)
                }
            }
        }
    }
}

/** One setting: its name, the line under it where the control does not say it all, its control and its (i). */
@Composable
private fun DefRow(
    d: Def,
    s: AppSettings,
    cam: CamInfo?,
    openHelp: String?,
    onToggleHelp: (String) -> Unit,
    up: ((AppSettings) -> AppSettings) -> Unit,
    run: (SettingsAction) -> Unit,
) {
    val c = Sc.colors
    val key = "${d.tab.name}/${d.label}"
    val expanded = openHelp == key
    when (d) {
        is Toggle -> ScSettingRow(
            d.label,
            Modifier.clickable(role = Role.Switch) { up { d.set(it, !d.get(it)) } },
            value = d.value(s, cam), help = d.help, expanded = expanded,
            onToggleHelp = { onToggleHelp(key) },
        ) {
            ScSwitch(d.get(s), { v -> up { d.set(it, v) } })
        }
        is Choice -> {
            val options = d.options(cam)
            val sel = d.selected(s, cam)
            ScSettingRow(
                d.label, value = if (options.size > 4) d.value?.invoke(s, cam) else null, help = d.help,
                expanded = expanded,
                onToggleHelp = { onToggleHelp(key) },
                below = {
                    if (options.size <= 4) {
                        ScSegmented(options, sel, { i2 -> up { d.select(it, cam, i2) } },
                            Modifier.fillMaxWidth().padding(top = 8.dp), mono = d.mono)
                    } else {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            options.forEachIndexed { oi, o ->
                                ScChip(o, selected = oi == sel, onClick = { up { d.select(it, cam, oi) } },
                                    mono = d.mono, height = 40.dp)
                            }
                        }
                    }
                },
            )
        }
        is Slide -> ScSettingRow(
            d.label, value = d.value(s), help = d.help, expanded = expanded,
            onToggleHelp = { onToggleHelp(key) },
            below = {
                ScSlider(d.get(s), { v -> up { d.set(it, v) } }, d.range)
            },
        )
        is Link -> ScSettingRow(
            d.label,
            Modifier.clickable(role = Role.Button) { run(d.action) },
            value = d.value, help = d.help, expanded = expanded,
            onToggleHelp = { onToggleHelp(key) },
            labelColor = if (d.danger) c.error else null,
            valueColor = c.text3,
        ) {
            Icon(ScIcons.ChevronRight, null, Modifier.size(18.dp), tint = c.text3)
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Sc.colors
    Row(
        modifier.fillMaxWidth().height(48.dp).clip(ScShapes.button).background(c.surface)
            .border(1.dp, c.hairline, ScShapes.button).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ScIcons.Search, null, Modifier.size(18.dp), tint = c.text3)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search settings", style = ScType.body, color = c.text3)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = ScType.body.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button) { onChange("") },
                contentAlignment = Alignment.Center,
            ) { Icon(ScIcons.Close, "Clear search", Modifier.size(16.dp), tint = c.text3) }
        }
    }
}

@Composable
private fun LicencesDialog(onDismiss: () -> Unit) {
    val c = Sc.colors
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        text = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("licenses/notosans-OFL.txt").bufferedReader().use { it.readText() }
            }.getOrDefault("Licence text unavailable.")
        }
    }
    ScDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(c.surface)
                .border(1.dp, c.hairline, ScShapes.card).padding(20.dp),
        ) {
            Text("Open-source licences", style = ScType.titleSmall, color = c.text)
            Column(Modifier.padding(top = 12.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Data", style = ScType.headline, color = c.text)
                Text(
                    "Night lights: \"The global NPP-VIIRS-like nighttime light data (Version 2)\", 2025, " +
                        "Chen, Yu et al., Harvard Dataverse doi:10.7910/DVN/YGIVCD, CC0 1.0; from Earth " +
                        "Observation Group, Colorado School of Mines, VIIRS data.\n" +
                        "Sky-glow model: Duriscoe et al. 2018, JQSRT 214:133-145, CC BY 4.0. Calibrated against " +
                        "US National Park Service night-sky measurements and Globe at Night (CC BY 4.0).\n" +
                        "Base map: Natural Earth (public domain). Dark-sky places: Wikidata (CC0).\n" +
                        "Stars: Yale Bright Star Catalogue, 5th ed. (Hoffleit & Warren, public domain).",
                    style = ScType.caption, color = c.text2, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                )
                Text("Noto Sans", style = ScType.headline, color = c.text)
                Text("Copyright 2022 The Noto Project Authors (github.com/notofonts/latin-greek-cyrillic)",
                    style = ScType.caption, color = c.text3, modifier = Modifier.padding(bottom = 10.dp))
                Text(text, style = ScType.data, color = c.text2)
            }
            Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.CenterEnd) {
                ScButton("Close", onDismiss, height = 44.dp)
            }
        }
    }
}

/**
 * The privacy policy or the terms of use, from the same page as on the web (docs/legal), so that
 * Play's "privacy policy inside the app" is there without internet access.
 */
@Composable
private fun LegalDialog(asset: String, onDismiss: () -> Unit) {
    val c = Sc.colors
    val context = LocalContext.current
    var blocks by remember { mutableStateOf<List<LegalPage.Block>?>(null) }
    LaunchedEffect(asset) {
        blocks = withContext(Dispatchers.IO) {
            runCatching {
                LegalPage.parse(context.assets.open(asset).bufferedReader().use { it.readText() })
            }.getOrDefault(emptyList())
        }
    }
    ScDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(c.surface)
                .border(1.dp, c.hairline, ScShapes.card).padding(20.dp),
        ) {
            val all = blocks.orEmpty()
            Text(all.firstOrNull { it.kind == LegalPage.Kind.Title }?.text.orEmpty(),
                style = ScType.titleSmall, color = c.text)
            Column(Modifier.padding(top = 8.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                if (blocks?.isEmpty() == true) Text("Text unavailable.", style = ScType.body, color = c.text3)
                for (b in all) when (b.kind) {
                    LegalPage.Kind.Title -> Unit
                    LegalPage.Kind.Updated -> Text(b.text, style = ScType.caption, color = c.text3,
                        modifier = Modifier.padding(bottom = 10.dp))
                    LegalPage.Kind.Heading -> Text(b.text, style = ScType.headline, color = c.text,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    LegalPage.Kind.Paragraph -> Text(b.text, style = ScType.bodySmall, color = c.text2,
                        modifier = Modifier.padding(bottom = 8.dp))
                    LegalPage.Kind.Item -> Row(Modifier.padding(bottom = 6.dp)) {
                        Text("•", style = ScType.bodySmall, color = c.text3, modifier = Modifier.width(14.dp))
                        Text(b.text, style = ScType.bodySmall, color = c.text2)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.CenterEnd) {
                ScButton("Close", onDismiss, height = 44.dp)
            }
        }
    }
}
