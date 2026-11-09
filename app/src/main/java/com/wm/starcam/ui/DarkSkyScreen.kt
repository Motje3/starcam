package com.wm.starcam.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wm.starcam.darksky.DarkSkyData
import com.wm.starcam.darksky.DarkSpot
import com.wm.starcam.darksky.GlowGrid
import com.wm.starcam.darksky.MapPlace
import com.wm.starcam.darksky.Polylines
import com.wm.starcam.darksky.SkyQuality
import com.wm.starcam.darksky.SpotFinder
import com.wm.starcam.sensors.SkyPlace
import com.wm.starcam.sensors.SkyPlaceStore
import com.wm.starcam.sky.GeoLocation
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.components.ScCard
import com.wm.starcam.ui.components.ScGlass
import com.wm.starcam.ui.components.ScIconButton
import com.wm.starcam.ui.components.ScLabel
import com.wm.starcam.ui.components.ScScreen
import com.wm.starcam.ui.components.ScSpinner
import com.wm.starcam.ui.components.ScSwitch
import com.wm.starcam.ui.components.ScTopBar
import com.wm.starcam.ui.theme.NotoSans
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScColors
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScShapes
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.ui.theme.ScreenMargin
import com.wm.starcam.ui.theme.scGlow
import com.wm.starcam.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ----------------------------------------------------------------------------- the map's data

private const val KM_PER_DEG = 111.32

/** How far around you the map is prepared (km): the spots go to 150 km, dark-sky parks to 400. */
private const val REGION_KM = 450.0

/** A town or dark-sky place on the map, its position in the map's km. */
private class MapMark(val place: MapPlace, val x: Float, val y: Float)

/**
 * Everything drawn, prepared once for one place: lines as paths and the light pollution as a
 * picture, both in km east and south of the place (a local equirectangular projection: within a
 * few hundred km it bends lines by well under a pixel's worth).
 */
private class MapLayers(
    val refLat: Double,
    val refLon: Double,
    val kmPerDegLon: Double,
    val coast: Path,
    val lakes: Path,
    val borders: Path,
    /** Major, secondary and minor roads. */
    val roads: Array<Path>,
    val glow: ImageBitmap,
    val glowRect: Rect,
    val towns: List<MapMark>,
    val sites: List<MapMark>,
) {
    fun km(lat: Double, lon: Double): Offset =
        Offset(((lon - refLon) * kmPerDegLon).toFloat(), (-(lat - refLat) * KM_PER_DEG).toFloat())

    fun latLon(x: Float, y: Float): Pair<Double, Double> =
        (refLat - y / KM_PER_DEG) to (refLon + x / kmPerDegLon)
}

private fun buildLayers(data: DarkSkyData, lat: Double, lon: Double, night: Boolean): MapLayers {
    val kx = KM_PER_DEG * cos(Math.toRadians(lat))
    val dLat = REGION_KM / KM_PER_DEG
    val dLon = REGION_KM / kx
    fun path(p: Polylines, cls: Int? = null): Path {
        val out = Path()
        for (k in 0 until p.size) {
            if (cls != null && p.classes[k].toInt() != cls) continue
            if (p.bbox[4 * k + 2] < lon - dLon || p.bbox[4 * k] > lon + dLon ||
                p.bbox[4 * k + 3] < lat - dLat || p.bbox[4 * k + 1] > lat + dLat) continue
            for (i in p.starts[k] until p.starts[k + 1]) {
                val x = ((p.coords[2 * i] - lon) * kx).toFloat()
                val y = (-(p.coords[2 * i + 1] - lat) * KM_PER_DEG).toFloat()
                if (i == p.starts[k]) out.moveTo(x, y) else out.lineTo(x, y)
            }
        }
        return out
    }
    val g = data.glow
    val rowTop = max(0, g.row(min(lat + dLat, g.latMax - 1e-6)) ?: 0)
    val rowBottom = min(g.height - 1, g.row(max(lat - dLat, g.latMin + 1e-6)) ?: (g.height - 1))
    val colLeft = floor((lon - dLon + 180.0) * g.cellsPerDeg).toInt()
    val colRight = floor((lon + dLon + 180.0) * g.cellsPerDeg).toInt()
    val w = colRight - colLeft + 1
    val h = rowBottom - rowTop + 1
    val pixels = IntArray(w * h)
    for (r in 0 until h) for (c in 0 until w) {
        pixels[r * w + c] = glowColor(g, g.value(rowTop + r, colLeft + c), night)
    }
    val bmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    // The picture's corners: the outer edges of its first and last cells.
    val left = ((-180.0 + colLeft.toDouble() / g.cellsPerDeg - lon) * kx).toFloat()
    val right = ((-180.0 + (colRight + 1).toDouble() / g.cellsPerDeg - lon) * kx).toFloat()
    val top = (-(g.latMax - rowTop.toDouble() / g.cellsPerDeg - lat) * KM_PER_DEG).toFloat()
    val bottom = (-(g.latMax - (rowBottom + 1).toDouble() / g.cellsPerDeg - lat) * KM_PER_DEG).toFloat()
    fun marks(list: List<MapPlace>) = list.filter { abs(it.lat - lat) < dLat && abs(it.lon - lon) < dLon }
        .map { MapMark(it, ((it.lon - lon) * kx).toFloat(), (-(it.lat - lat) * KM_PER_DEG).toFloat()) }
    return MapLayers(
        refLat = lat, refLon = lon, kmPerDegLon = kx,
        coast = path(data.coast), lakes = path(data.lakes), borders = path(data.borders),
        roads = arrayOf(path(data.roads, 1), path(data.roads, 2), path(data.roads, 3)),
        glow = bmp.asImageBitmap(), glowRect = Rect(left, top, right, bottom),
        towns = marks(data.towns), sites = marks(data.darkSites),
    )
}

// ------------------------------------------------------------------------------- colours

private val WaterDay = Color(0xFF0B1B33)
private val WaterNight = Color(0xFF0A0000)

/**
 * The light-pollution ramp, by zenith brightness: black for a pristine sky through blues and
 * greens to the yellows, reds and whites of cities. Most of the steps sit where they decide
 * where to go (21-22 mag/arcsec2), not in the cities.
 */
