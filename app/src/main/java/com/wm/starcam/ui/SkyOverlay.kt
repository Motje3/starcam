package com.wm.starcam.ui

import android.Manifest
import android.hardware.GeomagneticField
import android.hardware.camera2.CameraCharacteristics
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wm.starcam.camera.CamInfo
import com.wm.starcam.sensors.DeviceLocation
import com.wm.starcam.sensors.SkyOrientation
import com.wm.starcam.sensors.SkyPlace
import com.wm.starcam.sensors.SkyPlaceStore
import com.wm.starcam.sky.SkyCalendar
import com.wm.starcam.sky.SkyProjection
import com.wm.starcam.sky.SkyScene
import com.wm.starcam.sky.SkyTarget
import com.wm.starcam.sky.SkyTargets
import com.wm.starcam.sky.StarCatalog
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.components.ScDialog
import com.wm.starcam.ui.components.ScGlass
import com.wm.starcam.ui.components.ScSlider
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScShapes
import com.wm.starcam.ui.theme.ScType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * What the sky view shows: the sky now ([atMs] null), or later tonight; [target], the name of
 * something to find in it (from the planner's "worth a shot"); [pick], open with the list of
 * things to find (the capture screen's Find button).
 */
data class SkyViewRequest(val atMs: Long? = null, val target: String? = null, val pick: Boolean = false)

/** The back camera's half field of view across and up the portrait screen, as tangents. */
private fun fieldOfView(cam: CamInfo?): Pair<Float, Float> {
    val fallback = tan(Math.toRadians(30.0)).toFloat() to tan(Math.toRadians(38.0)).toFloat()
    if (cam == null || cam.focalLengthMm <= 0f) return fallback
    val ch = cam.characteristics
    val physical = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    // The physical size is the whole pixel array's; the preview shows the active part of it.
    val pixels = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
    val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    val fx = if (pixels != null && active != null && pixels.width > 0) active.width().toFloat() / pixels.width else 1f
    val fy = if (pixels != null && active != null && pixels.height > 0) active.height().toFloat() / pixels.height else 1f
    val longMm = (physical?.width?.takeIf { it > 0f } ?: cam.sensorWidthMm.takeIf { it > 0f } ?: return fallback) *
        fx.coerceIn(0.5f, 1f)
    val shortMm = (physical?.height?.takeIf { it > 0f }?.times(fy.coerceIn(0.5f, 1f)))
        ?: (longMm * cam.fullRawSize.height / cam.fullRawSize.width.coerceAtLeast(1))
    // The sensor lies on its side: across the portrait screen is its short side.
    return (shortMm / 2f / cam.focalLengthMm) to (longMm / 2f / cam.focalLengthMm)
}

/**
 * The sky view: the night sky drawn over the viewfinder where it really is - the Milky Way and
 * its core, the constellations and their brightest stars, the planets, the Moon, the horizon and
 * the compass points - from the phone's orientation, the place and the time. "Now", or a time
 * later tonight to plan a frame by day. Everything is worked out on the phone.
 */
@Composable
fun SkyOverlay(
    request: SkyViewRequest,
    cam: CamInfo?,
    onTime: (Long?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Sc.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SkyPlaceStore(context) }
    var place by remember { mutableStateOf(store.load()) }
    var locating by remember { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val atMs = request.atMs ?: nowMs

    val orientation = remember { SkyOrientation(context) }
    DisposableEffect(Unit) {
        orientation.start()
        onDispose { orientation.stop() }
    }
    var scene by remember { mutableStateOf<SkyScene?>(null) }
    val catalog = remember { runCatching { StarCatalog.load(context) }.getOrNull() }
    LaunchedEffect(place, atMs) {
        val p = place ?: return@LaunchedEffect
        val cat = catalog ?: return@LaunchedEffect
        scene = withContext(Dispatchers.Default) {
            val decl = GeomagneticField(p.loc.latDeg.toFloat(), p.loc.lonDeg.toFloat(), 0f, atMs).declination
            SkyScene.build(atMs, p.loc, decl.toDouble(), cat)
        }
    }

    fun locate() {
        locating = true
        scope.launch {
            DeviceLocation.current(context)?.let { loc ->
                val p = SkyPlace(loc, manual = false, atMs = System.currentTimeMillis())
                store.save(p)
                place = p
            }
            locating = false
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locate()
    }

    val (tanW, tanH) = remember(cam) { fieldOfView(cam) }
    val measurer = rememberTextMeasurer()
    val labels = remember { HashMap<String, TextLayoutResult>() }

    // ---- Find: what the arrow leads to - the Milky Way's core unless something else is picked
    val targets = remember(catalog) { catalog?.let { SkyTargets.all(it) } ?: emptyList() }
    var target by remember(request.target) { mutableStateOf(targets.firstOrNull { it.name == request.target }) }
    var picking by remember(request.pick) { mutableStateOf(request.pick) }
    val targetVec = remember(scene, target) { scene?.let { targetVector(it, target) } }
    // On it: the target within the middle of the view. A tick in the hand when it gets there.
    val centred by remember(targetVec, tanW, tanH) {
        derivedStateOf {
            val r = orientation.matrix.value
            val v = targetVec
            if (r == null || v == null) {
                false
            } else {
                val out = FloatArray(2)
                SkyProjection(r, tanW, tanH).project(v, 0, 2f, 2f, out) && abs(out[0] - 1f) < 0.12f && abs(out[1] - 1f) < 0.12f
            }
        }
    }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(centred) { if (centred) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    val where = remember(target, place, atMs / 60_000L) {
        val p = place
        val t = target ?: targets.firstOrNull()
        if (p == null || t == null) null else whereIs(t, atMs, p)
    }

    Box(modifier) {
        val s = scene
        if (s != null && catalog != null) {
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Sky view" }) {
                val r = orientation.matrix.value ?: return@Canvas
                drawSky(s, catalog, SkyProjection(r, tanW, tanH), measurer, labels, c.night, target, targetVec, centred)
            }
        }
        // ---- what it needs, and how sure it is
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 58.dp, start = 10.dp, end = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                !orientation.available -> SkyNote("This phone has no rotation sensor, so the sky cannot follow it.")
                place == null -> ScGlass(Modifier.padding(top = 4.dp)) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text("The sky view needs to know where you are.", style = ScType.bodySmall, color = c.text)
                        ScButton(if (locating) "Finding you…" else "Use my location", {
                            if (DeviceLocation.permitted(context)) locate()
                            else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }, Modifier.padding(top = 6.dp), kind = ScButtonKind.Primary, enabled = !locating, height = 40.dp)
                    }
                }
                else -> {
                    val err by orientation.headingErrorDeg
                    if (err.isFinite() && err > 20f) {
                        SkyNote("Compass about ±${err.roundToInt()}° off: wave the phone in a figure of eight.")
                    }
                }
            }
        }
        // ---- when: now, or later tonight
        SkyTime(
            atMs = request.atMs,
            nowMs = nowMs,
            onTime = onTime,
            onClose = onClose,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
            find = (target?.name ?: "Milky Way core") + if (centred) " · centred" else "",
            findWhere = where,
            onFind = { picking = true },
        )
    }
    val p = place
    if (picking && p != null) {
        TargetPicker(targets, atMs, p.loc, onPick = { target = it; picking = false }, onDismiss = { picking = false })
    }
}

