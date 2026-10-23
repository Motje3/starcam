package com.wm.starcam.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.wm.starcam.StarCamViewModel
import com.wm.starcam.engine.EditParams
import com.wm.starcam.engine.NativeEngine
import com.wm.starcam.session.stackPhoto
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.components.ScGlass
import com.wm.starcam.ui.components.ScLabel
import com.wm.starcam.ui.components.ScNotice
import com.wm.starcam.ui.components.ScScreen
import com.wm.starcam.ui.components.ScSegmented
import com.wm.starcam.ui.components.ScSlider
import com.wm.starcam.ui.components.ScSpinner
import com.wm.starcam.ui.components.ScSwitch
import com.wm.starcam.ui.components.ScTopBar
import com.wm.starcam.ui.components.Tone
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.ui.theme.ScreenMargin
import com.wm.starcam.ui.theme.scImageFilter
import com.wm.starcam.util.AppLog
import com.wm.starcam.util.MediaSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** Long edge of the studio's working preview: sharp on the phone's screen, quick to develop. */
private const val PREVIEW_EDGE = 1600

/**
 * One open master. Native calls are serialized, and the handle is closed only once none is
 * running, so leaving the screen during a preview never frees memory a develop is still using.
 */
private class StudioSession(private var handle: Long) {
    private val lock = Any()

    fun <T> use(block: (Long) -> T): T? = synchronized(lock) {
        if (handle == 0L) null else block(handle)
    }

    fun close() = synchronized(lock) {
        if (handle != 0L) NativeEngine.studioClose(handle)
        handle = 0L
    }
}

private data class StudioInfo(val previewWidth: Int, val previewHeight: Int)

/** What opening a master yields; the session is closed by whoever owns this. */
private class Opened(
    val session: StudioSession,
    val info: StudioInfo,
    val upright: Boolean,
    val defaults: EditParams,
    val start: EditParams,
    val original: Bitmap?,
)

private fun develop(s: StudioSession, info: StudioInfo, p: EditParams, upright: Boolean): Bitmap? {
    val turned = p.rotation == 90 || p.rotation == 270
    val bmp = Bitmap.createBitmap(
        if (turned) info.previewHeight else info.previewWidth,
        if (turned) info.previewWidth else info.previewHeight,
        Bitmap.Config.ARGB_8888,
    )
    val ok = s.use { NativeEngine.studioRender(it, p.toOptionsJson(upright), bmp) } == true
    return if (ok) bmp else null
}

private fun turned(b: Bitmap, rotation: Int): Bitmap =
    if (rotation == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height,
        Matrix().apply { postRotate(rotation.toFloat()) }, true)

/**
 * The editing studio: a session's linear master developed again with the user's own settings
 * (brightness of sky and landscape, contrast, colour, light pollution, noise), previewed live and
 * saved as a new photo beside the original, which stays as it was. The settings are kept with the
 * session, so the studio reopens where it was left.
 */