private val SqmStops = floatArrayOf(22.0f, 21.92f, 21.8f, 21.6f, 21.35f, 21.0f, 20.6f, 20.15f, 19.6f, 19.0f, 18.4f, 17.8f)
private val ColorStops = intArrayOf(
    0xFF07070C.toInt(), 0xFF191A2B.toInt(), 0xFF22285A.toInt(), 0xFF1E4C9A.toInt(), 0xFF1C8288.toInt(),
    0xFF2C9A4C.toInt(), 0xFF8DBD38.toInt(), 0xFFE4CF39.toInt(), 0xFFEF962B.toInt(), 0xFFDF4329.toInt(),
    0xFFD93C8E.toInt(), 0xFFF5D7EC.toInt(),
)

private fun lerpColor(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = ((a shr s and 0xff) + ((b shr s and 0xff) - (a shr s and 0xff)) * t).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Colour of a zenith brightness: the ramp above by day, shades of red at night. */
private fun sqmColor(sqm: Double, night: Boolean): Int {
    val s = sqm.toFloat()
    if (night) {
        // Brighter red for more light pollution; pristine skies near black.
        val t = ((22.0f - s) / 4.2f).coerceIn(0f, 1f)
        val v = (18 + 220 * sqrt(t)).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (v shl 16) or ((v / 7) shl 8)
    }
    if (s >= SqmStops[0]) return ColorStops[0]
    for (i in 1 until SqmStops.size) {
        if (s >= SqmStops[i]) {
            val t = (SqmStops[i - 1] - s) / (SqmStops[i - 1] - SqmStops[i])
            return lerpColor(ColorStops[i - 1], ColorStops[i], t)
        }
    }
    return ColorStops.last()
}

private fun glowColor(g: GlowGrid, value: Int, night: Boolean): Int {
    if (value == 0) return (if (night) WaterNight else WaterDay).toArgb()
    return sqmColor(SkyQuality.sqm(g.alr(value)), night)
}

private fun bortleColor(bortle: Int, night: Boolean): Color {
    val sqm = when (bortle) { 1 -> 22.0; 2 -> 21.93; 3 -> 21.78; 4 -> 21.1; 5 -> 20.0; 6 -> 19.2; 7 -> 18.65; 8 -> 18.1; else -> 17.6 }
    return Color(sqmColor(sqm, night))
}

/** The markers' colours: lavender by day, red in night vision (nothing white). */
private class MapInk(
    val mark: Color,
    val ink: Color,
    val chosen: List<Color>,
    val chosenRim: Color,
    val chosenText: Color,
    val glow: Color,
    val label: Color,
    val site: Color,
    val you: Color,
    val youRim: Color,
)

private fun mapInk(c: ScColors): MapInk = if (c.night) MapInk(
    mark = Color(0xFFFF5A44), ink = Color(0xFF160505), chosen = c.primaryGradient, chosenRim = Color(0xFFFF6A5A),
    chosenText = Color(0xFFFF6A5A), glow = Color(0x40FF4433), label = Color(0xFFFF6A55), site = Color(0xFFFF8A60),
    you = Color(0xFFFF3B30), youRim = Color(0xFF3A0E09),
) else MapInk(
    mark = Color(0xFFCDAAFF), ink = Color(0xFF140A28), chosen = c.primaryGradient, chosenRim = Color(0xFFF1E9FF),
    chosenText = c.primaryText, glow = Color(0x809F5CFF), label = Color(0xEEFFFFFF), site = Color(0xFFFFE07A),
    you = Color(0xFF9B5CF0), youRim = Color(0xFFF5F0FF),
)

/** What the layers menu has switched on. */
private data class MapShow(val roads: Boolean, val rings: Boolean, val names: Boolean)

// ------------------------------------------------------------------- the view and the panel

/** What part of the map is on screen: its centre in the map's km and its scale in px per km. */
@Stable
private class MapView {
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    var scale by mutableFloatStateOf(0f)
    /** The canvas, px: the whole screen, under the top bar and the panel. */
    var size by mutableStateOf(IntSize.Zero)

    fun toScreen(o: Offset) =
        Offset(size.width / 2f + (o.x - panX) * scale, size.height / 2f + (o.y - panY) * scale)

    /**
     * You and every suggestion, at least 60 km around you, with you in the middle of the strip of
     * map between [top] and [bottom] (px): what the top bar and the panel leave of it.
     */
    fun fit(layers: MapLayers, spots: List<DarkSpot>, top: Float, bottom: Float) {
        if (size.width == 0 || size.height == 0) return
        var reach = 60f
        for (s in spots) {
            val o = layers.km(s.lat, s.lon)
            reach = max(reach, max(abs(o.x), abs(o.y)) * 1.15f)
        }
        val band = (bottom - top).coerceAtLeast(size.height * 0.25f)
        scale = min(size.width.toFloat(), band) / 2f / reach
        panX = 0f
        panY = (size.height / 2f - (top + bottom) / 2f) / scale
    }

    /** Brings [s] into the strip between [top] and [bottom] when it is outside, you still in view. */
    fun show(layers: MapLayers, s: DarkSpot, top: Float, bottom: Float, margin: Float) {
        if (size.width == 0 || scale <= 0f) return
        val o = layers.km(s.lat, s.lon)
        val p = toScreen(o)
        if (p.x > margin && p.x < size.width - margin && p.y > top + margin && p.y < bottom - margin) return
        val band = (bottom - top).coerceAtLeast(size.height * 0.25f)
        val reach = max(abs(o.x), abs(o.y)) / 2f * 1.3f + 8f
        scale = min(scale, min(size.width.toFloat(), band) / 2f / reach)
        panX = o.x / 2f
        panY = o.y / 2f + (size.height / 2f - (top + bottom) / 2f) / scale
    }

    companion object {
        val Saver = listSaver<MapView, Float>(
            save = { listOf(it.panX, it.panY, it.scale) },
            restore = { MapView().apply { panX = it[0]; panY = it[1]; scale = it[2] } },
        )
    }
}

private enum class Stop { Full, Half, Peek }

/**
 * The panel over the map: where its top edge is (px from the top of the screen) and the stop it
 * rests at. The stops come from its content's measured height, so Half always ends just under
 * your own sky and Peek under the place's name, however much text they take.
 */
@Stable
private class Panel {
    var top by mutableFloatStateOf(Float.NaN)
    var stop by mutableStateOf(Stop.Half)
    var fullAt by mutableFloatStateOf(0f)
    var halfAt by mutableFloatStateOf(0f)
    var peekAt by mutableFloatStateOf(0f)
    private var moving: Job? = null

    val ready: Boolean get() = peekAt > 0f

    fun at(s: Stop): Float = when (s) {
        Stop.Full -> fullAt
        Stop.Half -> halfAt
        Stop.Peek -> peekAt
    }

    /** Whether it sits at a stop rather than between two. */
    val resting: Boolean get() = Stop.entries.any { abs(at(it) - top) < 1f }

    /** Follows a finger by [dy] px, within the stops; returns how far it went. */
    fun drag(dy: Float): Float {
        if (!ready || top.isNaN()) return 0f
        moving?.cancel()
        val old = top
        top = (top + dy).coerceIn(fullAt, peekAt)
        return top - old
    }

    /** After a drag: on to the next stop the way it was flung, else back to the nearest. */
    fun settle(scope: CoroutineScope, velocity: Float, flingPx: Float) {
        if (!ready) return
        val target = when {
            velocity < -flingPx -> Stop.entries.lastOrNull { at(it) < top - 1f } ?: Stop.Full
            velocity > flingPx -> Stop.entries.firstOrNull { at(it) > top + 1f } ?: Stop.Peek
            else -> Stop.entries.minBy { abs(at(it) - top) }
        }
        go(scope, target, velocity)
    }

    fun go(scope: CoroutineScope, s: Stop, velocity: Float = 0f) {
        stop = s
        if (!ready || top.isNaN()) return
        moving?.cancel()
        val from = top
        moving = scope.launch {
            animate(from, at(s), velocity, spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> top = v }
        }
    }

    /** The stops, measured again (another place, your sky opened): the panel keeps its stop. */
    fun place(scope: CoroutineScope, full: Float, half: Float, peek: Float) {
        val wasAt = if (ready) Stop.entries.firstOrNull { abs(at(it) - top) < 1f } else null
        val animating = moving?.isActive == true
        fullAt = full
        peekAt = max(peek, full)
        halfAt = half.coerceIn(full, peekAt)
        when {
            top.isNaN() -> top = at(stop)
            animating -> go(scope, stop)
            wasAt != null -> go(scope, wasAt)
        }
    }
}

/** Drags on the panel's content move the panel first (up) or last (down), as a bottom sheet does. */
private class PanelScroll(
    private val panel: Panel,
    private val scope: CoroutineScope,
    private val flingPx: Float,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0f && source == NestedScrollSource.UserInput) Offset(0f, panel.drag(available.y))
        else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.y > 0f && source == NestedScrollSource.UserInput) Offset(0f, panel.drag(available.y))
        else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (panel.resting) return Velocity.Zero
        panel.settle(scope, available.y, flingPx)
        return available
    }
}

