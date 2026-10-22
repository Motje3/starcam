package com.wm.starcam.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.BatteryManager
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.wm.starcam.camera.CamInfo
import com.wm.starcam.session.AppSettings
import com.wm.starcam.session.Mode
import com.wm.starcam.session.sessionPhoto
import com.wm.starcam.util.AppLog
import com.wm.starcam.util.Fmt
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

// ------------------------------------------------------------------------------- clocks

/**
 * Elapsed milliseconds since [sinceMs] (elapsedRealtime), ticking ~5× a second.
 *
 * Call it from leaf composables only: the ticker invalidates whatever reads it, so keeping it in
 * a Text-sized scope means the screen around it is not rebuilt five times a second all session.
 */
@Composable
fun rememberElapsedMs(sinceMs: Long): Long {
    var now by remember(sinceMs) { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(sinceMs) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(200)
        }
    }
    return (now - sinceMs).coerceAtLeast(0L)
}

/** Battery percentage, re-read every 30 s; -1 while unknown. */
@Composable
fun rememberBatteryPercent(): Int {
    val context = LocalContext.current
    var pct by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        while (true) {
            pct = runCatching { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1 }
                .getOrDefault(-1)
            delay(30_000)
        }
    }
    return pct
}

/**
 * Swallows every touch that reaches this layer, so an opaque screen drawn over the capture
 * screen never lets a tap fall through to the shutter underneath.
 */
fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}

// ------------------------------------------------------------------------------- formatting

/** "8.5 s", "30 s", "1/120 s": [Fmt.exposure] with the unit spaced like the rest of the UI. */
fun exposureText(ns: Long): String = Fmt.exposure(ns).removeSuffix("s") + " s"

fun exposureText(seconds: Double): String = exposureText((seconds * 1e9).toLong())

/** The comparison card's caption: "20 × 15 s · ISO 1600 · OnePlus". */
fun compareCaption(frames: Int, exposureNs: Long, iso: Int): String = listOfNotNull(
    if (exposureNs > 0) "$frames × ${exposureText(exposureNs)}" else "$frames frames",
    if (iso > 0) "ISO $iso" else null,
    android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
).joinToString(" · ")

/** "45 s", "7 min 30 s", "1 h 05 min". */
fun durationText(totalSec: Long): String {
    val t = totalSec.coerceAtLeast(0L)
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return when {
        h > 0 -> String.format(Locale.US, "%d h %02d min", h, m)
        m > 0 && s > 0 -> "$m min $s s"
        m > 0 -> "$m min"
        else -> "$s s"
    }
}

/** Short form for tiles: "7m 30s", "45s", "1h 05m". */
fun durationShort(totalSec: Long): String {
    val t = totalSec.coerceAtLeast(0L)
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return when {
        h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
        m > 0 -> if (s > 0) "${m}m ${s}s" else "${m}m"
        else -> "${s}s"
    }
}

/**
 * A session's title in the local time it was shot. The folder name (yyyyMMdd_HHmmss_mode) holds
 * the phone's wall-clock time then; the start timestamp in session.json is absolute and would be
 * shown in today's time zone, an hour or more off for a night shot while travelling.
 */
fun sessionTitle(dirName: String, fallbackMs: Long): String {
    val shot = runCatching {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { isLenient = false }.parse(dirName.take(15))
    }.getOrNull()
    return sessionTitle(shot?.time ?: fallbackMs)
}

/** "Today · 23:14", "Yesterday · 01:42", "Oct 18 · 01:42", "Oct 18 2025 · 01:42". */
fun sessionTitle(startedAtMs: Long): String {
    if (startedAtMs <= 0L) return "Session"
    val then = Calendar.getInstance().apply { timeInMillis = startedAtMs }
    val now = Calendar.getInstance()
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(startedAtMs))
    val sameYear = then.get(Calendar.YEAR) == now.get(Calendar.YEAR)
    val dayDiff = if (sameYear) now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) else -1
    val day = when {
        dayDiff == 0 -> "Today"
        dayDiff == 1 -> "Yesterday"
        sameYear -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(startedAtMs))
        else -> SimpleDateFormat("MMM d yyyy", Locale.getDefault()).format(Date(startedAtMs))
    }
    return "$day · $time"
}

fun modeLabel(mode: Mode): String = when (mode) {
    Mode.ASTRO -> "Astro"
    Mode.TRAILS -> "Trails"
    Mode.METEOR -> "Meteor"
    Mode.PRO -> "Pro"
}

/** "12.6 GB free". */
fun bytesText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%d KB", bytes / 1024)
}

