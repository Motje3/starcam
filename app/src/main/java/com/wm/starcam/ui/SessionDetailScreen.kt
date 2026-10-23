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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.wm.starcam.StarCamViewModel
import com.wm.starcam.engine.StackConfig
import com.wm.starcam.session.Mode
import com.wm.starcam.session.SINGLE_FRAME_JPG
import com.wm.starcam.session.SessionRepository
import com.wm.starcam.session.sessionPhoto
import com.wm.starcam.session.stackPhoto
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
import com.wm.starcam.util.ShareCard
import com.wm.starcam.util.StackReplay
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
    /** What the photo was stacked with: a re-stack's own options, else the capture's. Null for old sessions. */
    val config: StackConfig?,
    /** The linear master the editing studio develops; absent when the session was not stacked. */
    val hasMaster: Boolean,
    /** One frame developed like the stack (out/single_frame.jpg), for "1 frame vs stacked". */
    val single: ImageBitmap? = null,
    /**
     * What [single] is compared with: the stack's own photo, not a studio edit, which can be
     * turned or toned differently.
     */
    val stacked: ImageBitmap? = null,
    val stackedFile: File? = null,
    /** Live-stack snapshots kept during capture, for the replay video. */
    val replayFrames: Int = 0,
    val replayBytes: Long = 0L,
)

/** Blocking; reads session.json, the photo and the file sizes. */
private fun loadDetail(dir: File, repo: SessionRepository): DetailData {
    val json = repo.readSessionJson(dir)
    // The newest: an edit or a re-stack is what the session shows from then on.
    val jpg = sessionPhoto(dir)
    // Downsampled: a full-res stack is up to 50 MP, ~200 MB as one ARGB bitmap.
    val image = jpg?.let { decodeDownsampled(it.absolutePath, 2048) }?.asImageBitmap()
    val singleFile = File(dir, "out/$SINGLE_FRAME_JPG").takeIf { it.isFile }
    val stackJpg = stackPhoto(dir).takeIf { it.isFile && singleFile != null }
    val stacked = when (stackJpg) {
        null -> null
        jpg -> image
        else -> decodeDownsampled(stackJpg.absolutePath, 2048)?.asImageBitmap()
    }
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
    val replay = StackReplay.frames(dir)
    return DetailData(
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
        image = image,
        cacheCount = cache.size,
        mode = runCatching { Mode.valueOf(modeName) }.getOrDefault(Mode.ASTRO),
        startedAt = header?.optLong("startedAt") ?: dir.lastModified(),
        exposureNs = header?.optLong("exposureNs") ?: 0L,
        iso = header?.optInt("iso") ?: 0,
        dngCount = dngs.size,
        dngBytes = dngs.sumOf { it.length() },
        cacheBytes = cache.sumOf { it.length() },
        config = (resultObj?.optJSONObject("stackConfig") ?: header?.optJSONObject("stackConfig"))
            ?.let { StackConfig.fromJson(it) },
        hasMaster = File(dir, "out/master_linear16.tiff").isFile,
        single = singleFile?.let { decodeDownsampled(it.absolutePath, 2048)?.asImageBitmap() },
        stacked = stacked,
        stackedFile = stackJpg,
        replayFrames = replay.size,
        replayBytes = replay.sumOf { it.length() },
    )
}