// -------------------------------------------------------------------------------- screen

/** A tapped point on the map: its sky, as a spot of its own. */
private fun probeSpot(data: DarkSkyData, lat: Double, lon: Double, hereLat: Double, hereLon: Double): DarkSpot =
    DarkSpot(
        lat, lon, data.glow.alr(lat, lon), SpotFinder.distanceKm(hereLat, hereLon, lat, lon),
        SpotFinder.bearingDeg(hereLat, hereLon, lat, lon), "This spot", SpotFinder.describe(data.towns, lat, lon),
    )

/**
 * Dark skies near you (the owner's design, 5 Nov 2026): the light-pollution map fills the screen,
 * and one panel over its lower part says where the darker skies are and how dark yours is. The
 * panel rests at three heights: the place's name alone (the map almost whole), the place with its
 * way there and your own sky, and everything, with the numbers and the full list. The way there is
 * handed to the maps app: Stelli itself never goes online.
 */
@Composable
fun DarkSkyScreen(onBack: () -> Unit, onPlanThere: (GeoLocation, String) -> Unit) {
    val c = Sc.colors
    val context = LocalContext.current
    val place by remember { SkyPlaceStore(context).place }.collectAsState()
    var data by remember { mutableStateOf<DarkSkyData?>(null) }
    var layers by remember { mutableStateOf<MapLayers?>(null) }
    var result by remember { mutableStateOf<SpotFinder.Result?>(null) }
    val night = c.night

    LaunchedEffect(Unit) {
        data = withContext(Dispatchers.Default) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            runCatching { DarkSkyData.load(context) }
                .onFailure { AppLog.e("darksky", "the map's data could not be read", it) }
                .getOrNull()
                .also { AppLog.i("darksky", "map data ready in ${android.os.SystemClock.elapsedRealtime() - t0} ms") }
        }
    }
    LaunchedEffect(data, place, night) {
        val d = data ?: return@LaunchedEffect
        val p = place ?: return@LaunchedEffect
        val (l, r) = withContext(Dispatchers.Default) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val built = buildLayers(d, p.loc.latDeg, p.loc.lonDeg, night)
            val t1 = android.os.SystemClock.elapsedRealtime()
            val found = SpotFinder.find(d, p.loc.latDeg, p.loc.lonDeg)
            AppLog.i("darksky", "layers ${t1 - t0} ms, spots ${android.os.SystemClock.elapsedRealtime() - t1} ms: " +
                "Bortle ${found.hereBortle} here, ${found.all.size} suggestions")
            built to found
        }
        layers = l
        result = r
    }

    val p = place
    val d = data
    val l = layers
    val r = result
    if (p != null && d != null && l != null && r != null) {
        LightMap(d, l, r, p, onBack, onPlanThere)
        return
    }
    ScScreen {
        ScTopBar(title = "Light map", subtitle = "Find darker skies", onBack = onBack)
        if (p == null) {
            Column(Modifier.fillMaxWidth().padding(ScreenMargin), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ScCard(Modifier.fillMaxWidth()) {
                    Text("Where are you?", style = ScType.headline, color = c.text)
                    Text(
                        "The map shows how much light pollution there is around you and where the nearest " +
                            "darker skies are. Your location stays on this phone: Stelli has no internet access.",
                        style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 4.dp),
                    )
                    FindMeButton(Modifier.fillMaxWidth().padding(top = 12.dp))
                    Text("Or type coordinates in the Tonight planner.", style = ScType.caption, color = c.text3,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ScSpinner(size = 28.dp) }
        }
    }
}

