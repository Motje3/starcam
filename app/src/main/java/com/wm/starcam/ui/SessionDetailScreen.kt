package com.wm.starcam.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wm.starcam.engine.Restacker
import com.wm.starcam.engine.StackConfig
import com.wm.starcam.session.Mode
import com.wm.starcam.session.SessionRepository
import com.wm.starcam.util.Fmt
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
)

private val StretchLabels = listOf("Subtle", "Normal", "Strong")
private val SaturationLabels = listOf("1.0", "1.25", "1.5")
private val SaturationValues = listOf(1.0f, 1.25f, 1.5f)
private val RejectionLabels = listOf("Harsh", "Normal", "Gentle")
private val RejectionValues = listOf(2.0f, 2.8f, 3.5f)  // StackConfig.sigmaFinal
private val StyleLabels = listOf("Natural", "Nightfall")  // StackConfig.style

/**
 * One session: final image, stack stats, folder path for USB export, re-stack (if the sraw cache
 * was kept) and delete. Handles unfinished sessions (no result, no jpg) gracefully.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    dir: File,
    repo: SessionRepository,
    onBack: () -> Unit,
) {
    var loaded by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<StackStats?>(null) }
    var frameCount by remember { mutableStateOf(0) }
    var bumpedCount by remember { mutableStateOf(0) }
    var jpgFile by remember { mutableStateOf<File?>(null) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var cacheCount by remember { mutableStateOf(0) }
    var sessionMode by remember { mutableStateOf(Mode.ASTRO) }

    // Re-stack options + progress.
    var showOptions by remember { mutableStateOf(false) }
    var stretchIndex by remember { mutableStateOf(1) }
    var satIndex by remember { mutableStateOf(1) }
    var rejectionIndex by remember { mutableStateOf(1) }
    var styleIndex by remember { mutableStateOf(0) }
    var gradientRemoval by remember { mutableStateOf(true) }
    var starSharpen by remember { mutableStateOf(false) }
    var restacking by remember { mutableStateOf(false) }
    var progressStage by remember { mutableStateOf("") }
    var progressPct by remember { mutableStateOf(0) }
    var restackError by remember { mutableStateOf<String?>(null) }

    var showDeleteDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(dir) {
        val d = withContext(Dispatchers.IO) {
            val json = repo.readSessionJson(dir)
            val jpg = File(dir, "out").listFiles { f -> f.extension == "jpg" }?.firstOrNull()
            val bmp = jpg?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
            val frames = json?.optJSONArray("frames")
            var bumped = 0
            if (frames != null) {
                for (i in 0 until frames.length()) {
                    if (frames.optJSONObject(i)?.optBoolean("bumped") == true) bumped++
                }
            }
            val resultObj = json?.optJSONObject("result")
            val modeName = json?.optJSONObject("header")?.optString("mode") ?: ""
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
                cacheCount = repo.cacheFrames(dir).size,
                mode = runCatching { Mode.valueOf(modeName) }.getOrDefault(Mode.ASTRO),
            )
        }
        stats = d.stats
        frameCount = d.frameCount
        bumpedCount = d.bumpedCount
        jpgFile = d.jpgFile
        image = d.image
        cacheCount = d.cacheCount
        sessionMode = d.mode
        loaded = true
    }

    fun runRestack() {
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
            progressStage = "starting"
            progressPct = 0
            val result = withContext(Dispatchers.Default) {
                Restacker.restack(
                    dir, repo.cacheFrames(dir), cfg, sessionMode.ordinal
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
                    runCatching { BitmapFactory.decodeFile(targetJpg.absolutePath) }.getOrNull()
                        ?.asImageBitmap()
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
                showOptions = false
            } else {
                restackError = result.error
            }
            restacking = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(dir.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { inner ->
        if (!loaded) {
            Box(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // --- Final image (or placeholder for RAW-only sessions) ---
                val img = image
                if (img != null) {
                    Image(
                        bitmap = img,
                        contentDescription = "Final stacked image",
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.FillWidth,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(40.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "No stacked image — RAWs only",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // --- Stats card ---
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            text = "Stack result",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        val st = stats
                        if (st != null) {
                            StatRow("Frames used", "${st.framesUsed} / ${st.framesTotal}")
                            StatRow(
                                "Rejected",
                                String.format(Locale.US, "%.1f%%", st.rejectionPct),
                            )
                            StatRow(
                                "FWHM ref → stack",
                                String.format(
                                    Locale.US, "%.2f → %.2f px", st.fwhmRef, st.fwhmStack
                                ),
                            )
                            StatRow("Stack time", Fmt.seconds((st.elapsedMs / 1000L).toInt()))
                        } else {
                            Text(
                                text = "Not stacked — session was not finished.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        StatRow("Frames captured", "$frameCount")
                        StatRow("Bumped (gyro)", "$bumpedCount")
                    }
                }

                // --- Folder path for PC post-processing ---
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            text = "Session folder",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = dir.absolutePath,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Connect USB and copy this folder for PC stacking (DNGs in lights/).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // --- Actions ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { showOptions = !showOptions },
                        enabled = cacheCount > 0 && !restacking,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Re-stack")
                    }
                    OutlinedButton(
                        onClick = { showDeleteDialog = true },
                        enabled = !restacking,
                        modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Delete")
                    }
                }
                if (cacheCount == 0) {
                    Text(
                        text = "Raw frame cache was not kept — re-stacking is unavailable for this session.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // --- Re-stack options panel ---
                if (showOptions && cacheCount > 0) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ChoiceRow(
                                label = "Stretch",
                                options = StretchLabels,
                                selected = stretchIndex,
                                enabled = !restacking,
                                onSelect = { stretchIndex = it },
                            )
                            ChoiceRow(
                                label = "Saturation",
                                options = SaturationLabels,
                                selected = satIndex,
                                enabled = !restacking,
                                onSelect = { satIndex = it },
                            )
                            ChoiceRow(
                                label = "Style",
                                options = StyleLabels,
                                selected = styleIndex,
                                enabled = !restacking,
                                onSelect = { styleIndex = it },
                            )
                            ChoiceRow(
                                label = "Rejection",
                                options = RejectionLabels,
                                selected = rejectionIndex,
                                enabled = !restacking,
                                onSelect = { rejectionIndex = it },
                            )
                            ToggleRow(
                                label = "Gradient removal",
                                checked = gradientRemoval,
                                enabled = !restacking,
                                onChange = { gradientRemoval = it },
                            )
                            ToggleRow(
                                label = "Star sharpen",
                                checked = starSharpen,
                                enabled = !restacking,
                                onChange = { starSharpen = it },
                            )
                            if (restacking) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "$progressStage $progressPct%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { progressPct / 100f },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            } else {
                                Button(
                                    onClick = { runRestack() },
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                ) {
                                    Text("Run")
                                }
                            }
                            val err = restackError
                            if (err != null) {
                                Text(
                                    text = "Re-stack failed: $err",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete session?") },
            text = {
                Text(
                    "This permanently removes ${dir.name}, including all DNGs and cached frames."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    scope.launch {
                        withContext(Dispatchers.IO) { repo.deleteSession(dir) }
                        onBack()
                    }
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A single-choice row built from FilterChips (SegmentedButton-like, one third width each). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceRow(
    label: String,
    options: List<String>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEachIndexed { i, opt ->
                FilterChip(
                    selected = selected == i,
                    onClick = { onSelect(i) },
                    label = {
                        Text(
                            text = opt,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