/** A target's direction in the scene's frame. */
private fun targetVector(s: SkyScene, t: SkyTarget?): FloatArray = when (t?.kind) {
    null, SkyTarget.Kind.CORE -> s.core
    SkyTarget.Kind.MOON -> s.moon.xyz
    SkyTarget.Kind.PLANET -> s.planets.firstOrNull { it.name == t.name }?.xyz ?: s.vector(t.raDeg, t.decDeg)
    else -> s.vector(t.raDeg, t.decDeg)
}

/** "34° up in the SE"; under a degree, "on the horizon in the E". */
private fun upIn(altDeg: Double, azDeg: Double): String =
    if (altDeg < 1) "on the horizon in the ${SkyCalendar.compass(azDeg)}"
    else "${altDeg.roundToInt()}° up in the ${SkyCalendar.compass(azDeg)}"

/** "34° up in the SE", or when it comes up, or that it does not today. */
private fun whereIs(t: SkyTarget, atMs: Long, place: SkyPlace): String {
    val h = t.horizontal(atMs, place.loc)
    if (h.altDeg >= 0) return upIn(h.altDeg, h.azDeg)
    val rises = t.rises(atMs, place.loc) ?: return "not up from here today"
    val at = t.horizontal(rises, place.loc)
    return "below the horizon · up at ${SkyCalendar.hm(rises, ZoneId.systemDefault())} in the ${SkyCalendar.compass(at.azDeg)}"
}