/** The top bar over the map: [ScTopBar]'s 44 dp buttons and 8 dp above and below. */
private val TopBarHeight = 60.dp

/** The strip at the panel's top that holds its handle. */
private val PanelHandle = 26.dp

@Composable
private fun LightMap(
    data: DarkSkyData,
    layers: MapLayers,
    result: SpotFinder.Result,
    place: SkyPlace,
    onBack: () -> Unit,
    onPlanThere: (GeoLocation, String) -> Unit,
) {
    val c = Sc.colors
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val prefs = remember { UiPrefs(context) }
    val hereLat = place.loc.latDeg
    val hereLon = place.loc.lonDeg
    val spots = result.all
    val view = rememberSaveable(hereLat, hereLon, saver = MapView.Saver) { MapView() }
    val panel = remember { Panel() }
    // The place in the panel: a suggestion by its index, or (-1) a point tapped on the map. The
    // nearest darker sky to begin with: the first answer to "where do I go?".
    var pick by rememberSaveable(hereLat, hereLon) { mutableStateOf(0) }
    var probe by remember { mutableStateOf<DarkSpot?>(null) }
    val featured = if (pick < 0) probe else spots.getOrNull(pick)
    var skyOpen by rememberSaveable { mutableStateOf(false) }
    var roads by remember { mutableStateOf(prefs.mapRoads) }
    var rings by remember { mutableStateOf(prefs.mapRings) }
    var names by remember { mutableStateOf(prefs.mapNames) }
    var menu by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().background(if (c.night) WaterNight else WaterDay)) {
        val screenH = constraints.maxHeight.toFloat()
        val insets = WindowInsets.safeDrawing
        val navPx = insets.getBottom(density).toFloat()
        val barBottom = insets.getTop(density) + with(density) { TopBarHeight.toPx() }
        val margin = with(density) { 40.dp.toPx() }
        val flingPx = with(density) { 500.dp.toPx() }
        // The map's lower edge on screen: the panel's top.
        fun mapBottom(): Float = if (panel.top.isNaN()) screenH * 0.6f else panel.top

        fun choose(i: Int) {
            pick = i
            if (panel.stop != Stop.Half) panel.go(scope, Stop.Half)
            val s = if (i < 0) probe else spots.getOrNull(i)
            if (s != null) view.show(layers, s, barBottom, panel.halfAt, margin)
        }

        DarkSkyMap(
            layers, spots, featured, probe, view, MapShow(roads, rings, names),
            onSpot = { s -> choose(spots.indexOf(s)) },
            onPoint = { x, y ->
                val (lat, lon) = layers.latLon(x, y)
                // The sea has no reading of its own in the data: a tap there picks nothing.
                if (data.glow.value(lat, lon) > 0) {
                    probe = probeSpot(data, lat, lon, hereLat, hereLon)
                    choose(-1)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        LaunchedEffect(view, layers) {
            snapshotFlow { view.size.width > 0 && panel.ready }.first { it }
            if (view.scale == 0f) view.fit(layers, spots, barBottom, panel.halfAt)
        }

        // The top bar floats over the map, on a shade that keeps its words readable over a city.
        Box(
            Modifier.fillMaxWidth().height(with(density) { barBottom.toDp() } + 28.dp).background(
                Brush.verticalGradient(
                    0f to c.background.copy(alpha = 0.9f),
                    0.6f to c.background.copy(alpha = 0.55f),
                    1f to Color.Transparent,
                ),
            ),
        )
        ScTopBar(
            "Light map", onBack,
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            subtitle = "Find darker skies",
        ) {
            ScIconButton(ScIcons.Focus, "Back to where you are", { view.fit(layers, spots, barBottom, mapBottom()) })
            ScIconButton(ScIcons.Layers, "Map layers", { menu = !menu }, selected = menu)
        }

        // The legend rides on the panel, and fades once the panel is up over most of the map.
        var legendH by remember { mutableIntStateOf(0) }
        Legend(
            c.night,
            Modifier
                .onSizeChanged { legendH = it.height }
                .offset { IntOffset(12.dp.roundToPx(), (mapBottom() - legendH - 10.dp.toPx()).roundToInt()) }
                .graphicsLayer {
                    val room = mapBottom() - barBottom - 120.dp.toPx()
                    alpha = if (panel.top.isNaN()) 0f else (room / 80.dp.toPx()).coerceIn(0f, 1f)
                },
        )

        var headH by remember { mutableIntStateOf(0) }
        var summaryH by remember { mutableIntStateOf(0) }
        LaunchedEffect(headH, summaryH, screenH, barBottom, navPx) {
            if (headH == 0 || summaryH == 0) return@LaunchedEffect
            with(density) {
                val handle = PanelHandle.toPx()
                panel.place(
                    scope,
                    full = barBottom + 6.dp.toPx(),
                    half = max(screenH - (handle + summaryH + 22.dp.toPx() + navPx), screenH * 0.3f),
                    peek = screenH - (handle + headH + 18.dp.toPx() + navPx),
                )
            }
        }
        BackHandler(enabled = panel.stop == Stop.Full && !menu) { panel.go(scope, Stop.Half) }
        BackHandler(enabled = menu) { menu = false }
        val navDp = with(density) { navPx.toDp() }
        MapPanel(panel, screenH, scope, flingPx, navDp) {
            PanelContent(
                result, featured, skyOpen, c.night,
                onSkyToggle = { skyOpen = !skyOpen },
                onNavigate = { openInMaps(context, it) },
                onPlan = { onPlanThere(GeoLocation(it.lat, it.lon), spotLabel(it)) },
                onPick = ::choose,
                onHeadSize = { headH = it },
                onSummarySize = { summaryH = it },
                bottomInset = navDp,
            )
        }

        if (menu) {
            // A tap anywhere else closes the menu.
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { menu = false } })
            LayersMenu(
                MapShow(roads, rings, names),
                onChange = { s ->
                    roads = s.roads
                    rings = s.rings
                    names = s.names
                    prefs.mapRoads = s.roads
                    prefs.mapRings = s.rings
                    prefs.mapNames = s.names
                },
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(top = with(density) { barBottom.toDp() } + 4.dp, end = 10.dp),
            )
        }
    }
}