/** The option closest to [v]: sessions can carry values these few choices don't list. */
private fun List<Float>.nearestIndex(v: Float): Int =
    indices.minByOrNull { kotlin.math.abs(this[it] - v) } ?: 0

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
    vm: StarCamViewModel,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val c = Sc.colors
    val repo = vm.repo
    var data by remember { mutableStateOf<DetailData?>(null) }
    var loadKey by remember { mutableIntStateOf(0) }

    // Re-stack options, set from what the photo was made with once that is known.
    var stretchIndex by remember { mutableIntStateOf(1) }
    var satIndex by remember { mutableIntStateOf(1) }
    var rejectionIndex by remember { mutableIntStateOf(1) }
    var styleIndex by remember { mutableIntStateOf(0) }
    var gradientRemoval by remember { mutableStateOf(true) }
    var starSharpen by remember { mutableStateOf(false) }
    var optionsLoaded by remember { mutableStateOf(false) }

    // The re-stack itself runs in the view model: it carries on when this screen is left, and
    // coming back cannot start a second one on the same files.
    val restack by vm.restack.collectAsState()
    val mine = restack?.takeIf { it.dir == dir }
    val restacking = mine?.running == true
    val busyElsewhere = restack?.running == true && !restacking
    var refused by remember { mutableStateOf(false) }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    var scrollUpOnLoad by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(dir, loadKey) {
        val d = withContext(Dispatchers.IO) { loadDetail(dir, repo) }
        data = d
        if (scrollUpOnLoad) {
            scrollUpOnLoad = false
            scroll.animateScrollTo(0)
        }
        if (!optionsLoaded) {
            optionsLoaded = true
            d.config?.let { cfg ->
                stretchIndex = cfg.stretchStrength.coerceIn(0, StretchLabels.lastIndex)
                styleIndex = cfg.style.coerceIn(0, StyleLabels.lastIndex)
                satIndex = SaturationValues.nearestIndex(cfg.saturation)
                rejectionIndex = RejectionValues.nearestIndex(cfg.sigmaFinal)
                gradientRemoval = cfg.gradientRemoval
                starSharpen = cfg.starSharpen
            }
        }
    }

    // A re-stack that finishes while the screen is open brings in its photo and numbers.
    var watching by remember { mutableStateOf(false) }
    LaunchedEffect(restacking) {
        if (restacking) {
            watching = true
        } else if (watching) {
            watching = false
            scrollUpOnLoad = true
            loadKey++
        }
    }
    // Its outcome is said once: leaving the screen forgets a finished re-stack.
    DisposableEffect(dir) { onDispose { vm.clearRestack(dir) } }

    fun runRestack() {
        val recipe = "StarCam re-stack: stretch ${StretchLabels[stretchIndex]}, " +
            String.format(Locale.US, "sigma %.1f", RejectionValues[rejectionIndex]) +
            if (styleIndex == 1) ", nightfall" else ""
        val stretch = stretchIndex
        val saturation = SaturationValues[satIndex]
        val sigma = RejectionValues[rejectionIndex]
        val style = styleIndex
        val gradient = gradientRemoval
        val sharpen = starSharpen
        refused = !vm.startRestack(
            dir,
            adjust = {
                it.copy(
                    sigmaFinal = sigma, gradientRemoval = gradient, stretchStrength = stretch,
                    saturation = saturation, starSharpen = sharpen, style = style,
                )
            },
            recipe = recipe,
        )
    }

    val d = data
    ScScreen {
        ScTopBar(
            title = d?.let { sessionTitle(dir.name, it.startedAt) } ?: dir.name,
            onBack = onBack,
            subtitle = d?.let { "${modeLabel(it.mode)} · ${it.frameCount} frame${if (it.frameCount == 1) "" else "s"}" },
        )
        if (d == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ScSpinner(size = 28.dp) }
            return@ScScreen
        }
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            val single = d.single
            val stacked = d.stacked
            if (comparing && single != null && stacked != null) {
                CompareSlider(single, stacked, "1 frame", "Stacked · ${d.stats?.framesUsed ?: d.frameCount}")
            } else {
                ZoomableImage(d.image)
            }
            Column(
                Modifier.padding(horizontal = ScreenMargin, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // A finished re-stack is announced up here, under its new photo: the page scrolls
                // up to it, and a portrait photo replacing a placeholder used to push a message
                // down by the button out of sight.
                if (mine != null && mine.finished && mine.error == null) {
                    ScNotice(
                        if (mine.galleryFailed) "Done: the photo above is updated, but the gallery copy failed."
                        else "Done: the photo above is updated, and the new version is in the gallery.",
                        tone = if (mine.galleryFailed) Tone.Warning else Tone.Accent, icon = ScIcons.CheckCircle,
                    )
                }
                if (single != null && stacked != null) {
                    ScSegmented(listOf("Photo", "1 frame vs stacked"), if (comparing) 1 else 0,
                        { comparing = it == 1 }, Modifier.fillMaxWidth())
                    val jpg = d.stackedFile
                    if (comparing && jpg != null) {
                        val used = d.stats?.framesUsed ?: d.frameCount
                        ScButton("Share 1 frame vs stacked", {
                            scope.launch {
                                val card = withContext(Dispatchers.IO) {
                                    ShareCard.compare(context, File(dir, "out/$SINGLE_FRAME_JPG"), jpg,
                                        "Stacked · $used", compareCaption(used, d.exposureNs, d.iso))
                                }
                                card?.let { photoUri(context, it) }?.let { sharePhoto(context, it) }
                            }
                        }, Modifier.fillMaxWidth(), icon = ScIcons.Share)
                    }
                }
                // ---- What went into it
                val st = d.stats
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

                // ---- Edit. Shown before a first stack too (disabled), so the screen does not jump
                // when the stack finishes and the card would otherwise appear above its message.
                if (d.hasMaster || d.cacheCount > 0) {
                    ScCard(Modifier.fillMaxWidth()) {
                        Text("Edit photo", style = ScType.headline, color = c.text)
                        Text(
                            if (d.hasMaster) {
                                "Sky and landscape brightness, contrast, colour, light pollution and noise, " +
                                    "previewed as you go. Saved as a new photo; this one stays."
                            } else {
                                "Once the session is stacked: sky and landscape brightness, contrast, " +
                                    "colour, light pollution and noise, previewed as you go."
                            },
                            style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 4.dp),
                        )
                        ScButton("Edit", onEdit, Modifier.fillMaxWidth().padding(top = 10.dp),
                            kind = ScButtonKind.Primary, icon = ScIcons.Edit,
                            enabled = d.hasMaster && !restacking, height = 52.dp)
                    }
                }

                // ---- The stack building up, as a video
                if (d.replayFrames >= 2) {
                    val used = st?.framesUsed ?: d.frameCount
                    ReplayCard(dir, d.replayFrames, compareCaption(used, d.exposureNs, d.iso), enabled = !restacking)
                }

                // ---- Re-process
                ScCard(Modifier.fillMaxWidth()) {
                    Text(if (st != null || d.cacheCount == 0) "Re-process with different settings" else "Stack this session",
                        style = ScType.headline, color = c.text)
                    if (d.cacheCount == 0) {
                        Text(
                            "The working files were not kept for this session, so it cannot be " +
                                "re-stacked here. Its DNGs (if saved) stack fine on a computer.",
                            style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        Text(
                            if (st != null) {
                                "Re-stacks from the ${d.cacheCount} kept frames, starting from the options " +
                                    "this photo was made with. The new photo replaces it here and is added " +
                                    "to the gallery."
                            } else {
                                "Stacks the ${d.cacheCount} kept frames into a photo, saved here and to the gallery."
                            },
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
                        if (mine != null && restacking) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ScSpinner(size = 20.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("${mine.stage.replaceFirstChar { it.uppercase() }} · ${mine.percent}%",
                                    style = ScType.bodySmall, color = c.text2, modifier = Modifier.weight(1f))
                            }
                            Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(CircleShape).background(c.raised)) {
                                Box(Modifier.fillMaxWidth((mine.percent / 100f).coerceIn(0f, 1f)).height(6.dp)
                                    .clip(CircleShape).background(c.accent))
                            }
                            Text("Carries on if you leave this screen.", style = ScType.caption, color = c.text3,
                                modifier = Modifier.padding(top = 6.dp))
                        } else {
                            ScButton(if (st != null) "Re-stack" else "Stack", ::runRestack, Modifier.fillMaxWidth(),
                                kind = ScButtonKind.Primary, icon = ScIcons.Refresh, enabled = !busyElsewhere,
                                height = 52.dp)
                            if (busyElsewhere) {
                                Text("Another session is being re-stacked; this one can start when it is done.",
                                    style = ScType.caption, color = c.text3, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                        if (refused && !restacking) {
                            Text("Not now: the camera is busy with a capture or a test.",
                                style = ScType.bodySmall, color = c.warning, modifier = Modifier.padding(top = 8.dp))
                        }
                        mine?.error?.let {
                            Text("${if (st != null) "Re-stack" else "Stacking"} failed: $it",
                                style = ScType.bodySmall, color = c.error, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }

                // ---- Files
                ScCard(Modifier.fillMaxWidth()) {
                    Text("Files", style = ScType.headline, color = c.text)
                    Spacer(Modifier.height(6.dp))
                    d.jpgFile?.let { FileRow("Photo · JPEG", bytesText(it.length())) }
                    if (d.dngCount > 0) FileRow("RAW · ${d.dngCount} DNG", bytesText(d.dngBytes))
                    if (d.cacheCount > 0) FileRow("Working files · ${d.cacheCount}", bytesText(d.cacheBytes))
                    if (d.replayFrames > 0) FileRow("Replay pictures · ${d.replayFrames}", bytesText(d.replayBytes))
                    Text(
                        dir.absolutePath.substringAfter("/0/"),
                        style = ScType.data, color = c.text3, modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "Over USB, copy this folder to a computer for desktop stacking — the DNGs are in lights/.",
                        style = ScType.caption, color = c.text3, modifier = Modifier.padding(top = 4.dp),
                    )
                    val jpg = d.jpgFile
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
