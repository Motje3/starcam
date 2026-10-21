package com.wm.starcam.session

import android.content.Context
import android.content.SharedPreferences
import com.wm.starcam.engine.StackConfig
import com.wm.starcam.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Capture modes. Order matters (persisted as ordinal, matches native SessionMode). */
enum class Mode { ASTRO, TRAILS, METEOR, PRO }

/** Astro quality presets (PLAN.md §3.4). */
enum class Quality(val frames: Int, val darks: Int, val foreground: Boolean) {
    FAST(8, 0, false), BEST(20, 4, false), INSANE(40, 4, true)
}

/**
 * Every toggle in the app (PLAN.md §8 Pro panel). One immutable snapshot; SettingsStore persists
 * it and exposes it as a StateFlow for Compose.
 */
data class AppSettings(
    val mode: Mode = Mode.ASTRO,
    val quality: Quality = Quality.BEST,
    // Exposure
    val manualExposure: Boolean = false,
    val exposureNs: Long = 8_000_000_000L,
    val iso: Int = 800,
    val frameCount: Int = 20,
    val startDelaySec: Int = 3,
    /** When true the per-frame cap comes from the lens (NPF rule) instead of [trailCapSec]. */
    val autoTrailLimit: Boolean = true,
    val trailCapSec: Float = 8f,
    // Focus
    val focusMode: FocusMode = FocusMode.AUTO_STAR,
    val manualFocusDiopters: Float = 0f,
    /** Skip the ~30s star sweep when a good focus was found within the last 90 minutes. */
    val reuseLastFocus: Boolean = true,
    // Capture
    val fullResRaw: Boolean = false,
    val saveDngs: Boolean = true,
    val darksEnabled: Boolean = true,
    val darkCount: Int = 4,
    /** Reuse a recent master dark at the same exposure/ISO instead of shooting a new set. */
    val reuseDarks: Boolean = true,
    /** How old a library dark may be before it is shot fresh instead. */
    val darkLibraryDays: Int = 14,
    val autoReshoot: Boolean = true,
    val cooldownSec: Float = 0.5f,
    // Stacking
    val stackOnDevice: Boolean = true,
    val sigmaFinal: Float = 2.8f,
    val hotPixelFix: Boolean = true,
    /** Divide out lens vignetting with the camera's own shading map before stacking. */
    val lensShading: Boolean = true,
    val livePreview: Boolean = true,
    // Default ON so re-stacking works out of the box; SessionRepository auto-prunes old caches.
    val keepRawCache: Boolean = true,
    /** Camera id override; "" = auto (main sensor). Set by the lens picker (e.g. ultrawide). */
    val lensId: String = "",
    // Post
    val gradientRemoval: Boolean = true,
    val stretchStrength: Int = 1,
    val saturation: Float = 1.25f,
    val chromaDenoise: Boolean = true,
    val starSharpen: Boolean = false,
    /** 0 = natural color (default), 1 = "nightfall" purple-blue grade (opt-in). */
    val style: Int = 0,
    val foregroundComposite: Boolean = false,
    // Motion
    val gyroStillThreshold: Float = 0.006f,
    val gyroBumpThreshold: Float = 0.02f,
    // Long modes
    val trailsMinutes: Int = 20,
    val meteorMinutes: Int = 30,
    // UI
    val nightMode: Boolean = false,
    val nightDim: Float = 0.35f,
    val haptics: Boolean = true,
    /** Spirit level readout on the capture screen. Only useful for landscape-astro framing. */
    val showLevel: Boolean = false,
    // Camera debugging (Settings → Advanced). The defaults are the production behaviour; each
    // switch exists to A/B one suspect at home when the camera misbehaves in the field.
    /** When a frame fails: abort → fresh session → reopen the camera, instead of one plain retry. */
    val cameraRecovery: Boolean = true,
    /** Ask for the lens shading map on every frame (the 0.1 behaviour) instead of the probe only. */
    val debugShadingEveryFrame: Boolean = false,
    /** Set SENSOR_FRAME_DURATION to the exposure time on manual captures. */
    val debugFrameDuration: Boolean = false,
    /** Keep the viewfinder output in the capture session while shooting (off = RAW-only). */
    val debugPreviewInSession: Boolean = true,
    /**
     * Rebuild the capture session after every long exposure. The fix for the field failure (see
     * CameraController.freshSessionAfterLong); a switch only so it can still be A/B tested.
     */
    val freshSessionAfterLong: Boolean = true,
    /** Log every camera callback (exposure started, session idle/active), not only the essentials. */
    val verboseCameraLog: Boolean = true,
) {
    fun stackConfig(): StackConfig = StackConfig(
        sigmaFinal = sigmaFinal,
        hotPixelFix = hotPixelFix,
        lensShading = lensShading,
        gradientRemoval = gradientRemoval,
        stretchStrength = stretchStrength,
        saturation = saturation,
        chromaDenoise = chromaDenoise,
        starSharpen = starSharpen,
        style = style,
        // Insane preset implies foreground compositing; elsewhere it follows the user toggle.
        // Suppressed at full-res: the composite holds the sky stack, the ground stack, the blend
        // and the mask simultaneously, which is ~2 GB at 50 MP and gets the app killed.
        foregroundComposite = !fullResRaw &&
            (if (mode == Mode.ASTRO) foregroundComposite || quality.foreground
            else foregroundComposite),
    )

    /** Effective light-frame count for the session. */
    fun effectiveFrames(): Int = when {
        mode == Mode.ASTRO && !manualExposure -> quality.frames
        else -> frameCount
    }
}

