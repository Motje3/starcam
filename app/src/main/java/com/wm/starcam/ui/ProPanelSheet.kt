package com.wm.starcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wm.starcam.StarCamViewModel
import com.wm.starcam.camera.CamInfo
import com.wm.starcam.session.AppSettings
import com.wm.starcam.session.FocusMode
import com.wm.starcam.session.Mode
import com.wm.starcam.util.Fmt

/**
 * Pro panel: every automatic decision, overridable (PLAN.md §8).
 *
 * Organised as tabs rather than one long scroll — outdoors in the dark, hunting for one toggle in a
 * 35-row list is miserable. Every row carries an (i) that expands a plain-English explanation of
 * what the setting does and when you'd actually want to change it.
 */
private enum class ProTab(val label: String) {
    EXPOSURE("Exposure"),
    FOCUS("Focus"),
    CAPTURE("Capture"),
    STACKING("Stacking"),
    LOOK("Look"),
    MOTION("Motion"),
    INTERFACE("Interface"),
    DEBUG("Debug"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProPanelSheet(vm: StarCamViewModel, onDismiss: () -> Unit) {
    val s by vm.settingsStore.settings.collectAsState()
    val cam by vm.camInfo.collectAsState()
    fun up(t: (AppSettings) -> AppSettings) = vm.settingsStore.update(t)

    var tab by remember { mutableStateOf(ProTab.EXPOSURE) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Pro settings",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.settingsStore.update { AppSettings() } }) {
                    Icon(Icons.Filled.RestartAlt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reset", fontSize = 13.sp)
                }
            }
            Text(
                "Everything the app decides for you, and how to overrule it. Tap any ⓘ for what it does.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ProTab.entries.forEach { t ->
                    FilterChip(
                        selected = tab == t,
                        onClick = { tab = t },
                        label = { Text(t.label, fontSize = 12.sp) },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when (tab) {
                    ProTab.EXPOSURE -> ExposureTab(s, cam, ::up)
                    ProTab.FOCUS -> FocusTab(s, ::up)
                    ProTab.CAPTURE -> CaptureTab(s, cam, ::up)
                    ProTab.STACKING -> StackingTab(s, ::up)
                    ProTab.LOOK -> LookTab(s, ::up)
                    ProTab.MOTION -> MotionTab(s, ::up)
                    ProTab.INTERFACE -> InterfaceTab(s, ::up)
                    ProTab.DEBUG -> DebugTab(s, ::up)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- tabs

@Composable
private fun ExposureTab(s: AppSettings, cam: CamInfo?, up: ((AppSettings) -> AppSettings) -> Unit) {
    ToggleSetting(
        "Let the app choose",
        if (s.manualExposure) "off — you set shutter and ISO yourself"
        else "on — a test frame picks shutter, ISO and frame count",
        "Before shooting, the app takes one quick throwaway frame and measures how bright your sky " +
            "actually is and how many stars it can see. It then picks settings to suit that sky. " +
            "In an unfamiliar location this is almost always a better starting point than a guess. " +
            "Turn it off when you know exactly what you want.",
        !s.manualExposure,
    ) { up { it.copy(manualExposure = !it.manualExposure) } }

    if (s.manualExposure || s.mode == Mode.PRO) {
        val maxExp = cam?.maxExposureSeconds ?: 8.0
        val expChoices = listOf(0.5, 1.0, 2.0, 4.0, 8.0, 15.0, 30.0).filter { it <= maxExp + 0.01 }
        ChipsSetting(
            "Shutter",
            "how long each single frame is exposed",
            "Longer frames collect more light, but the earth is turning, so past a certain point " +
                "stars stop being dots and become short streaks. Because the app stacks many " +
                "frames, more-and-shorter usually beats fewer-and-longer: you get the same total " +
                "light with rounder stars and better outlier rejection.",
            expChoices.map { Fmt.exposure((it * 1e9).toLong()) },
            expChoices.indexOfFirst { (it * 1e9).toLong() == s.exposureNs }.coerceAtLeast(0),
        ) { i -> up { it.copy(exposureNs = (expChoices[i] * 1e9).toLong()) } }

        val isoChoices = listOf(200, 400, 800, 1600, 3200, 6400)
        ChipsSetting(
            "ISO",
            "sensor gain",
            "Higher ISO gives a brighter image but a noisier one. Since we stack, a moderate ISO " +
                "with more frames beats a high ISO with few. Your sensor's gain is analogue all " +
                "the way to 6400, so nothing in this list is fake digital gain.",
            isoChoices.map { "$it" },
            isoChoices.indexOf(s.iso).coerceAtLeast(0),
        ) { i -> up { it.copy(iso = isoChoices[i]) } }

        SliderSetting(
            "Frames", "${s.frameCount} exposures to stack",
            "Random noise falls by roughly the square root of the frame count: 4 frames halves it, " +
                "16 quarters it. There are diminishing returns, but more frames are always " +
                "cleaner — the limit is your patience and the battery.",
            s.frameCount.toFloat(), 4f..99f,
        ) { v -> up { it.copy(frameCount = v.toInt()) } }
    }

    ToggleSetting(
        "Auto trail limit",
        cam?.let { "%.1fs per frame, from this lens".format(it.trailLimitSeconds()) }
            ?: "computed from the lens",
        "Works out the longest you can expose before stars smear into visible streaks, using your " +
            "lens's focal length, aperture and the size of the sensor's pixels (the NPF rule). " +
            "It's stricter and far more honest than the old \"500 rule\". Switch off to impose " +
            "your own cap — longer for deliberate trailing, shorter for the roundest possible stars.",
        s.autoTrailLimit,
    ) { up { it.copy(autoTrailLimit = !it.autoTrailLimit) } }

    if (!s.autoTrailLimit) {
        SliderSetting(
            "Trail cap", "%.0fs maximum per frame".format(s.trailCapSec),
            "The app will never expose a single frame longer than this, even if the sky is dark " +
                "enough to allow it.",
            s.trailCapSec, 2f..30f,
        ) { v -> up { it.copy(trailCapSec = v) } }
    }

    ChipsSetting(
        "Start delay", "pause after you tap the button",
        "Your finger tapping the screen shakes the whole tripod. This waits for that wobble to die " +
            "away before the first exposure begins. The app also waits for the gyroscope to go " +
            "quiet on top of this.",
        listOf("0s", "3s", "10s"),
        listOf(0, 3, 10).indexOf(s.startDelaySec).coerceAtLeast(0),
    ) { i -> up { it.copy(startDelaySec = listOf(0, 3, 10)[i]) } }
}

@Composable
private fun FocusTab(s: AppSettings, up: ((AppSettings) -> AppSettings) -> Unit) {
    ChipsSetting(
        "Focus method", "how the lens is set before shooting",
        "Auto (stars) is the reliable one: the app takes a series of test frames, measures how " +
            "tight the stars actually are at each lens position, and locks the sharpest. Takes " +
            "about 30 seconds and beats eyeballing it every time. Infinity trusts the lens's own " +
            "infinity position, which on phones is often slightly off and drifts with temperature. " +
            "Manual lets you dial it in yourself.",
        listOf("Auto (stars)", "Infinity", "Manual"),
        s.focusMode.ordinal,
    ) { i -> up { it.copy(focusMode = FocusMode.entries[i]) } }

    if (s.focusMode == FocusMode.MANUAL) {
        SliderSetting(
            "Focus distance", "%.3f dioptres (0 = infinity)".format(s.manualFocusDiopters),
            "0 focuses as far away as the lens can. Larger numbers focus closer. For stars you " +
                "want 0 or very near it — but note that exactly 0 is often not quite the sharpest " +
                "point, which is the whole reason the automatic star focus exists.",
            s.manualFocusDiopters, 0f..0.5f,
        ) { v -> up { it.copy(manualFocusDiopters = v) } }
    }

    ToggleSetting(
        "Reuse last focus", "skip the sweep if focused within 90 min",
        "On a tripod the lens does not move between back-to-back sessions, so the app remembers " +
            "the last good star-focus position and reuses it for 90 minutes instead of spending " +
            "about 30 seconds re-sweeping. Turn this off to force a fresh sweep every time — for " +
            "example after a large temperature change.",
        s.reuseLastFocus,
    ) { up { it.copy(reuseLastFocus = !it.reuseLastFocus) } }
}

@Composable
private fun CaptureTab(s: AppSettings, cam: CamInfo?, up: ((AppSettings) -> AppSettings) -> Unit) {
    val onlyOneRawSize = cam != null && cam.binnedRawSize == null
    ToggleSetting(
        "Full-resolution RAW",
        if (onlyOneRawSize) "no effect — your camera offers only one RAW size"
        else "off = binned RAW (recommended)",
        "Binned RAW combines neighbouring pixels on the sensor, which means less noise per pixel, " +
            "smaller files and faster stacking — usually the better choice for astro. Full " +
            "resolution keeps more fine detail but is noisier per pixel and much heavier to process." +
            if (onlyOneRawSize) " Your camera only exposes one RAW size, so this switch does " +
                "nothing on this phone." else "",
        s.fullResRaw,
    ) { up { it.copy(fullResRaw = !it.fullResRaw) } }

    ToggleSetting(
        "Save DNG files", "every frame kept as a standard raw file",
        "Saves each individual exposure as a DNG you can drag straight into Siril, Sequator, " +
            "Lightroom or PixInsight on a computer. It costs storage, but it's your safety net: " +
            "the phone's stack is good, a desktop stack with more time and better tools can be " +
            "better, and you can only do that if the frames still exist.",
        s.saveDngs,
    ) { up { it.copy(saveDngs = !it.saveDngs) } }

    ToggleSetting(
        "Dark frames", "the \"cover the lens\" step after shooting",
        "Some sensor pixels glow on their own regardless of light, and they show up as coloured " +
            "speckles. Dark frames — identical exposures with the lens covered — map exactly which " +
            "pixels those are so they can be repaired. Measured on this phone: stacking alone " +
            "removes about 97% of them, dark frames remove all of them. Worth the extra minute. " +
            "The dark map is also saved with the session, so later re-stacks stay calibrated.",
        s.darksEnabled,
    ) { up { it.copy(darksEnabled = !it.darksEnabled) } }

    if (s.darksEnabled) {
        ChipsSetting(
            "Dark frame count", "how many to shoot",
            "More darks average out to a cleaner map of the bad pixels, at the cost of more time " +
                "sitting with the lens covered. Four is a good balance.",
            listOf("2", "4", "8"),
            listOf(2, 4, 8).indexOf(s.darkCount).coerceAtLeast(0),
        ) { i -> up { it.copy(darkCount = listOf(2, 4, 8)[i]) } }

        ToggleSetting(
            "Reuse recent darks", "skip the lens-covered step when a matching set exists",
            "A dark set is only valid for the exact same lens, exposure and ISO, so it is filed " +
                "under all of those and only reused on an exact match. That means the second and " +
                "later sessions of a night usually skip the \"cover the lens\" minute entirely. " +
                "Reuse expires after two weeks — hot pixels and dark current drift with the " +
                "sensor's age and temperature, and an old dark is worse than none. The capture " +
                "screen always says which set is in use.",
            s.reuseDarks,
        ) { up { it.copy(reuseDarks = !it.reuseDarks) } }
    }

    ToggleSetting(
        "Replace bumped frames", "shoot an extra when a knock is detected",
        "The gyroscope watches during every exposure. If something bumps the tripod — wind, a " +
            "footstep, your sleeve — that frame is thrown out rather than being allowed to blur " +
            "the stack, and an extra frame is added at the end to make up the lost light.",
        s.autoReshoot,
    ) { up { it.copy(autoReshoot = !it.autoReshoot) } }

    SliderSetting(
        "Gap between frames", "%.1fs pause".format(s.cooldownSec),
        "Sensors get noisier as they warm up, and a long session warms them a lot. A short pause " +
            "between exposures lets the sensor shed heat. The app also adds its own extra pause " +
            "automatically if the phone reports it is getting hot.",
        s.cooldownSec, 0f..5f,
    ) { v -> up { it.copy(cooldownSec = v) } }
}

@Composable
private fun StackingTab(s: AppSettings, up: ((AppSettings) -> AppSettings) -> Unit) {
    ToggleSetting(
        "Stack on the phone", if (s.stackOnDevice) "on" else "off — save raw frames only",
        "Off turns StarCam into a plain intervalometer: it shoots the frames, saves them, and does " +
            "no processing at all. Useful if you always process on a computer anyway, or if you " +
            "want to keep shooting and deal with the stacking later.",
        s.stackOnDevice,
    ) { up { it.copy(stackOnDevice = !it.stackOnDevice) } }

    SliderSetting(
        "Outlier rejection", "%.1f sigma".format(s.sigmaFinal),
        "When the frames are combined, each pixel is compared across all of them and values that " +
            "disagree with the rest are thrown away — that is what makes aeroplanes, satellites " +
            "and hot pixels disappear, since they only appear in one frame. The app uses " +
            "winsorized sigma clipping, the method Siril and PixInsight recommend for stacks of " +
            "8–40 frames: the statistics come from a clamped copy of the samples, so a bright " +
            "satellite cannot inflate the threshold and hide itself. The slider still sets how " +
            "strict rejection is — lower is more aggressive; go too low and it starts discarding " +
            "real detail as well.",
        s.sigmaFinal, 2f..4f,
    ) { v -> up { it.copy(sigmaFinal = v) } }

    ToggleSetting(
        "Repair hot pixels", "use the map built from dark frames",
        "Replaces each known-bad pixel with an estimate from its neighbours of the same colour. " +
            "Needs dark frames to know which pixels are bad.",
        s.hotPixelFix,
    ) { up { it.copy(hotPixelFix = !it.hotPixelFix) } }

    ToggleSetting(
        "Correct lens vignetting", "flatten the lens's own falloff before stacking",
        "A bright lens is noticeably darker at the corners than in the centre. The camera "
            + "publishes a measurement of exactly that falloff, and RAW frames are deliberately "
            + "left uncorrected so it can be applied later — which is what this does. It matters "
            + "for more than flat corners: without it the sky-gradient step has to model the "
            + "lens falloff too, and a Milky Way band across the frame looks a lot like falloff, "
            + "so some of it gets removed with the vignetting. The trade is honest: corners are "
            + "brightened, so their noise is brightened with them. Does nothing at all if this "
            + "phone publishes no usable map.",
        s.lensShading,
    ) { up { it.copy(lensShading = !it.lensShading) } }

    ToggleSetting(
        "Live stack preview", "watch the image build while shooting",
        "Shows the stack so far, updating after every frame. Genuinely useful in the field: within " +
            "a few frames you can see whether focus and framing are right, instead of finding out " +
            "after wasting a full session.",
        s.livePreview,
    ) { up { it.copy(livePreview = !it.livePreview) } }

    ToggleSetting(
        "Keep working files", "on by default — re-stack later without re-shooting",
        "Keeps the app's internal copies of each frame so you can go into Sessions afterwards and " +
            "re-process the same night with different settings — a different stretch, gentler " +
            "rejection — in seconds. Now on by default: the app automatically keeps only the last " +
            "5 sessions of working files and prunes older caches, so re-stacking works out of the " +
            "box without filling your storage.",
        s.keepRawCache,
    ) { up { it.copy(keepRawCache = !it.keepRawCache) } }
}

@Composable
private fun LookTab(s: AppSettings, up: ((AppSettings) -> AppSettings) -> Unit) {
    ToggleSetting(
        "Remove light pollution gradient", "flatten uneven sky background",
        "Streetlights and towns make one part of the sky brighter than another, which looks like a " +
            "colour wash across the image. This measures that gradient across the frame and " +
            "subtracts it, leaving an even background. Turn it off for a genuinely dark site, or " +
            "if a large bright object fills the frame.",
        s.gradientRemoval,
    ) { up { it.copy(gradientRemoval = !it.gradientRemoval) } }

    ChipsSetting(
        "Stretch", "how hard the faint detail is pulled up",
        "A raw astro stack is almost entirely black — the interesting material sits in the bottom " +
            "few percent of the brightness range. Stretching brightens the faint parts far more " +
            "than the bright ones, so nebulosity appears without turning stars into white blobs. " +
            "Subtle looks natural, Strong digs deepest but also lifts the noise.",
        listOf("Subtle", "Normal", "Strong"),
        s.stretchStrength,
    ) { i -> up { it.copy(stretchStrength = i) } }

    ChipsSetting(
        "Style", if (s.style == 0) "natural color" else "nightfall grade",
        "Natural keeps honest color — real star temperatures, real dust tones — and is what you " +
            "want if you edit on a PC later (the 16-bit TIFF master is always saved un-graded " +
            "either way). Nightfall applies the cool purple-blue look you see in viral phone " +
            "astro shots: blue-shifted color, deeper blacks. Taste, not science — and you can " +
            "re-stack any kept session with the other style in seconds.",
        listOf("Natural", "Nightfall"),
        s.style,
    ) { i -> up { it.copy(style = i) } }

    SliderSetting(
        "Colour saturation", "%.2f x".format(s.saturation),
        "Star colour is real information, not decoration — hot stars are genuinely blue and cool " +
            "ones orange, and the Milky Way has real dust tones. Stacking preserves that colour, " +
            "and this brings it out.",
        s.saturation, 1f..1.6f,
    ) { v -> up { it.copy(saturation = v) } }

    ToggleSetting(
        "Colour noise reduction", "smooth colour blotches only",
        "Blurs the colour information while leaving brightness detail untouched. Your eyes are far " +
            "less sensitive to colour noise than to brightness noise, so this removes the ugly " +
            "red/green mottling without softening a single star.",
        s.chromaDenoise,
    ) { up { it.copy(chromaDenoise = !it.chromaDenoise) } }

    ToggleSetting(
        "Sharpen stars", "gentle, stars only",
        "Applies mild sharpening only where stars are, never to the background. Off by default " +
            "because it is easy to overdo and make an image look crunchy and artificial.",
        s.starSharpen,
    ) { up { it.copy(starSharpen = !it.starSharpen) } }

    ToggleSetting(
        "Blend sharp foreground", "separate landscape stack (experimental)",
        "Aligning frames to the stars means the landscape moves instead, so it comes out blurred. " +
            "This stacks the ground separately without alignment, finds the horizon, and blends " +
            "the two — sharp stars above, sharp landscape below. Automatically on in Insane mode. " +
            "Still experimental, and switched off at full resolution because it needs too much memory.",
        s.foregroundComposite,
    ) { up { it.copy(foregroundComposite = !it.foregroundComposite) } }
}

@Composable
private fun MotionTab(s: AppSettings, up: ((AppSettings) -> AppSettings) -> Unit) {
    SliderSetting(
        "Stillness required to start", "%.3f rad/s".format(s.gyroStillThreshold),
        "The app refuses to begin until the gyroscope says the phone has been still for a moment. " +
            "Lower is stricter. Raise it if it stubbornly refuses to start on a windy night or a " +
            "slightly springy tripod.",
        s.gyroStillThreshold, 0.002f..0.02f,
    ) { v -> up { it.copy(gyroStillThreshold = v) } }

    SliderSetting(
        "Knock sensitivity", "%.3f rad/s".format(s.gyroBumpThreshold),
        "Movement above this during an exposure marks that frame as bumped, so it gets thrown out " +
            "instead of smearing the stack. Lower rejects more aggressively.",
        s.gyroBumpThreshold, 0.005f..0.05f,
    ) { v -> up { it.copy(gyroBumpThreshold = v) } }

    SliderSetting(
        "Star trails length", Fmt.seconds(s.trailsMinutes * 60),
        "How long TRAILS mode keeps shooting. The arcs get longer the more time you give it — " +
            "15 minutes gives short arcs, an hour gives dramatic sweeping circles. Bring a battery.",
        s.trailsMinutes.toFloat(), 5f..60f,
    ) { v -> up { it.copy(trailsMinutes = v.toInt()) } }

    SliderSetting(
        "Meteor watch length", Fmt.seconds(s.meteorMinutes * 60),
        "How long METEOR mode keeps watching. Meteors are rare and random, so this is a numbers " +
            "game — the longer it runs, the more chances of catching one.",
        s.meteorMinutes.toFloat(), 10f..120f,
    ) { v -> up { it.copy(meteorMinutes = v.toInt()) } }
}

@Composable
private fun InterfaceTab(s: AppSettings, up: ((AppSettings) -> AppSettings) -> Unit) {
    ToggleSetting(
        "Night vision (red)", "protect your dark adaptation",
        "Turns the whole interface deep red. Your eyes take around 20 minutes to fully adapt to " +
            "darkness and a single glance at a white screen undoes it. Red light barely affects " +
            "that adaptation, so you can check settings without blinding yourself.",
        s.nightMode,
    ) { up { it.copy(nightMode = !it.nightMode) } }

    if (s.nightMode) {
        SliderSetting(
            "Screen dimming", "%.0f%%".format(s.nightDim * 100),
            "Dims the display beyond the system's own minimum. Even a dim red screen is bright " +
                "next to a genuinely dark sky.",
            s.nightDim, 0f..0.8f,
        ) { v -> up { it.copy(nightDim = v) } }
    }

    ToggleSetting(
        "Spirit level", "tilt readout while framing (off by default)",
        "Shows the phone's tilt in degrees on the capture screen — green when level. Pointless " +
            "for pure sky shots (no horizon in frame, and the Milky Way runs diagonally anyway), " +
            "which is why it is off by default. Turn it on for landscape-astro compositions: at " +
            "night the ground is often too dark to judge in the preview, and a two-degree tilt " +
            "you cannot see is the classic ruined-horizon shot.",
        s.showLevel,
    ) { up { it.copy(showLevel = !it.showLevel) } }

    ToggleSetting(
        "Vibration feedback", "buzz as frames complete",
        "A short buzz each time a frame finishes, so you know the session is progressing without " +
            "looking at the screen.",
        s.haptics,
    ) { up { it.copy(haptics = !it.haptics) } }
}

/**
 * Camera behaviour switches for chasing a field failure at home with the Diagnostics camera
 * test. The defaults are what a normal session should use; each switch isolates one suspect.
 */
@Composable
private fun DebugTab(s: AppSettings, up: ((AppSettings) -> AppSettings) -> Unit) {
    Text(
        "For tracking down camera problems. Leave these at their defaults for normal shooting; " +
            "flip one at a time and run the camera test (quick bar → more → logs) to see what " +
            "changes. Everything is logged either way.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    ToggleSetting(
        "Repair the camera when a frame fails", "on — abort, fresh session, then reopen",
        "When a frame fails, the app first repairs the camera and then retries: it cancels " +
            "anything stuck, then rebuilds the capture session, then closes and reopens the " +
            "camera completely. If the camera cannot be brought back, the frames already shot " +
            "are still stacked. Off reproduces the old behaviour — one immediate retry, then " +
            "the session ends — which is only useful for comparing.",
        s.cameraRecovery,
    ) { up { it.copy(cameraRecovery = !it.cameraRecovery) } }

    ToggleSetting(
        "Vignetting map on every frame", "off — only the first test frame asks for it",
        "The lens's vignetting map is only needed once per session, so only the probe frame " +
            "asks the camera for it. Version 0.1 asked on every long exposure, and the field " +
            "failures started with the build that added it. On reproduces that behaviour, to " +
            "test whether it is the cause.",
        s.debugShadingEveryFrame,
    ) { up { it.copy(debugShadingEveryFrame = !it.debugShadingEveryFrame) } }

    ToggleSetting(
        "Set frame duration explicitly", "off — the camera derives it from the exposure",
        "Normally the camera stretches each frame to fit the exposure on its own. Some camera " +
            "drivers handle long exposures better when the frame duration is also requested " +
            "explicitly. On sets it to the exposure time.",
        s.debugFrameDuration,
    ) { up { it.copy(debugFrameDuration = !it.debugFrameDuration) } }

    ToggleSetting(
        "Keep the viewfinder stream while shooting", "on — the capture session also feeds the preview",
        "During a session the viewfinder is frozen anyway, but its output stays part of the " +
            "camera session. When the screen turns off, that output dies while still being part " +
            "of the session. Off shoots with a RAW-only session instead, which takes the " +
            "viewfinder out of the picture entirely.",
        s.debugPreviewInSession,
    ) { up { it.copy(debugPreviewInSession = !it.debugPreviewInSession) } }

    ToggleSetting(
        "Fresh camera session after long exposures", "on — the fix for the October failures",
        "This phone's camera driver gets stuck on whatever is asked of it right after a long " +
            "exposure: the request never comes back, and after a minute everything on that " +
            "camera session fails. That is why every field session died at frame 2. Rebuilding " +
            "the session after each long exposure avoids it completely (tested: 3 of 3 long " +
            "frames on time, against 1 of 3 without) for about a fifth of a second per frame. " +
            "Off only to reproduce the old behaviour.",
        s.freshSessionAfterLong,
    ) { up { it.copy(freshSessionAfterLong = !it.freshSessionAfterLong) } }

    ToggleSetting(
        "Detailed camera log", "on — every camera callback is logged",
        "Logs the camera's minor events too (exposure started, idle/active) with timings. The " +
            "log stays small either way; there is no reason to switch this off while the camera " +
            "is being investigated.",
        s.verboseCameraLog,
    ) { up { it.copy(verboseCameraLog = !it.verboseCameraLog) } }
}

// ---------------------------------------------------------------- row building blocks

/** Label + optional one-line summary + an (i) that expands [help]. */
@Composable
private fun SettingHeader(label: String, summary: String?, help: String, trailing: @Composable (() -> Unit)? = null) {
    var showHelp by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .size(26.dp)
                            .clickable { showHelp = !showHelp },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = "What does \"$label\" do?",
                            modifier = Modifier.size(16.dp),
                            tint = if (showHelp) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (summary != null) {
                    Text(
                        summary,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing?.invoke()
        }
        if (showHelp) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(10.dp),
                    )
                    .padding(12.dp)
            ) {
                Text(
                    help,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ToggleSetting(
    label: String, summary: String?, help: String, checked: Boolean, onToggle: () -> Unit,
) {
    SettingHeader(label, summary, help) {
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun SliderSetting(
    label: String, summary: String?, help: String, value: Float,
    range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit,
) {
    SettingHeader(label, summary, help)
    Slider(value = value, onValueChange = onChange, valueRange = range)
}

@Composable
private fun ChipsSetting(
    label: String, summary: String?, help: String, options: List<String>, selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    SettingHeader(label, summary, help)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEachIndexed { i, opt ->
            FilterChip(
                selected = i == selectedIndex,
                onClick = { onSelect(i) },
                label = { Text(opt, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
            )
        }
    }
}