@Composable
fun StudioScreen(dir: File, vm: StarCamViewModel, onBack: () -> Unit) {
    val c = Sc.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val master = remember(dir) { File(dir, "out/master_linear16.tiff") }
    val editFile = remember(dir) { File(dir, "out/edit.json") }

    var session by remember { mutableStateOf<StudioSession?>(null) }
    var info by remember { mutableStateOf<StudioInfo?>(null) }
    var upright by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var defaults by remember { mutableStateOf(EditParams()) }
    var params by remember { mutableStateOf(EditParams()) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    // The photo as the session made it, as the master is stored (unturned).
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var comparing by remember { mutableStateOf(false) }
    var developing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var savedMessage by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // Opens the master and owns the native handle for as long as the screen shows: closed when
    // it goes, also if that happens while the (uncancellable) open is still reading the file.
    LaunchedEffect(dir) {
        var opened: StudioSession? = null
        try {
            val o = withContext(Dispatchers.Default) {
                val header = vm.repo.readSessionJson(dir)?.optJSONObject("header")
                val cfg = header?.optJSONObject("stackConfig")
                // A session that recorded the phone's attitude has its master written upright.
                val up = (cfg?.optInt("rotation", -1) ?: -1) >= 0 && cfg?.optBoolean("rotationGuessed") != true
                // Star trails are finished without gradient removal (engine.cpp): so is their edit.
                val dflt = if (header?.optString("mode") == "TRAILS") EditParams(gradient = 0f) else EditParams()
                val saved = runCatching { EditParams.fromJson(JSONObject(editFile.readText())) }.getOrNull()
                val h = if (master.isFile) NativeEngine.studioOpen(master.absolutePath, PREVIEW_EDGE) else 0L
                if (h == 0L) return@withContext null
                val s = StudioSession(h).also { opened = it }
                val io = JSONObject(NativeEngine.studioInfo(h))
                val i = StudioInfo(io.optInt("previewWidth"), io.optInt("previewHeight"))
                // "Before" is the photo the session made; developed defaults if it is gone.
                val orig = stackPhoto(dir).takeIf { it.isFile }?.let { decodeDownsampled(it.absolutePath, PREVIEW_EDGE) }
                    ?: develop(s, i, dflt, up)
                AppLog.i("studio", "opened ${dir.name} (${io.optInt("width")}x${io.optInt("height")})")
                Opened(s, i, up, dflt, saved ?: dflt, orig)
            }
            if (o == null) {
                failed = if (master.isFile) "The photo's master could not be read." else
                    "This session has no master to edit: it was not stacked on the phone."
                return@LaunchedEffect
            }
            upright = o.upright
            defaults = o.defaults
            original = o.original
            info = o.info
            params = o.start
            session = o.session
            awaitCancellation()
        } finally {
            // Off the main thread: closing waits for a develop still running.
            opened?.let { s -> vm.viewModelScope.launch(Dispatchers.Default) { s.close() } }
        }
    }
    // Develops the preview after every change; a newer change cancels the wait of the older one.
    LaunchedEffect(params, session) {
        val s = session ?: return@LaunchedEffect
        val i = info ?: return@LaunchedEffect
        delay(80)
        developing = true
        val img = withContext(Dispatchers.Default) { develop(s, i, params, upright) }
        if (img != null) preview = img.asImageBitmap()
        developing = false
    }
    // The original, turned the way the edit is.
    val before = remember(original, params.rotation) { original?.let { turned(it, params.rotation).asImageBitmap() } }
    BackHandler(enabled = saving) { /* the photo is being written; leaving would lose it */ }

    fun save() {
        val s = session ?: return
        val p = params
        saving = true
        savedMessage = null
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                // Its own file: two saves never share one, and nothing is left in the session.
                val rgb = runCatching { File.createTempFile("edit_rgb", ".raw", context.cacheDir) }.getOrNull()
                    ?: return@withContext "Could not write the photo (storage full?)." to false
                try {
                    val res = JSONObject(
                        s.use { NativeEngine.studioExport(it, p.toOptionsJson(upright), rgb.absolutePath) }
                            ?: """{"ok":false,"error":"closed"}"""
                    )
                    if (!res.optBoolean("ok")) {
                        return@withContext "Could not develop the photo: ${res.optString("error")}" to false
                    }
                    val json = vm.repo.readSessionJson(dir)
                    val header = json?.optJSONObject("header")
                    val used = json?.optJSONObject("result")?.optInt("framesUsed", 0) ?: 0
                    val expNs = header?.optLong("exposureNs") ?: 0L
                    val jpg = File(dir, "out/starcam_${dir.name}_edit.jpg")
                    val saved = MediaSaver.saveFinal(
                        context, rgb, res.optInt("width"), res.optInt("height"), jpg,
                        totalExposureSec = used * (expNs / 1e9), iso = header?.optInt("iso") ?: 0,
                        recipe = "StarCam studio edit",
                    )
                    runCatching { editFile.writeText(p.toJson()) }
                    AppLog.i("studio", "saved ${jpg.name} (gallery: ${saved.galleryUri ?: "FAILED"}) ${p.toJson()}")
                    when {
                        saved.galleryUri != null -> "Saved to the gallery as a new photo. The original is unchanged." to true
                        saved.jpegWritten -> "Saved in the session; the gallery copy failed." to false
                        else -> "Could not write the photo (storage full?)." to false
                    }
                } finally {
                    rgb.delete()
                }
            }
            savedMessage = result
            saving = false
        }
    }

    ScScreen {
        ScTopBar(
            title = "Edit",
            subtitle = dir.name,
            onBack = if (saving) null else onBack,
            actions = {
                if (session != null) {
                    ScButton("Reset", { params = defaults.copy(rotation = params.rotation) },
                        kind = ScButtonKind.Text, enabled = !saving, height = 40.dp)
                }
            },
        )
        failed?.let {
            ScNotice(it, tone = Tone.Warning, icon = ScIcons.Info,
                modifier = Modifier.padding(horizontal = ScreenMargin))
            return@ScScreen
        }
        // ---- the picture: hold it to see the photo before these changes
        Box(
            Modifier.fillMaxWidth().weight(1f).background(c.sunken)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        comparing = true
                        tryAwaitRelease()
                        comparing = false
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            val shown = if (comparing) before ?: preview else preview
            if (shown != null) {
                Image(shown, if (comparing) "Before" else "Edited photo", Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit, colorFilter = scImageFilter)
            } else {
                ScSpinner(size = 28.dp)
            }
            if (developing || saving) {
                ScSpinner(Modifier.align(Alignment.TopEnd).padding(12.dp), size = 18.dp)
            }
            ScGlass(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Text(if (comparing) "BEFORE" else "HOLD TO COMPARE", style = ScType.label, color = c.text)
            }
        }
        // ---- the controls
        Column(Modifier.fillMaxWidth().padding(horizontal = ScreenMargin, vertical = 10.dp)) {
            ScSegmented(listOf("Light", "Colour", "Clean-up", "Frame"), tab, { tab = it },
                Modifier.fillMaxWidth(), enabled = session != null && !saving)
            Column(
                Modifier.fillMaxWidth().height(232.dp).verticalScroll(rememberScrollState())
                    .padding(top = 8.dp),
            ) {
                val enabled = session != null && !saving
                when (tab) {
                    0 -> {
                        StudioSlider("Sky brightness", pct(params.sky / 0.16f), params.sky, 0.05f..0.32f, enabled) {
                            params = params.copy(sky = it)
                        }
                        StudioSlider("Contrast", pct(params.contrast / 0.6f), params.contrast, 0f..1f, enabled) {
                            params = params.copy(contrast = it)
                        }
                        StudioSlider("Landscape brightness", pct(params.ground / 0.07f), params.ground, 0f..0.22f, enabled) {
                            params = params.copy(ground = it)
                        }
                    }
                    1 -> {
                        Column(Modifier.padding(bottom = 5.dp)) {
                            ScLabel("Style")
                            ScSegmented(listOf("Natural", "Nightfall"), params.style,
                                { params = params.copy(style = it) },
                                Modifier.fillMaxWidth().padding(top = 6.dp), enabled = enabled)
                        }
                        StudioSlider("Saturation", pct(params.saturation / 1.25f), params.saturation, 0.3f..2.2f, enabled) {
                            params = params.copy(saturation = it)
                        }
                        StudioSlider("Star colour", pct(params.starColor / 0.4f), params.starColor, 0f..1.4f, enabled) {
                            params = params.copy(starColor = it)
                        }
                        StudioSlider("Warmth", signed(params.warmth), params.warmth, -1f..1f, enabled) {
                            params = params.copy(warmth = it)
                        }
                        StudioSlider("Tint", signed(params.tint), params.tint, -1f..1f, enabled) {
                            params = params.copy(tint = it)
                        }
                    }
                    2 -> {
                        StudioSlider("Light pollution removal", pct(params.gradient), params.gradient, 0f..1f, enabled) {
                            params = params.copy(gradient = it)
                        }
                        StudioSlider("Noise reduction", pct(params.noise), params.noise, 0f..1f, enabled) {
                            params = params.copy(noise = it)
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Sharpen stars", style = ScType.body, color = c.text, modifier = Modifier.weight(1f))
                            ScSwitch(params.sharpen, { params = params.copy(sharpen = it) }, enabled = enabled)
                        }
                    }
                    else -> {
                        Text(
                            "Turn the photo if it came out sideways (photos from before 0.4 were saved " +
                                "the way the sensor lies). Saving keeps the turn.",
                            style = ScType.bodySmall, color = c.text2,
                        )
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ScButton("Turn left", { params = params.copy(rotation = (params.rotation + 270) % 360) },
                                Modifier.weight(1f), icon = ScIcons.RotateLeft, enabled = enabled)
                            ScButton("Turn right", { params = params.copy(rotation = (params.rotation + 90) % 360) },
                                Modifier.weight(1f), icon = ScIcons.RotateRight, enabled = enabled)
                        }
                    }
                }
            }
            savedMessage?.let { (text, good) ->
                Text(text, style = ScType.bodySmall, color = if (good) c.accentText else c.warning,
                    modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
            ScButton(
                if (saving) "Saving…" else "Save as new photo", ::save, Modifier.fillMaxWidth(),
                kind = ScButtonKind.Primary, icon = ScIcons.Check,
                enabled = session != null && !saving, height = 52.dp,
            )
        }
    }
}

@Composable
private fun StudioSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    val c = Sc.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = ScType.body, color = c.text, modifier = Modifier.weight(1f))
            Text(valueText, style = ScType.data, color = c.text2)
            Spacer(Modifier.width(2.dp))
        }
        ScSlider(value, onChange, range, Modifier.fillMaxWidth(), enabled = enabled)
    }
}

private fun pct(fraction: Float): String = "${(fraction * 100f).roundToInt()}%"

private fun signed(v: Float): String =
    if (v == 0f) "0" else String.format(Locale.US, "%+d", (v * 100f).roundToInt())