enum class FocusMode { AUTO_STAR, INFINITY, MANUAL }

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("starcam_settings", Context.MODE_PRIVATE)

    /** A remembered star-autofocus result; [tempC] is the battery temperature when it was found. */
    data class SavedFocus(val diopters: Float, val ageMs: Long, val tempC: Float?)

    /** Last successful star-autofocus result (state, not a setting — no recompose needed). */
    fun saveLastFocus(diopters: Float, cameraId: String, tempC: Float?) {
        prefs.edit().putFloat("lastFocusDiopters", diopters)
            .putString("lastFocusCameraId", cameraId)
            .putLong("lastFocusAtMs", System.currentTimeMillis())
            .putFloat("lastFocusTempC", tempC ?: Float.NaN).apply()
    }

    /**
     * The last good focus FOR THIS LENS, or null. Keyed by camera id: a focus position swept on
     * one lens is meaningless (and session-ruining) on another.
     */
    fun lastFocus(cameraId: String): SavedFocus? {
        if (prefs.getString("lastFocusCameraId", "") != cameraId) return null
        val d = prefs.getFloat("lastFocusDiopters", -1f)
        if (d < 0f) return null
        val t = prefs.getFloat("lastFocusTempC", Float.NaN)
        return SavedFocus(d, System.currentTimeMillis() - prefs.getLong("lastFocusAtMs", 0L),
            t.takeUnless { it.isNaN() })
    }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        save(next)
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return try {
            AppSettings(
                mode = Mode.entries[prefs.getInt("mode", d.mode.ordinal).coerceIn(0, Mode.entries.size - 1)],
                quality = Quality.entries[prefs.getInt("quality", d.quality.ordinal).coerceIn(0, Quality.entries.size - 1)],
                manualExposure = prefs.getBoolean("manualExposure", d.manualExposure),
                exposureNs = prefs.getLong("exposureNs", d.exposureNs),
                iso = prefs.getInt("iso", d.iso),
                frameCount = prefs.getInt("frameCount", d.frameCount),
                startDelaySec = prefs.getInt("startDelaySec", d.startDelaySec),
                autoTrailLimit = prefs.getBoolean("autoTrailLimit", d.autoTrailLimit),
                trailCapSec = prefs.getFloat("trailCapSec", d.trailCapSec),
                focusMode = FocusMode.entries[prefs.getInt("focusMode", d.focusMode.ordinal).coerceIn(0, FocusMode.entries.size - 1)],
                manualFocusDiopters = prefs.getFloat("manualFocusDiopters", d.manualFocusDiopters),
                reuseLastFocus = prefs.getBoolean("reuseLastFocus", d.reuseLastFocus),
                fullResRaw = prefs.getBoolean("fullResRaw", d.fullResRaw),
                saveDngs = prefs.getBoolean("saveDngs", d.saveDngs),
                darksEnabled = prefs.getBoolean("darksEnabled", d.darksEnabled),
                darkCount = prefs.getInt("darkCount", d.darkCount),
                reuseDarks = prefs.getBoolean("reuseDarks", d.reuseDarks),
                darkLibraryDays = prefs.getInt("darkLibraryDays", d.darkLibraryDays),
                autoReshoot = prefs.getBoolean("autoReshoot", d.autoReshoot),
                cooldownSec = prefs.getFloat("cooldownSec", d.cooldownSec),
                stackOnDevice = prefs.getBoolean("stackOnDevice", d.stackOnDevice),
                sigmaFinal = prefs.getFloat("sigmaFinal", d.sigmaFinal),
                hotPixelFix = prefs.getBoolean("hotPixelFix", d.hotPixelFix),
                lensShading = prefs.getBoolean("lensShading", d.lensShading),
                livePreview = prefs.getBoolean("livePreview", d.livePreview),
                // One-time migration: keepRawCache used to default false and old installs have
                // that stored, which silently disables re-stacking. Force the new default once;
                // after the next save() the user's own choice sticks.
                keepRawCache = if (!prefs.getBoolean("cacheDefaultMigrated", false)) true
                else prefs.getBoolean("keepRawCache", d.keepRawCache),
                lensId = prefs.getString("lensId", d.lensId) ?: d.lensId,
                gradientRemoval = prefs.getBoolean("gradientRemoval", d.gradientRemoval),
                stretchStrength = prefs.getInt("stretchStrength", d.stretchStrength),
                saturation = prefs.getFloat("saturation", d.saturation),
                chromaDenoise = prefs.getBoolean("chromaDenoise", d.chromaDenoise),
                starSharpen = prefs.getBoolean("starSharpen", d.starSharpen),
                style = prefs.getInt("style", d.style).coerceIn(0, 1),
                foregroundComposite = prefs.getBoolean("foregroundComposite", d.foregroundComposite),
                gyroStillThreshold = prefs.getFloat("gyroStillThreshold", d.gyroStillThreshold),
                gyroBumpThreshold = prefs.getFloat("gyroBumpThreshold", d.gyroBumpThreshold),
                trailsMinutes = prefs.getInt("trailsMinutes", d.trailsMinutes),
                meteorMinutes = prefs.getInt("meteorMinutes", d.meteorMinutes),
                nightMode = prefs.getBoolean("nightMode", d.nightMode),
                nightDim = prefs.getFloat("nightDim", d.nightDim),
                haptics = prefs.getBoolean("haptics", d.haptics),
                showLevel = prefs.getBoolean("showLevel", d.showLevel),
                cameraRecovery = prefs.getBoolean("cameraRecovery", d.cameraRecovery),
                debugShadingEveryFrame = prefs.getBoolean("debugShadingEveryFrame", d.debugShadingEveryFrame),
                debugFrameDuration = prefs.getBoolean("debugFrameDuration", d.debugFrameDuration),
                debugPreviewInSession = prefs.getBoolean("debugPreviewInSession", d.debugPreviewInSession),
                freshSessionAfterLong = prefs.getBoolean("freshSessionAfterLong", d.freshSessionAfterLong),
                verboseCameraLog = prefs.getBoolean("verboseCameraLog", d.verboseCameraLog),
            )
        } catch (t: Throwable) {
            AppLog.e("settings", "stored settings unreadable — using defaults", t)
            d
        }
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putInt("mode", s.mode.ordinal)
            .putInt("quality", s.quality.ordinal)
            .putBoolean("manualExposure", s.manualExposure)
            .putLong("exposureNs", s.exposureNs)
            .putInt("iso", s.iso)
            .putInt("frameCount", s.frameCount)
            .putInt("startDelaySec", s.startDelaySec)
            .putBoolean("autoTrailLimit", s.autoTrailLimit)
            .putFloat("trailCapSec", s.trailCapSec)
            .putInt("focusMode", s.focusMode.ordinal)
            .putFloat("manualFocusDiopters", s.manualFocusDiopters)
            .putBoolean("reuseLastFocus", s.reuseLastFocus)
            .putBoolean("fullResRaw", s.fullResRaw)
            .putBoolean("saveDngs", s.saveDngs)
            .putBoolean("darksEnabled", s.darksEnabled)
            .putInt("darkCount", s.darkCount)
            .putBoolean("reuseDarks", s.reuseDarks)
            .putInt("darkLibraryDays", s.darkLibraryDays)
            .putBoolean("autoReshoot", s.autoReshoot)
            .putFloat("cooldownSec", s.cooldownSec)
            .putBoolean("stackOnDevice", s.stackOnDevice)
            .putFloat("sigmaFinal", s.sigmaFinal)
            .putBoolean("hotPixelFix", s.hotPixelFix)
            .putBoolean("lensShading", s.lensShading)
            .putBoolean("livePreview", s.livePreview)
            .putBoolean("keepRawCache", s.keepRawCache)
            .putBoolean("cacheDefaultMigrated", true)
            .putString("lensId", s.lensId)
            .putBoolean("gradientRemoval", s.gradientRemoval)
            .putInt("stretchStrength", s.stretchStrength)
            .putFloat("saturation", s.saturation)
            .putBoolean("chromaDenoise", s.chromaDenoise)
            .putBoolean("starSharpen", s.starSharpen)
            .putInt("style", s.style)
            .putBoolean("foregroundComposite", s.foregroundComposite)
            .putFloat("gyroStillThreshold", s.gyroStillThreshold)
            .putFloat("gyroBumpThreshold", s.gyroBumpThreshold)
            .putInt("trailsMinutes", s.trailsMinutes)
            .putInt("meteorMinutes", s.meteorMinutes)
            .putBoolean("nightMode", s.nightMode)
            .putFloat("nightDim", s.nightDim)
            .putBoolean("haptics", s.haptics)
            .putBoolean("showLevel", s.showLevel)
            .putBoolean("cameraRecovery", s.cameraRecovery)
            .putBoolean("debugShadingEveryFrame", s.debugShadingEveryFrame)
            .putBoolean("debugFrameDuration", s.debugFrameDuration)
            .putBoolean("debugPreviewInSession", s.debugPreviewInSession)
            .putBoolean("freshSessionAfterLong", s.freshSessionAfterLong)
            .putBoolean("verboseCameraLog", s.verboseCameraLog)
            .apply()
    }
}