/** The panel: violet glass with a lit top edge, its handle, and content that scrolls at Full. */
@Composable
private fun MapPanel(
    panel: Panel,
    screenH: Float,
    scope: CoroutineScope,
    flingPx: Float,
    bottomInset: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Sc.colors
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    // Below Full the panel shows its top: a list scrolled down there would hide the place.
    LaunchedEffect(panel.stop) { if (panel.stop != Stop.Full) scroll.animateScrollTo(0) }
    val connection = remember(panel) { PanelScroll(panel, scope, flingPx) }
    val drag = rememberDraggableState { panel.drag(it) }
    val height = with(density) { (screenH - (if (panel.ready) panel.fullAt else screenH * 0.1f)).toDp() }
    Column(
        Modifier
            .fillMaxWidth()
            .height(height)
            .offset { IntOffset(0, (if (panel.top.isNaN()) screenH else panel.top).roundToInt()) }
            .scGlow(c.accentGlow.copy(alpha = c.accentGlow.alpha * 0.35f), radius = 18.dp, cornerRadius = 28.dp)
            .clip(ScShapes.sheet)
            .background(Brush.verticalGradient(listOf(c.raised, c.surface, c.surface)))
            .border(1.dp, c.buttonBorder, ScShapes.sheet)
            .blockTouches(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(PanelHandle)
                .draggable(drag, Orientation.Vertical, onDragStopped = { v -> panel.settle(scope, v, flingPx) })
                .clickable(role = Role.Button) {
                    panel.go(scope, if (panel.stop == Stop.Full) Stop.Half else Stop.Full)
                }
                .semantics {
                    contentDescription = if (panel.stop == Stop.Full) "Show the map" else "Show the whole list"
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(c.text3.copy(alpha = 0.55f)))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .nestedScroll(connection)
                .verticalScroll(scroll)
                .padding(start = 16.dp, end = 16.dp, bottom = bottomInset + 20.dp),
            content = content,
        )
    }
}

/**
 * What the panel says, top to bottom: the place (all that shows at Peek), how dark it is, the way
 * there and your own sky (Half ends there), then its numbers and every suggestion.
 */
