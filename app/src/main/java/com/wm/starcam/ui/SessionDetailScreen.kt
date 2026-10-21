package com.wm.starcam.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.wm.starcam.engine.Restacker
import com.wm.starcam.engine.StackConfig
import com.wm.starcam.session.Mode
import com.wm.starcam.session.SessionRepository
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.components.ScCard
import com.wm.starcam.ui.components.ScConfirmDialog
import com.wm.starcam.ui.components.ScGlass
import com.wm.starcam.ui.components.ScLabel
import com.wm.starcam.ui.components.ScNotice
import com.wm.starcam.ui.components.ScScreen
import com.wm.starcam.ui.components.ScSegmented
import com.wm.starcam.ui.components.ScSpinner
import com.wm.starcam.ui.components.ScStatTile
import com.wm.starcam.ui.components.ScSwitch
import com.wm.starcam.ui.components.ScTopBar
import com.wm.starcam.ui.components.Tone
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.ui.theme.ScreenMargin
import com.wm.starcam.ui.theme.scImageFilter
import com.wm.starcam.util.MediaSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Result stats shown in the detail card, from session.json "result" or a fresh re-stack. */
private data class StackStats(
    val framesUsed: Int,
    val framesTotal: Int,
    val rejectionPct: Float,
    val fwhmRef: Float,
    val fwhmStack: Float,
    val elapsedMs: Long,
)

/** Everything loaded off-disk in one IO pass when the screen opens. */
private data class DetailData(
    val stats: StackStats?,
    val frameCount: Int,
    val bumpedCount: Int,
    val jpgFile: File?,
    val image: ImageBitmap?,
    val cacheCount: Int,
    /** Capture mode from the session.json header; a re-stack must reuse it (TRAILS ≠ ASTRO). */
    val mode: Mode,
    val startedAt: Long,
    val exposureNs: Long,
    val iso: Int,
    val dngCount: Int,
    val dngBytes: Long,
    val cacheBytes: Long,
)

private val StretchLabels = listOf("Subtle", "Normal", "Strong")
private val SaturationLabels = listOf("1.0×", "1.25×", "1.5×")
private val SaturationValues = listOf(1.0f, 1.25f, 1.5f)
private val RejectionLabels = listOf("Harsh", "Normal", "Gentle")
private val RejectionValues = listOf(2.0f, 2.8f, 3.5f)  // StackConfig.sigmaFinal
private val StyleLabels = listOf("Natural", "Nightfall")  // StackConfig.style

/**
 * One session: the photo (pinch to zoom), what went into it, re-stack with a different look when
 * the working files were kept, the files for a computer, and delete. Handles unfinished sessions
 * (no result, no photo) the same way.
 */