private val KindIcons = mapOf(
    SkyTarget.Kind.CORE to ScIcons.Astro,
    SkyTarget.Kind.MOON to ScIcons.Moon,
    SkyTarget.Kind.PLANET to ScIcons.Planet,
    SkyTarget.Kind.DEEP_SKY to ScIcons.Astro,
    SkyTarget.Kind.CONSTELLATION to ScIcons.Constellation,
    SkyTarget.Kind.STAR to ScIcons.Astro,
)

/**
 * The targets to find: what is up now first (the core, the Moon and planets, then nebulae and
 * galaxies, constellations, bright stars), then what comes up later, then what does not today.
 */
@Composable
private fun TargetPicker(
    targets: List<SkyTarget>,
    atMs: Long,
    loc: com.wm.starcam.sky.GeoLocation,
    onPick: (SkyTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Sc.colors
    class Item(val t: SkyTarget, val alt: Double, val az: Double, val rises: Long?)
    var rows by remember { mutableStateOf<List<Item>?>(null) }
    LaunchedEffect(atMs) {
        rows = withContext(Dispatchers.Default) {
            targets.map { t ->
                val h = t.horizontal(atMs, loc)
                Item(t, h.altDeg, h.azDeg, if (h.altDeg < 0) t.rises(atMs, loc, 0.0) else null)
            }
        }
    }
    ScDialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(c.surface).border(1.dp, c.hairline, ScShapes.card).padding(vertical = 16.dp)) {
            Text("Find in the sky", style = ScType.titleSmall, color = c.text, modifier = Modifier.padding(horizontal = 20.dp))
            Text("The sky view's arrow leads you to it; the phone ticks when it is in the middle.",
                style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            val list = rows
            if (list == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    com.wm.starcam.ui.components.ScSpinner(size = 24.dp)
                }
            } else {
                val zone = ZoneId.systemDefault()
                val up = list.filter { it.alt >= 0 }
                val later = list.filter { it.alt < 0 && it.rises != null }.sortedBy { it.rises }
                val never = list.filter { it.alt < 0 && it.rises == null }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(top = 6.dp)) {
                    fun section(title: String, rows: List<Item>, line: (Item) -> String, dim: Boolean) {
                        if (rows.isEmpty()) return
                        item(key = title) {
                            Text(title.uppercase(), style = ScType.label, color = c.text3,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp))
                        }
                        items(rows, key = { title + it.t.name }) { r ->
                            TargetRow(KindIcons.getValue(r.t.kind), r.t.name, line(r), dim) { onPick(r.t) }
                        }
                    }
                    section("Up now", up, { r ->
                        upIn(r.alt, r.az) + (r.t.about?.let { " · $it" } ?: "")
                    }, dim = false)
                    section("Later", later, { r ->
                        "up at ${SkyCalendar.hm(r.rises!!, zone)}" + (r.t.about?.let { " · $it" } ?: "")
                    }, dim = false)
                    section("Not up from here today", never, { r -> r.t.about ?: "" }, dim = true)
                }
            }
        }
    }
}