@Composable
private fun PanelContent(
    result: SpotFinder.Result,
    featured: DarkSpot?,
    skyOpen: Boolean,
    night: Boolean,
    onSkyToggle: () -> Unit,
    onNavigate: (DarkSpot) -> Unit,
    onPlan: (DarkSpot) -> Unit,
    onPick: (Int) -> Unit,
    onHeadSize: (Int) -> Unit,
    onSummarySize: (Int) -> Unit,
    bottomInset: Dp,
) {
    val c = Sc.colors
    val spots = result.all
    Column(Modifier.fillMaxWidth().onSizeChanged { onSummarySize(it.height) }) {
        if (featured != null) {
            SpotHead(featured, night, Modifier.onSizeChanged { onHeadSize(it.height) })
            BortleChip(featured.bortle, Modifier.padding(top = 12.dp))
            Text(
                SkyQuality.compared(featured.sqm, result.hereSqm) + " " + SkyQuality.milkyWayShort(featured.bortle),
                style = ScType.body, color = c.text2, modifier = Modifier.padding(top = 10.dp),
            )
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScButton("Navigate", { onNavigate(featured) }, Modifier.weight(1f), kind = ScButtonKind.Primary,
                    icon = ScIcons.Navigate, height = 50.dp)
                ScButton("Plan here", { onPlan(featured) }, Modifier.weight(1f), icon = ScIcons.Moon, height = 50.dp)
            }
            YourSkyRow(result, skyOpen, night, onSkyToggle, Modifier.padding(top = 16.dp))
        } else {
            HereHead(result, night, Modifier.onSizeChanged { onHeadSize(it.height) })
            Text(SkyQuality.milkyWay(result.hereBortle), style = ScType.body, color = c.text2,
                modifier = Modifier.padding(top = 12.dp))
            Text(
                if (result.hereBortle <= 2) "Your sky is about as dark as skies get: nothing darker within 150 km."
                else "Nothing clearly darker within 150 km.",
                style = ScType.bodySmall, color = c.text3, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
    // Swiped up: the numbers, and every suggestion. Far enough down to stay out of sight at Half,
    // which ends 22 dp above the navigation bar.
    Column(Modifier.fillMaxWidth().padding(top = bottomInset + 34.dp)) {
        if (featured != null) {
            ScLabel("About this place")
            Spacer(Modifier.height(6.dp))
            Fact("Sky brightness", String.format(Locale.US, "%.1f mag/arcsec² at the zenith", featured.sqm))
            Fact("Against yours", againstYours(featured.sqm, result.hereSqm))
            Fact("Distance", "${featured.distanceKm.roundToInt()} km ${SpotFinder.compass(featured.bearingDeg)}, as the crow flies")
            featured.near?.let { Fact("Near", it) }
            Fact("Position", String.format(Locale.US, "%.4f, %.4f", featured.lat, featured.lon))
            Text(SkyQuality.milkyWay(featured.bortle), style = ScType.bodySmall, color = c.text2,
                modifier = Modifier.padding(top = 8.dp))
        } else {
            ScLabel("Your sky")
            Spacer(Modifier.height(6.dp))
            Fact("Sky brightness", String.format(Locale.US, "%.1f mag/arcsec² at the zenith", result.hereSqm))
        }
    }
    if (spots.isNotEmpty()) {
        ScLabel("Darker skies within reach", Modifier.padding(top = 26.dp, bottom = 10.dp))
        spots.forEachIndexed { i, s ->
            SpotRow(i + 1, s, night, chosen = s == featured, Modifier.padding(bottom = 8.dp)) { onPick(i) }
        }
    }
    Text(
        "Estimated from satellite night lights (NPP-VIIRS-like 2025, Chen et al., CC0) with the " +
            "Duriscoe 2018 sky-glow model; map: Natural Earth. City centres can be brighter than shown, " +
            "and the sky also needs no cloud and no Moon. Check access and safety before you go.",
        style = ScType.caption, color = c.text3, modifier = Modifier.padding(top = 18.dp),
    )
}

private val HeadStyle = ScType.screenTitle.copy(fontWeight = FontWeight.Bold)

/** "7 km NE of you"; closer than a kilometre, right here. */
private fun wayText(s: DarkSpot): String =
    if (s.distanceKm < 1.0) "Right where you are"
    else "${s.distanceKm.roundToInt()} km ${SpotFinder.compass(s.bearingDeg)} of you"

/** "1.6× darker than yours": the zenith brightness of both, as a ratio. */
private fun againstYours(sqm: Double, hereSqm: Double): String {
    val k = SkyQuality.darkerBy(sqm, hereSqm)
    fun times(x: Double) = if (x < 10.0) String.format(Locale.US, "%.1f×", x) else "${x.roundToInt()}×"
    return when {
        k >= 1.1 -> "${times(k)} darker than yours"
        k <= 1.0 / 1.1 -> "${times(1.0 / k)} brighter than yours"
        else -> "About the same as yours"
    }
}

@Composable
private fun SpotHead(s: DarkSpot, night: Boolean, modifier: Modifier) {
    val c = Sc.colors
    Row(modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ScIcons.Pin, null, Modifier.size(28.dp), tint = c.accent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = HeadStyle, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(wayText(s), style = ScType.body, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        BortleBadge(s.bortle, night, 40.dp)
    }
}

/** The head when nothing nearby is darker: your own sky. */
@Composable
private fun HereHead(r: SpotFinder.Result, night: Boolean, modifier: Modifier) {
    val c = Sc.colors
    Row(modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        BortleBadge(r.hereBortle, night, 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Your sky", style = HeadStyle, color = c.text)
            Text("Bortle ${r.hereBortle} · ${SkyQuality.name(r.hereBortle)}", style = ScType.body, color = c.text2)
        }
    }
}

@Composable
private fun BortleChip(bortle: Int, modifier: Modifier = Modifier) {
    val c = Sc.colors
    Text(
        "Bortle $bortle · ${SkyQuality.name(bortle)}",
        style = ScType.body, color = c.text2, maxLines = 1,
        modifier = modifier.clip(CircleShape).background(c.raised).border(1.dp, c.hairlineStrong, CircleShape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** Your own sky in one row; tapped, what that means for the Milky Way and its number. */
@Composable
private fun YourSkyRow(r: SpotFinder.Result, open: Boolean, night: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    val c = Sc.colors
    Column(
        modifier.fillMaxWidth().clip(ScShapes.tile).background(c.tile).border(1.dp, c.hairline, ScShapes.tile)
            .clickable(role = Role.Button, onClickLabel = if (open) "Less about your sky" else "More about your sky",
                onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BortleBadge(r.hereBortle, night, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                ScLabel("Your sky")
                Text("Bortle ${r.hereBortle} · ${SkyQuality.name(r.hereBortle)}",
                    style = ScType.body.copy(fontWeight = FontWeight.Medium), color = c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Icon(ScIcons.ChevronDown, null, Modifier.size(20.dp).rotate(if (open) 180f else 0f), tint = c.text2)
        }
        if (open) {
            Text(SkyQuality.milkyWay(r.hereBortle), style = ScType.bodySmall, color = c.text2,
                modifier = Modifier.padding(top = 10.dp))
            Text(String.format(Locale.US, "About %.1f mag/arcsec² at the zenith", r.hereSqm), style = ScType.caption,
                color = c.text3, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    val c = Sc.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = ScType.bodySmall, color = c.text3, modifier = Modifier.width(116.dp))
        Text(value, style = ScType.bodySmall, color = c.text, modifier = Modifier.weight(1f))
    }
}

private fun spotLabel(s: DarkSpot): String =
    if (s.title == "This spot" || s.title.startsWith("Darkest") || s.title.startsWith("Nearest")) {
        "Dark spot" + (s.near?.let { ", $it" } ?: "")
    } else s.title

private fun openInMaps(context: android.content.Context, s: DarkSpot) {
    val label = Uri.encode(spotLabel(s))
    val uri = Uri.parse("geo:0,0?q=%.5f,%.5f(%s)".format(Locale.US, s.lat, s.lon, label))
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No maps app on this phone", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun BortleBadge(bortle: Int, night: Boolean, size: Dp = 34.dp) {
    val bg = bortleColor(bortle, night)
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).border(1.dp, Sc.colors.hairlineStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // Night vision: dark on the bright red of a city sky, red on the near-black of a dark
        // one; never white.
        Text("$bortle", style = (if (size >= 40.dp) ScType.headline else ScType.bodySmall).copy(fontWeight = FontWeight.Bold),
            color = when {
                night -> if (bortle >= 5) Color(0xFF1A0503) else Color(0xFFFF6A5A)
                bortle <= 3 -> Color.White
                else -> Color(0xFF101010)
            })
    }
}

@Composable
private fun SpotRow(
    number: Int,
    s: DarkSpot,
    night: Boolean,
    chosen: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val c = Sc.colors
    Row(
        modifier.fillMaxWidth().clip(ScShapes.card).background(if (chosen) c.selectedFill else c.surface)
            .border(1.dp, if (chosen) c.selectedBorder else c.hairline, ScShapes.card)
            .clickable(role = Role.Button, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NumberDot(number)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = ScType.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(
                "${s.distanceKm.roundToInt()} km ${SpotFinder.compass(s.bearingDeg)}" + (s.near?.let { " · $it" } ?: ""),
                style = ScType.caption, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        BortleBadge(s.bortle, night, 30.dp)
    }
}

@Composable
private fun NumberDot(number: Int) {
    val c = Sc.colors
    Box(Modifier.size(26.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
        Text("$number", style = ScType.caption.copy(fontWeight = FontWeight.Bold), color = c.background)
    }
}

@Composable
private fun Legend(night: Boolean, modifier: Modifier) {
    val c = Sc.colors
    val small = ScType.caption.copy(fontSize = 11.sp, lineHeight = 14.sp)
    ScGlass(modifier, padding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)) {
        Text("Bortle", style = small, color = c.text2)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (b in 1..9) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(bortleColor(b, night)))
            }
        }
        Text("1 · Dark", style = small, color = c.text2)
        Text("·", style = small, color = c.text3)
        Text("9 · City", style = small, color = c.text2)
    }
}

/** The layers button's menu: what the map draws besides the light pollution. */
@Composable
private fun LayersMenu(show: MapShow, onChange: (MapShow) -> Unit, modifier: Modifier) {
    val c = Sc.colors
    ScCard(
        modifier.width(232.dp).blockTouches(),
        color = c.raised, borderColor = c.hairlineStrong,
        padding = PaddingValues(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 6.dp),
    ) {
        ScLabel("Map layers", Modifier.padding(bottom = 2.dp))
        LayerRow("Roads", show.roads) { onChange(show.copy(roads = it)) }
        LayerRow("Distance rings", show.rings) { onChange(show.copy(rings = it)) }
        LayerRow("Place names", show.names) { onChange(show.copy(names = it)) }
    }
}

@Composable
private fun LayerRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = ScType.body, color = Sc.colors.text, modifier = Modifier.weight(1f))
        ScSwitch(on, onChange)
    }
}

// ---------------------------------------------------------------------------------- map

@Composable
private fun DarkSkyMap(
    layers: MapLayers,
    spots: List<DarkSpot>,
    featured: DarkSpot?,
    probe: DarkSpot?,
    view: MapView,
    show: MapShow,
    onSpot: (DarkSpot) -> Unit,
    /** A tap away from every marker, in the map's km. */
    onPoint: (Float, Float) -> Unit,
    modifier: Modifier,
) {
    val c = Sc.colors
    val measurer = rememberTextMeasurer()
    val labels = remember { HashMap<String, TextLayoutResult>() }
    val ink = remember(c) { mapInk(c) }
    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { view.size = it }
            .semantics { contentDescription = "Light pollution map" }
            .pointerInput(layers) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val w = size.width / 2f
                    val h = size.height / 2f
                    val old = view.scale
                    if (old <= 0f) return@detectTransformGestures
                    val new = (old * zoom).coerceIn(0.25f, 80f)
                    val kmX = view.panX + (centroid.x - w) / old
                    val kmY = view.panY + (centroid.y - h) / old
                    view.panX = (kmX - (centroid.x - w) / new - pan.x / new).coerceIn(-REGION_KM.toFloat(), REGION_KM.toFloat())
                    view.panY = (kmY - (centroid.y - h) / new - pan.y / new).coerceIn(-REGION_KM.toFloat(), REGION_KM.toFloat())
                    view.scale = new
                }
            }
            .pointerInput(layers, spots, probe) {
                detectTapGestures { pos ->
                    if (view.scale <= 0f) return@detectTapGestures
                    fun dist2(s: DarkSpot): Float {
                        val p = view.toScreen(layers.km(s.lat, s.lon))
                        return (p.x - pos.x) * (p.x - pos.x) + (p.y - pos.y) * (p.y - pos.y)
                    }
                    val hit = (spots + listOfNotNull(probe)).minByOrNull(::dist2)
                        ?.takeIf { dist2(it) < 28.dp.toPx() * 28.dp.toPx() }
                    if (hit != null) {
                        onSpot(hit)
                    } else {
                        onPoint(view.panX + (pos.x - size.width / 2f) / view.scale,
                            view.panY + (pos.y - size.height / 2f) / view.scale)
                    }
                }
            },
    ) {
        if (view.scale > 0f) drawMap(layers, spots, featured, probe, view, measurer, labels, ink, show, c.night)
    }
}

private val TownStyle = TextStyle(fontFamily = NotoSans, fontSize = 10.sp, fontWeight = FontWeight.Medium,
    shadow = androidx.compose.ui.graphics.Shadow(Color(0xDD000000), Offset.Zero, 5f))
private val SiteStyle = TownStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold)