@Composable
fun SessionDetailScreen(
    dir: File,
    repo: SessionRepository,
    onBack: () -> Unit,
) {
    val c = Sc.colors
    var data by remember { mutableStateOf<DetailData?>(null) }
    var stats by remember { mutableStateOf<StackStats?>(null) }
    var jpgFile by remember { mutableStateOf<File?>(null) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }

    // Re-stack options + progress.
    var stretchIndex by remember { mutableIntStateOf(1) }
    var satIndex by remember { mutableIntStateOf(1) }
    var rejectionIndex by remember { mutableIntStateOf(1) }
    var styleIndex by remember { mutableIntStateOf(0) }
    var gradientRemoval by remember { mutableStateOf(true) }
    var starSharpen by remember { mutableStateOf(false) }
    var restacking by remember { mutableStateOf(false) }
    var progressStage by remember { mutableStateOf("") }
    var progressPct by remember { mutableIntStateOf(0) }
    var restackError by remember { mutableStateOf<String?>(null) }
    var restackDone by remember { mutableStateOf(false) }

    var showDeleteDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(dir) {
        val d = withContext(Dispatchers.IO) {
            val json = repo.readSessionJson(dir)
            val jpg = File(dir, "out").listFiles { f -> f.extension == "jpg" }?.firstOrNull()
            // Downsampled: a full-res stack is up to 50 MP, ~200 MB as one ARGB bitmap.
            val bmp = jpg?.let { decodeDownsampled(it.absolutePath, 2048) }
            val frames = json?.optJSONArray("frames")
            var bumped = 0
            if (frames != null) {
                for (i in 0 until frames.length()) {
                    if (frames.optJSONObject(i)?.optBoolean("bumped") == true) bumped++
                }
            }
            val header = json?.optJSONObject("header")
            val resultObj = json?.optJSONObject("result")
            val modeName = header?.optString("mode") ?: ""
            val dngs = File(dir, "lights").listFiles { f -> f.extension == "dng" } ?: emptyArray()
            val cache = repo.cacheFrames(dir)
            DetailData(
                stats = resultObj?.let { r ->
                    StackStats(
                        framesUsed = r.optInt("framesUsed", 0),
                        framesTotal = r.optInt("framesTotal", 0),
                        rejectionPct = r.optDouble("rejectionPct", 0.0).toFloat(),
                        fwhmRef = r.optDouble("fwhmRef", 0.0).toFloat(),
                        fwhmStack = r.optDouble("fwhmStack", 0.0).toFloat(),
                        elapsedMs = r.optLong("elapsedMs", 0L),
                    )
                },
                frameCount = frames?.length() ?: 0,
                bumpedCount = bumped,
                jpgFile = jpg,
                image = bmp?.asImageBitmap(),
                cacheCount = cache.size,
                mode = runCatching { Mode.valueOf(modeName) }.getOrDefault(Mode.ASTRO),
                startedAt = header?.optLong("startedAt") ?: dir.lastModified(),
                exposureNs = header?.optLong("exposureNs") ?: 0L,
                iso = header?.optInt("iso") ?: 0,
                dngCount = dngs.size,
                dngBytes = dngs.sumOf { it.length() },
                cacheBytes = cache.sumOf { it.length() },
            )
        }
        data = d
        stats = d.stats
        jpgFile = d.jpgFile
        image = d.image
    }

    fun runRestack(d: DetailData) {
        if (restacking) return
        val cfg = StackConfig(
            sigmaFinal = RejectionValues[rejectionIndex],
            gradientRemoval = gradientRemoval,
            stretchStrength = stretchIndex,
            saturation = SaturationValues[satIndex],
            starSharpen = starSharpen,
            style = styleIndex,
        )
        val recipe = "StarCam re-stack: stretch ${StretchLabels[stretchIndex]}, " +
            String.format(Locale.US, "sigma %.1f", RejectionValues[rejectionIndex]) +
            if (styleIndex == 1) ", nightfall" else ""
        val targetJpg = jpgFile ?: File(File(dir, "out"), "starcam_${dir.name}.jpg")
        scope.launch {
            restacking = true
            restackError = null
            restackDone = false
            progressStage = "starting"
            progressPct = 0
            val result = withContext(Dispatchers.Default) {
                Restacker.restack(
                    dir, repo.cacheFrames(dir), cfg, d.mode.ordinal
                ) { stage, percent ->
                    progressStage = stage
                    progressPct = percent
                }
            }
            if (result.ok) {
                // Engine rewrote out/final_rgb.raw — save through MediaSaver so the new jpg gets
                // EXIF and a gallery copy, then refresh the preview from the saved file.
                val newImage = withContext(Dispatchers.IO) {
                    val hdr = repo.cacheFrames(dir).firstOrNull()?.let { Restacker.readHeader(it) }
                    MediaSaver.saveFinal(
                        context = context,
                        rgbFile = File(result.finalRgbPath),
                        width = result.width,
                        height = result.height,
                        jpegOut = targetJpg,
                        totalExposureSec = result.framesUsed * ((hdr?.exposureNs ?: 0L) / 1e9),
                        iso = hdr?.iso ?: 0,
                        focalMm = 0f,
                        fNumber = 0f,
                        recipe = recipe,
                    )
                    decodeDownsampled(targetJpg.absolutePath, 2048)?.asImageBitmap()
                }
                if (newImage != null) {
                    image = newImage
                    jpgFile = targetJpg
                }
                stats = StackStats(
                    framesUsed = result.framesUsed,
                    framesTotal = result.framesTotal,
                    rejectionPct = result.rejectionPct,
                    fwhmRef = result.fwhmRef,
                    fwhmStack = result.fwhmStack,
                    elapsedMs = result.elapsedMs,
                )
                restackDone = true
            } else {
                restackError = result.error
            }
            restacking = false
        }
    }

    val d = data
    ScScreen {
        ScTopBar(
            title = d?.let { sessionTitle(it.startedAt) } ?: dir.name,
            onBack = onBack,
            subtitle = d?.let { "${modeLabel(it.mode)} · ${it.frameCount} frame${if (it.frameCount == 1) "" else "s"}" },
        )
        if (d == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ScSpinner(size = 28.dp) }
            return@ScScreen
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ZoomableImage(image)
            Column(
                Modifier.padding(horizontal = ScreenMargin, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ---- What went into it
                val st = stats
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScStatTile(
                        if (st != null) "${st.framesUsed}/${st.framesTotal}" else "${d.frameCount}",
                        if (st != null) "frames stacked" else "frames captured",
                        Modifier.weight(1f),
                    )
                    val totalSec = Math.round((st?.framesUsed ?: d.frameCount) * (d.exposureNs / 1e9))
                    ScStatTile(if (d.exposureNs > 0) durationShort(totalSec) else "-", "total exposure", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScStatTile(
                        if (d.exposureNs > 0) exposureText(d.exposureNs) else "-",
                        if (d.iso > 0) "each frame · ISO ${d.iso}" else "each frame",
                        Modifier.weight(1f),
                    )
                    ScStatTile(
                        if (st != null && st.fwhmStack > 0f) String.format(Locale.US, "%.1f px", st.fwhmStack) else "-",
                        "star size (FWHM)", Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScStatTile(
                        if (st != null) String.format(Locale.US, "%.1f%%", st.rejectionPct) else "-",
                        "rejected in stacking", Modifier.weight(1f),
                    )
                    ScStatTile("${d.bumpedCount}", "bumped (gyro)", Modifier.weight(1f))
                }
                if (st == null) {
                    ScNotice("Not stacked — the session did not finish, or stacking on the phone was off.",
                        tone = Tone.Normal, icon = ScIcons.Info)
                }

                // ---- Re-process
                ScCard(Modifier.fillMaxWidth()) {
                    Text("Re-process with different settings", style = ScType.headline, color = c.text)
                    if (d.cacheCount == 0) {
                        Text(
                            "The working files were not kept for this session, so it cannot be " +
                                "re-stacked here. Its DNGs (if saved) stack fine on a computer.",
                            style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        Text(
                            "Re-stacks from the ${d.cacheCount} kept frames and replaces this photo.",
                            style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        OptionRow("Stretch", StretchLabels, stretchIndex, !restacking) { stretchIndex = it }
                        OptionRow("Style", StyleLabels, styleIndex, !restacking) { styleIndex = it }
                        OptionRow("Saturation", SaturationLabels, satIndex, !restacking) { satIndex = it }
                        OptionRow("Rejection", RejectionLabels, rejectionIndex, !restacking) { rejectionIndex = it }
                        SwitchRow("Remove light pollution gradient", gradientRemoval, !restacking) { gradientRemoval = it }
                        SwitchRow("Sharpen stars", starSharpen, !restacking) { starSharpen = it }
                        Spacer(Modifier.height(10.dp))
                        if (restacking) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ScSpinner(size = 20.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("${progressStage.replaceFirstChar { it.uppercase() }} · $progressPct%",
                                    style = ScType.bodySmall, color = c.text2, modifier = Modifier.weight(1f))
                            }
                            Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(CircleShape).background(c.raised)) {
                                Box(Modifier.fillMaxWidth((progressPct / 100f).coerceIn(0f, 1f)).height(6.dp)
                                    .clip(CircleShape).background(c.accent))
                            }
                        } else {
                            ScButton("Re-stack", { runRestack(d) }, Modifier.fillMaxWidth(),
                                kind = ScButtonKind.Primary, icon = ScIcons.Refresh, height = 52.dp)
                        }
                        restackError?.let {
                            Text("Re-stack failed: $it", style = ScType.bodySmall, color = c.error,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                        if (restackDone && !restacking) {
                            Text("Done — the photo above and the gallery copy are updated.",
                                style = ScType.bodySmall, color = c.accentText, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }

                // ---- Files
                ScCard(Modifier.fillMaxWidth()) {
                    Text("Files", style = ScType.headline, color = c.text)
                    Spacer(Modifier.height(6.dp))
                    jpgFile?.let { FileRow("Photo · JPEG", bytesText(it.length())) }
                    if (d.dngCount > 0) FileRow("RAW · ${d.dngCount} DNG", bytesText(d.dngBytes))
                    if (d.cacheCount > 0) FileRow("Working files · ${d.cacheCount}", bytesText(d.cacheBytes))
                    Text(
                        dir.absolutePath.substringAfter("/0/"),
                        style = ScType.data, color = c.text3, modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "Over USB, copy this folder to a computer for desktop stacking — the DNGs are in lights/.",
                        style = ScType.caption, color = c.text3, modifier = Modifier.padding(top = 4.dp),
                    )
                    val jpg = jpgFile
                    if (jpg != null) {
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ScButton("Share photo", { photoUri(context, jpg)?.let { sharePhoto(context, it) } },
                                Modifier.weight(1f), icon = ScIcons.Share)
                            ScButton("Open", { photoUri(context, jpg)?.let { openPhoto(context, it) } },
                                Modifier.weight(1f), icon = ScIcons.Open)
                        }
                    }
                }

                ScButton("Delete session", { showDeleteDialog = true }, Modifier.fillMaxWidth(),
                    kind = ScButtonKind.Danger, icon = ScIcons.Trash, enabled = !restacking)
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showDeleteDialog) {
        ScConfirmDialog(
            title = "Delete this session?",
            text = "This permanently removes ${dir.name}: its RAW frames, working files and the " +
                "session's copy of the photo. The copy in your gallery stays.",
            confirmText = "Delete",
            danger = true,
            onConfirm = {
                showDeleteDialog = false
                scope.launch {
                    withContext(Dispatchers.IO) { repo.deleteSession(dir) }
                    onBack()
                }
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

/**
 * The photo, fit to width. Pinch to zoom, drag to pan once zoomed, double-tap to fit again. A
 * single finger at fit scale is left alone, so it scrolls the page instead.
 */
@Composable
private fun ZoomableImage(image: ImageBitmap?) {
    val c = Sc.colors
    if (image == null) {
        Box(Modifier.fillMaxWidth().height(200.dp).background(c.sunken), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(ScIcons.Stack, null, Modifier.size(36.dp), tint = c.text3)
                Text("No stacked photo — RAWs only", style = ScType.bodySmall, color = c.text3,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
        return
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float): Offset {
        val maxX = boxSize.width * (s - 1f) / 2f
        val maxY = boxSize.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    Box(
        Modifier.fillMaxWidth().clip(androidx.compose.ui.graphics.RectangleShape).background(c.sunken)
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (newScale <= 1f) Offset.Zero else clamp(offset + event.calculatePan(), newScale)
                            scale = newScale
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = 1f
                    offset = Offset.Zero
                })
            },
    ) {
        Image(
            image, "Stacked photo",
            Modifier.fillMaxWidth().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
            contentScale = ContentScale.FillWidth,
            colorFilter = scImageFilter,
        )
        ScGlass(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
            Icon(ScIcons.ZoomIn, null, Modifier.size(14.dp), tint = c.text2)
            Text(if (scale > 1.01f) String.format(Locale.US, "%.1f× · double-tap to fit", scale) else "Pinch to zoom",
                style = ScType.caption, color = c.text)
        }
    }
}

@Composable
private fun OptionRow(label: String, options: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(vertical = 5.dp)) {
        ScLabel(label)
        ScSegmented(options, selected, onSelect, Modifier.fillMaxWidth().padding(top = 6.dp), enabled = enabled)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = ScType.body, color = Sc.colors.text, modifier = Modifier.weight(1f))
        ScSwitch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun FileRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = ScType.body, color = Sc.colors.text, modifier = Modifier.weight(1f))
        Text(value, style = ScType.data, color = Sc.colors.text2)
    }
}