@Composable
private fun TargetRow(icon: ImageVector, name: String, line: String, dim: Boolean, onClick: () -> Unit) {
    val c = Sc.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (dim) c.text3 else c.accentText)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = ScType.body, color = if (dim) c.text2 else c.text)
            if (line.isNotEmpty()) Text(line, style = ScType.caption, color = c.text3)
        }
    }
}

@Composable
private fun SkyNote(text: String) {
    ScGlass(Modifier.padding(top = 4.dp)) {
        Icon(ScIcons.Info, null, Modifier.size(14.dp), tint = Sc.colors.warning)
        Text(text, style = ScType.caption, color = Sc.colors.text)
    }
}

private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val DayClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.US)

/** sin(-0.6 deg): anything lower is under the horizon, refraction and all. */
private const val BELOW = -0.01f

/** "Now" or a time up to 12 hours ahead, in 10-minute steps; and the way out of the sky view. */
@Composable
private fun SkyTime(
    atMs: Long?,
    nowMs: Long,
    onTime: (Long?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
    find: String,
    findWhere: String?,
    onFind: () -> Unit,
) {
    val c = Sc.colors
    val ahead = ((atMs ?: nowMs) - nowMs).coerceIn(0L, 12 * 3_600_000L)
    ScGlass(modifier, shape = ScShapes.card) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clip(ScShapes.chip).clickable(role = Role.Button, onClick = onFind)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(ScIcons.Search, null, Modifier.size(14.dp), tint = c.accent)
                Column(Modifier.padding(start = 6.dp).weight(1f)) {
                    Text("Find: $find", style = ScType.bodySmall.copy(fontWeight = FontWeight.Medium), color = c.text)
                    findWhere?.let { Text(it, style = ScType.caption, color = c.text2) }
                }
                Text("Change", style = ScType.bodySmall.copy(fontWeight = FontWeight.Medium), color = c.accentText,
                    modifier = Modifier.padding(horizontal = 6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ScIcons.Moon, null, Modifier.size(14.dp), tint = c.accent)
                Text(
                    // Another night from the planner gets its date; the slider covers the next 12 hours.
                    if (atMs == null) "The sky now" else "The sky at " + Instant.ofEpochMilli(atMs)
                        .atZone(ZoneId.systemDefault()).format(if (atMs - nowMs > 12 * 3_600_000L) DayClockFormat else ClockFormat),
                    style = ScType.bodySmall.copy(fontWeight = FontWeight.Medium), color = c.text,
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                if (atMs != null) {
                    Text("Now", style = ScType.bodySmall.copy(fontWeight = FontWeight.Medium), color = c.accentText,
                        modifier = Modifier.clip(ScShapes.pill).clickable(role = Role.Button) { onTime(null) }
                            .padding(horizontal = 10.dp, vertical = 4.dp))
                }
                Icon(ScIcons.Close, "Close the sky view", Modifier.size(30.dp).clip(ScShapes.pill)
                    .clickable(role = Role.Button, onClick = onClose).padding(6.dp), tint = c.text2)
            }
            ScSlider(
                value = ahead / 3_600_000f,
                onValueChange = { h ->
                    val steps = (h * 6).roundToInt()  // 10-minute steps
                    onTime(if (steps == 0) null else nowMs + steps * 600_000L)
                },
                valueRange = 0f..12f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ------------------------------------------------------------------------------------ drawing

// A soft dark halo keeps the labels readable over a bright daytime preview.
private val Halo = Shadow(Color(0xCC000000), Offset.Zero, blurRadius = 6f)
private val LabelStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, shadow = Halo)
private val CardinalStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, shadow = Halo)

private fun DrawScope.label(
    text: String,
    color: Color,
    at: Offset,
    measurer: TextMeasurer,
    cache: HashMap<String, TextLayoutResult>,
    style: TextStyle = LabelStyle,
    centered: Boolean = false,
    keepInside: Boolean = false,
) {
    val key = "${style.fontSize}|$text"
    val layout = cache.getOrPut(key) { measurer.measure(text, style) }
    var tl = if (centered) Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f) else at
    if (keepInside) {
        val pad = 6.dp.toPx()
        tl = Offset(
            tl.x.coerceIn(pad, (size.width - layout.size.width - pad).coerceAtLeast(pad)),
            tl.y.coerceIn(pad, (size.height - layout.size.height - pad).coerceAtLeast(pad)),
        )
    }
    if (tl.x > size.width || tl.y > size.height || tl.x + layout.size.width < 0 || tl.y + layout.size.height < 0) return
    drawText(layout, color = color, topLeft = tl)
}

/** Star colour from B-V: blue-white hot stars to orange cool ones. */
private fun starColor(bv: Float): Color = when {
    bv.isNaN() -> Color(0xFFF2F4FF)
    bv < 0.0f -> Color(0xFFB9CCFF)
    bv < 0.3f -> Color(0xFFE6ECFF)
    bv < 0.6f -> Color(0xFFFFF6E8)
    bv < 1.0f -> Color(0xFFFFE2B8)
    bv < 1.4f -> Color(0xFFFFCB8C)
    else -> Color(0xFFFFB070)
}

private fun DrawScope.drawSky(
    s: SkyScene,
    catalog: StarCatalog,
    proj: SkyProjection,
    measurer: TextMeasurer,
    labels: HashMap<String, TextLayoutResult>,
    night: Boolean,
    target: SkyTarget?,
    targetVec: FloatArray?,
    centred: Boolean,
) {
    val w = size.width
    val h = size.height
    val red = Color(0xFFFF5040)
    fun tint(day: Color) = if (night) red.copy(alpha = day.alpha) else day
    val p = FloatArray(2)
    val q = FloatArray(2)
    // The preview dimmed: by day the drawing must stand out from a bright picture; at night the
    // picture is dark anyway.
    drawRect(Color.Black.copy(alpha = 0.45f))

    // ---- the horizon and the compass points on it
    val horizon = Path()
    var started = false
    for (k in 0 until s.horizon.size / 3) {
        if (proj.project(s.horizon, k * 3, w, h, p, minDepth = 0.02f)) {
            if (!started) horizon.moveTo(p[0], p[1]) else horizon.lineTo(p[0], p[1])
            started = true
        } else {
            started = false
        }
    }
    drawPath(horizon, tint(Color(0xCCFFFFFF)), style = Stroke(width = 1.2.dp.toPx()))
    for ((name, v) in s.cardinals) {
        if (proj.project(v, 0, w, h, p)) {
            label(name, tint(Color(0xFFFFFFFF)), Offset(p[0], p[1] - 14.dp.toPx()), measurer, labels,
                CardinalStyle, centered = true)
        }
    }

    // ---- the Milky Way: nested translucent strips, brighter towards the core
    val steps = s.bandGlow.size
    for ((li, level) in s.band.withIndex()) {
        val path = Path()
        for (k in 0 until steps - 1) {
            val g = (s.bandGlow[k] + s.bandGlow[k + 1]) / 2f
            if (g < 0.45f && li == s.band.lastIndex) continue  // the narrow bright core strip only where bright
            val a = k * 6
            val b = (k + 1) * 6
            // A piece wholly below the horizon is not drawn; one across it is.
            if (level[a + 2] < BELOW && level[a + 5] < BELOW && level[b + 2] < BELOW && level[b + 5] < BELOW) continue
            if (!proj.project(level, a, w, h, p)) continue
            val x0 = p[0]; val y0 = p[1]
            if (!proj.project(level, a + 3, w, h, p)) continue
            val x1 = p[0]; val y1 = p[1]
            if (!proj.project(level, b + 3, w, h, q)) continue
            val x2 = q[0]; val y2 = q[1]
            if (!proj.project(level, b, w, h, q)) continue
            // Quads stretched across the screen are ones wrapping behind the camera: skipped.
            if (abs(x2 - x0) > w * 1.5f || abs(y2 - y0) > h * 1.5f) continue
            path.moveTo(x0, y0)
            path.lineTo(x1, y1)
            path.lineTo(x2, y2)
            path.lineTo(q[0], q[1])
            path.close()
        }
        drawPath(path, tint(Color(0xFFC8D4F0)).copy(alpha = 0.07f + 0.03f * li))
    }

    // ---- constellation figures
    val lines = catalog.lines
    val lineColor = tint(Color(0xFF9FB4E0)).copy(alpha = 0.45f)
    val lineWidth = 1.dp.toPx()
    for (i in 0 until lines.size / 2) {
        // Below the horizon nothing is drawn but the core's arrow: those stars cannot be seen.
        if (s.stars[lines[i * 2] * 3 + 2] < BELOW || s.stars[lines[i * 2 + 1] * 3 + 2] < BELOW) continue
        if (proj.project(s.stars, lines[i * 2] * 3, w, h, p) && proj.project(s.stars, lines[i * 2 + 1] * 3, w, h, q)) {
            drawLine(lineColor, Offset(p[0], p[1]), Offset(q[0], q[1]), lineWidth, cap = StrokeCap.Round)
        }
    }

    // ---- stars, the brightest named
    val dp1 = 1.dp.toPx()
    for ((i, star) in catalog.stars.withIndex()) {
        if (s.stars[i * 3 + 2] < BELOW) continue
        if (!proj.project(s.stars, i * 3, w, h, p)) continue
        if (p[0] < -20 || p[0] > w + 20 || p[1] < -20 || p[1] > h + 20) continue
        val radius = (3.6f - 0.62f * star.mag).coerceIn(0.7f, 4.2f) * dp1
        val alpha = (1.15f - 0.16f * star.mag).coerceIn(0.35f, 1f)
        drawCircle(tint(starColor(star.bv)).copy(alpha = alpha), radius, Offset(p[0], p[1]))
        if (star.mag < 1.6f) {
            star.name?.let { label(it, tint(Color(0xDDFFFFFF)), Offset(p[0] + radius + 3 * dp1, p[1] - 7 * dp1), measurer, labels) }
        }
    }

    // ---- planets, the Moon, the Sun
    for (b in s.planets) {
        if (b.xyz[2] < BELOW) continue
        if (!proj.project(b.xyz, 0, w, h, p)) continue
        val radius = (4.2f - 0.5f * b.mag).coerceIn(2.5f, 5.5f) * dp1
        drawCircle(tint(Color(0xFFFFF0C8)), radius, Offset(p[0], p[1]))
        drawCircle(tint(Color(0xFFFFF0C8)).copy(alpha = 0.35f), radius * 2.2f, Offset(p[0], p[1]), style = Stroke(dp1))
        label(b.name, tint(Color(0xFFFFE9B0)), Offset(p[0] + radius + 4 * dp1, p[1] - 7 * dp1), measurer, labels)
    }
    if (s.moon.xyz[2] >= BELOW && proj.project(s.moon.xyz, 0, w, h, p)) {
        val radius = 9 * dp1
        drawCircle(tint(Color(0xFF3A3A44)), radius, Offset(p[0], p[1]))
        // The lit share as a lighter disc: the phase at a glance, without drawing the terminator.
        drawCircle(tint(Color(0xFFF4F1E6)).copy(alpha = (0.25f + 0.75f * s.moonIlluminated.toFloat())), radius, Offset(p[0], p[1]))
        label("Moon ${(s.moonIlluminated * 100).roundToInt()}%", tint(Color(0xFFF4F1E6)),
            Offset(p[0] + radius + 4 * dp1, p[1] - 7 * dp1), measurer, labels)
    }
    if (s.sun.altDeg > -12 && proj.project(s.sun.xyz, 0, w, h, p)) {
        drawCircle(tint(Color(0xFFFFD25A)), 11 * dp1, Offset(p[0], p[1]))
        label("Sun", tint(Color(0xFFFFD25A)), Offset(p[0] + 15 * dp1, p[1] - 7 * dp1), measurer, labels)
    }

    // ---- what is being found (the core unless something else was picked): marked where it is,
    // an arrow to it when out of view
    val accent = tint(Color(0xFF8FB8FF))
    val vec = targetVec ?: s.core
    val altDeg = Math.toDegrees(kotlin.math.asin(vec[2].toDouble().coerceIn(-1.0, 1.0)))
    val baseName = target?.name ?: "Milky Way core"
    val coreName = if (altDeg < 0) "$baseName (below the horizon)" else baseName
    // The core keeps its own mark while something else is being found.
    if (target != null && target.kind != SkyTarget.Kind.CORE && s.coreAltDeg >= 0 &&
        proj.project(s.core, 0, w, h, p) && p[0] in 0f..w && p[1] in 0f..h) {
        drawCircle(accent.copy(alpha = 0.55f), 14 * dp1, Offset(p[0], p[1]), style = Stroke(1.5f * dp1))
        label("Milky Way core", accent.copy(alpha = 0.8f), Offset(p[0], p[1] + 26 * dp1), measurer, labels, centered = true)
    }
    val inView = proj.project(vec, 0, w, h, p) && p[0] in 0f..w && p[1] in 0f..h
    if (inView) {
        if (centred) drawCircle(accent.copy(alpha = 0.18f), 26 * dp1, Offset(p[0], p[1]))
        drawCircle(accent, 16 * dp1, Offset(p[0], p[1]), style = Stroke(2 * dp1))
        drawCircle(accent.copy(alpha = 0.4f), 26 * dp1, Offset(p[0], p[1]), style = Stroke(dp1))
        label(coreName, accent, Offset(p[0], p[1] + 30 * dp1), measurer, labels, LabelStyle.copy(fontSize = 12.sp), centered = true)
    } else if (proj.direction(vec, 0, q)) {
        // From the centre towards the core, stopped just inside the frame.
        val cx = w / 2f
        val cy = h / 2f
        val margin = 34 * dp1
        val tx = if (abs(q[0]) > 1e-4f) ((w / 2f - margin) / abs(q[0])) else Float.MAX_VALUE
        val ty = if (abs(q[1]) > 1e-4f) ((h / 2f - margin - 90 * dp1) / abs(q[1])) else Float.MAX_VALUE
        val t = min(tx, ty)
        val ex = cx + q[0] * t
        val ey = cy + q[1] * t
        // A slender notched arrowhead: an equilateral triangle reads as pointing any way.
        val len = 24 * dp1
        val back = Offset(ex - q[0] * len, ey - q[1] * len)
        val notch = Offset(ex - q[0] * len * 0.65f, ey - q[1] * len * 0.65f)
        val side = Offset(-q[1] * 8 * dp1, q[0] * 8 * dp1)
        val arrow = Path().apply {
            moveTo(ex, ey)
            lineTo(back.x + side.x, back.y + side.y)
            lineTo(notch.x, notch.y)
            lineTo(back.x - side.x, back.y - side.y)
            close()
        }
        drawPath(arrow, accent)
        // The label behind the arrow, far enough along it that the two never overlap.
        val coreStyle = LabelStyle.copy(fontSize = 12.sp)
        val layout = labels.getOrPut("${coreStyle.fontSize}|$coreName") { measurer.measure(coreName, coreStyle) }
        val reach = len + 8 * dp1 + abs(q[0]) * layout.size.width / 2f + abs(q[1]) * layout.size.height / 2f
        label(coreName, accent, Offset(ex - q[0] * reach, ey - q[1] * reach), measurer, labels,
            coreStyle, centered = true, keepInside = true)
    }
}