private fun DrawScope.drawMap(
    l: MapLayers,
    spots: List<DarkSpot>,
    featured: DarkSpot?,
    probe: DarkSpot?,
    view: MapView,
    measurer: TextMeasurer,
    labels: HashMap<String, TextLayoutResult>,
    ink: MapInk,
    show: MapShow,
    night: Boolean,
) {
    val panX = view.panX
    val panY = view.panY
    val scale = view.scale
    val cx = size.width / 2f
    val cy = size.height / 2f
    fun sx(x: Float) = cx + (x - panX) * scale
    fun sy(y: Float) = cy + (y - panY) * scale
    drawRect(if (night) WaterNight else WaterDay)

    // ---- the light pollution, only the part on screen
    val r = l.glowRect
    val visLeft = max(r.left, panX - cx / scale)
    val visRight = min(r.right, panX + cx / scale)
    val visTop = max(r.top, panY - cy / scale)
    val visBottom = min(r.bottom, panY + cy / scale)
    if (visRight > visLeft && visBottom > visTop) {
        val pxPerKmX = l.glow.width / r.width
        val pxPerKmY = l.glow.height / r.height
        val srcL = floor((visLeft - r.left) * pxPerKmX).toInt().coerceIn(0, l.glow.width - 1)
        val srcT = floor((visTop - r.top) * pxPerKmY).toInt().coerceIn(0, l.glow.height - 1)
        val srcR = kotlin.math.ceil((visRight - r.left) * pxPerKmX).toInt().coerceIn(srcL + 1, l.glow.width)
        val srcB = kotlin.math.ceil((visBottom - r.top) * pxPerKmY).toInt().coerceIn(srcT + 1, l.glow.height)
        val dl = sx(r.left + srcL / pxPerKmX)
        val dt = sy(r.top + srcT / pxPerKmY)
        val dr = sx(r.left + srcR / pxPerKmX)
        val db = sy(r.top + srcB / pxPerKmY)
        drawImage(
            l.glow, srcOffset = IntOffset(srcL, srcT), srcSize = IntSize(srcR - srcL, srcB - srcT),
            dstOffset = IntOffset(dl.roundToInt(), dt.roundToInt()),
            dstSize = IntSize((dr - dl).roundToInt().coerceAtLeast(1), (db - dt).roundToInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.Low,
        )
    }

    // ---- lines, in km under one transform
    val px = 1f / scale
    val lineDay = night.not()
    withTransform({
        translate(cx - panX * scale, cy - panY * scale)
        scale(scale, scale, Offset.Zero)
    }) {
        if (show.roads) {
            val roadColors = if (lineDay) listOf(Color(0xCCF2E6C8), Color(0x99E8DCC0), Color(0x66D8D0C0))
            else listOf(Color(0xAA7A1E14), Color(0x885E1810), Color(0x66481208))
            val roadWidths = floatArrayOf(1.6f, 1.1f, 0.8f)
            // Minor roads only once zoomed in enough to tell them apart.
            val roadClasses = when {
                scale > 3f -> 3
                scale > 1.2f -> 2
                else -> 1
            }
            for (k in roadClasses - 1 downTo 0) {
                drawPath(l.roads[k], roadColors[k], style = Stroke(roadWidths[k] * density * px, cap = StrokeCap.Round))
            }
        }
        drawPath(l.lakes, if (lineDay) Color(0x8885B4E8) else Color(0x66803020), style = Stroke(0.8f * density * px))
        drawPath(l.coast, if (lineDay) Color(0xCC9CC8F0) else Color(0x99A03020), style = Stroke(1.1f * density * px))
        drawPath(l.borders, if (lineDay) Color(0xAAE0E0F0) else Color(0x88A03020),
            style = Stroke(1f * density * px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * density * px, 4f * density * px))))
        if (show.rings) {
            // Distance rings around you.
            for (ring in listOf(25f, 50f, 100f, 150f)) {
                drawCircle(if (lineDay) Color(0x55FFFFFF) else Color(0x55FF5040), ring, Offset.Zero,
                    style = Stroke(1f * density * px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * density * px, 5f * density * px))))
            }
        }
    }
    if (show.rings) {
        // Ring labels, to the south of you.
        for (ring in listOf(25, 50, 100, 150)) {
            val y = sy(ring.toFloat())
            if (y in 0f..size.height) labelAt("$ring km", Offset(sx(0f) + 4f, y - 14f * density), ink.label.copy(alpha = 0.7f), measurer, labels, TownStyle)
        }
    }

    // ---- towns, largest first, without overlaps
    val placed = ArrayList<Rect>()
    if (show.names) {
        val maxTowns = 40
        var shown = 0
        val minPop = when {
            scale > 6f -> 20_000
            scale > 2f -> 60_000
            scale > 0.8f -> 200_000
            else -> 700_000
        }
        for (t in l.towns) {
            if (shown >= maxTowns) break
            if (t.place.value < minPop) continue
            val x = sx(t.x)
            val y = sy(t.y)
            if (x < 0 || y < 0 || x > size.width || y > size.height) continue
            val layout = labels.getOrPut("t|${t.place.name}") { measurer.measure(t.place.name, TownStyle) }
            val box = Rect(x + 5f * density, y - layout.size.height / 2f, x + 5f * density + layout.size.width, y + layout.size.height / 2f)
            if (placed.any { it.overlaps(box) }) continue
            placed += box
            drawCircle(ink.label, 2.2f * density, Offset(x, y))
            drawText(layout, color = ink.label, topLeft = Offset(box.left, box.top))
            shown++
        }
    }
    // ---- dark-sky places: a star
    for (s in l.sites) {
        val x = sx(s.x)
        val y = sy(s.y)
        if (x < -20 || y < -20 || x > size.width + 20 || y > size.height + 20) continue
        drawStar(Offset(x, y), 6f * density, ink.site)
        if (show.names && scale > 0.6f) {
            val layout = labels.getOrPut("s|${s.place.name}") { measurer.measure(s.place.name, SiteStyle) }
            val box = Rect(x + 8f * density, y - layout.size.height / 2f, x + 8f * density + layout.size.width, y + layout.size.height / 2f)
            if (placed.none { it.overlaps(box) }) {
                placed += box
                drawText(layout, color = ink.site, topLeft = Offset(box.left, box.top))
            }
        }
    }
    // ---- the suggestions, numbered, the one in the panel lit and on top; the tapped point; you
    fun number(i: Int, p: Offset, sp: Int, color: Color) {
        val text = "${i + 1}"
        val layout = labels.getOrPut("n|$text|$sp") {
            measurer.measure(text, TextStyle(fontFamily = NotoSans, fontSize = sp.sp, fontWeight = FontWeight.Bold))
        }
        drawText(layout, color = color, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
    }
    spots.forEachIndexed { i, s ->
        if (s == featured) return@forEachIndexed
        val p = view.toScreen(l.km(s.lat, s.lon))
        val rad = 10f * density
        drawCircle(ink.ink, rad + 2f * density, p)
        drawCircle(ink.mark, rad, p)
        number(i, p, 11, ink.ink)
    }
    probe?.let { s ->
        val p = view.toScreen(l.km(s.lat, s.lon))
        if (s == featured) drawCircle(ink.glow, 18f * density, p)
        drawCircle(ink.ink, 9f * density, p)
        drawCircle(if (s == featured) ink.chosenRim else ink.mark.copy(alpha = 0.7f), 7f * density, p, style = Stroke(2.5f * density))
    }
    val chosen = featured?.let { spots.indexOf(it) } ?: -1
    if (featured != null && chosen >= 0) {
        // The place in the panel: bigger, deep violet, lit (the owner's design).
        val p = view.toScreen(l.km(featured.lat, featured.lon))
        val rad = 14f * density
        drawCircle(ink.glow.copy(alpha = ink.glow.alpha * 0.45f), rad * 2.1f, p)
        drawCircle(ink.glow, rad * 1.5f, p)
        drawCircle(ink.chosenRim, rad + 2f * density, p)
        drawCircle(Brush.verticalGradient(ink.chosen, startY = p.y - rad, endY = p.y + rad), rad, p)
        number(chosen, p, 13, ink.chosenText)
    }
    // You: a lit dot in a light ring (in night vision a dark one: nothing white).
    val here = Offset(sx(0f), sy(0f))
    drawCircle(ink.glow.copy(alpha = ink.glow.alpha * 0.6f), 16f * density, here)
    drawCircle(ink.youRim, 9f * density, here)
    drawCircle(ink.you, 6.5f * density, here)
}

private fun DrawScope.drawStar(c: Offset, r: Float, color: Color) {
    val path = Path()
    for (i in 0 until 10) {
        val a = Math.PI / 2 + i * Math.PI / 5
        val rr = if (i % 2 == 0) r else r * 0.45f
        val x = c.x + (rr * cos(a)).toFloat()
        val y = c.y - (rr * kotlin.math.sin(a)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, Color(0xFF0B1020), style = Stroke(2.5f * density))
    drawPath(path, color)
}

private fun DrawScope.labelAt(
    text: String, at: Offset, color: Color, measurer: TextMeasurer, cache: HashMap<String, TextLayoutResult>, style: TextStyle,
) {
    val layout = cache.getOrPut("l|$text") { measurer.measure(text, style) }
    drawText(layout, color = color, topLeft = at)
}