// ------------------------------------------------------------------------------- the plan

/**
 * What the next session will do, for the plan line under the viewfinder. Display only, and
 * deliberately the same arithmetic as CaptureEngine/AutoExposure: the exposure follows from the
 * lens and settings alone (only ISO needs the test frame), so it can be shown before shooting.
 */
data class Plan(
    val manual: Boolean,
    val exposureSec: Double,
    /** Null when the test frame picks it. */
    val iso: Int?,
    val frames: Int,
    val darks: Int,
    /** TRAILS / METEOR run for a time rather than a frame count. */
    val minutes: Int?,
)

fun planFor(s: AppSettings, cam: CamInfo?): Plan {
    val manual = s.manualExposure || s.mode == Mode.PRO
    val maxExp = cam?.maxExposureSeconds ?: 30.0
    val minExp = (cam?.exposureRangeNs?.lower ?: 0L) / 1e9
    val exposure: Double
    var frames: Int
    if (manual) {
        exposure = (s.exposureNs / 1e9).coerceIn(minExp, maxExp)
        frames = s.effectiveFrames()
    } else {
        val cap = if (s.autoTrailLimit) cam?.trailLimitSeconds() ?: 8.0 else s.trailCapSec.toDouble()
        exposure = minOf(maxExp, cap).coerceAtLeast(minExp)
        val scale = if (exposure < cap) ceil(cap / exposure).toInt().coerceAtMost(6) else 1
        frames = (s.effectiveFrames() * scale).coerceAtMost(99)
    }
    val minutes = when (s.mode) {
        Mode.TRAILS -> s.trailsMinutes
        Mode.METEOR -> s.meteorMinutes
        else -> null
    }
    if (minutes != null && exposure > 0) {
        frames = ((minutes * 60.0) / exposure).toInt().coerceIn(10, 900)
    }
    val darks = if (s.darksEnabled && s.stackOnDevice && s.darkCount > 0 &&
        (s.mode == Mode.ASTRO || s.mode == Mode.PRO)) s.darkCount else 0
    val iso = if (manual) cam?.isoRange?.let { s.iso.coerceIn(it.lower, it.upper) } ?: s.iso else null
    return Plan(manual, exposure, iso, frames, darks, minutes)
}

/** The lens label the picker uses: 1× for the main camera, the focal ratio for the others. */
fun lensLabel(lenses: List<CamInfo>, lens: CamInfo): String {
    val idx = lenses.indexOfFirst { it.cameraId == lens.cameraId }
    val mainFocal = lenses.firstOrNull()?.focalLengthMm ?: 0f
    return when {
        idx == 0 -> "1×"
        mainFocal > 0f && lens.focalLengthMm > 0f -> {
            val r = lens.focalLengthMm / mainFocal
            if (r < 1f) String.format(Locale.US, "%.1f×", r) else String.format(Locale.US, "%.0f×", r)
        }
        else -> "cam ${lens.cameraId}"
    }
}

// ------------------------------------------------------------------------------- images

/** Decodes [path] scaled down so its longest edge is roughly [maxEdge] px. Null on failure. */
fun decodeDownsampled(path: String, maxEdge: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

/** The newest finished photo across all sessions, for the gallery button. */
fun latestPhoto(sessionsRoot: File): File? = runCatching {
    sessionsRoot.listFiles { f -> f.isDirectory }
        ?.sortedByDescending { it.name }
        ?.asSequence()
        ?.mapNotNull { dir ->
            sessionPhoto(dir)
        }
        ?.firstOrNull()
}.getOrNull()

/** A content Uri other apps may read for a photo inside the sessions folder. */
fun photoUri(context: Context, file: File): Uri? = runCatching {
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}.onFailure { AppLog.e("ui", "no shareable uri for ${file.name}", it) }.getOrNull()

fun sharePhoto(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, "Share photo"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app to share with", Toast.LENGTH_SHORT).show()
    }
}

fun openPhoto(context: Context, uri: Uri) {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/jpeg")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(view)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open photos", Toast.LENGTH_SHORT).show()
    }
}

// ------------------------------------------------------------------------------- UI prefs

/**
 * Interface-only state that is not a capture setting: whether the intro was seen, which tips
 * were dismissed. Kept apart from SettingsStore so a settings reset never replays the intro.
 */
class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("starcam_ui", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()

    var lensTipDismissed: Boolean
        get() = prefs.getBoolean("lensTipDismissed", false)
        set(v) = prefs.edit().putBoolean("lensTipDismissed", v).apply()
}
