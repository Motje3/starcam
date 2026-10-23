package com.wm.starcam.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wm.starcam.darksky.DarkSkyData
import com.wm.starcam.darksky.DarkSpot
import com.wm.starcam.darksky.GlowGrid
import com.wm.starcam.darksky.MapPlace
import com.wm.starcam.darksky.Polylines
import com.wm.starcam.darksky.SkyQuality
import com.wm.starcam.darksky.SpotFinder
import com.wm.starcam.sensors.DeviceLocation
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
import com.wm.starcam.ui.components.ScTopBar
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScShapes
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.ui.theme.ScreenMargin
import com.wm.starcam.util.AppLog
import kotlinx.coroutines.Dispatchers
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

// -------------------------------------------------------------------------------- screen

/** A tapped point on the map: its sky, as a spot of its own. */
private fun probeSpot(data: DarkSkyData, layers: MapLayers, x: Float, y: Float, hereLat: Double, hereLon: Double): DarkSpot {
    val (lat, lon) = layers.latLon(x, y)
    return DarkSpot(
        lat, lon, data.glow.alr(lat, lon), SpotFinder.distanceKm(hereLat, hereLon, lat, lon),
        SpotFinder.bearingDeg(hereLat, hereLon, lat, lon), "This spot", SpotFinder.describe(data.towns, lat, lon),
    )
}

/**
 * Dark skies near you: how dark your own sky is, the darker places within reach on a map of light
 * pollution, and the way there (handed to the maps app: StarCam itself never goes online).
 */
@Composable
fun DarkSkyScreen(onBack: () -> Unit, onPlanThere: (GeoLocation, String) -> Unit) {
    val c = Sc.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SkyPlaceStore(context) }
    var place by remember { mutableStateOf(store.load()) }
    var locating by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf<DarkSkyData?>(null) }
    var layers by remember { mutableStateOf<MapLayers?>(null) }
    var result by remember { mutableStateOf<SpotFinder.Result?>(null) }
    var selected by remember { mutableStateOf<DarkSpot?>(null) }
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

    ScScreen {
        ScTopBar(
            title = "Dark skies",
            subtitle = place?.let { "Light pollution near you" } ?: "Find a darker sky",
            onBack = onBack,
        )
        val p = place
        if (p == null) {
            Column(Modifier.fillMaxWidth().padding(ScreenMargin), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ScCard(Modifier.fillMaxWidth()) {
                    Text("Where are you?", style = ScType.headline, color = c.text)
                    Text(
                        "The map shows how much light pollution there is around you and where the nearest " +
                            "darker skies are. Your location stays on this phone: StarCam has no internet access.",
                        style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 4.dp),
                    )
                    ScButton(if (locating) "Finding you…" else "Use my location", {
                        if (DeviceLocation.permitted(context)) locate()
                        else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }, Modifier.fillMaxWidth().padding(top = 12.dp), kind = ScButtonKind.Primary,
                        icon = ScIcons.Pin, enabled = !locating, height = 52.dp)
                    Text("Or type coordinates in the Tonight planner.", style = ScType.caption, color = c.text3,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
            return@ScScreen
        }
        val l = layers
        val r = result
        if (l == null || r == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ScSpinner(size = 28.dp) }
            return@ScScreen
        }
        Box(Modifier.fillMaxWidth().weight(0.56f)) {
            DarkSkyMap(l, data!!, r, selected, onSelect = { selected = it },
                hereLat = p.loc.latDeg, hereLon = p.loc.lonDeg, modifier = Modifier.fillMaxSize())
            Legend(night, Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        Column(
            Modifier.fillMaxWidth().weight(0.44f).verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenMargin, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // A picked spot comes first, with its way there; then your own sky and the list.
            val sel = selected
            if (sel != null) {
                SpotCard(sel, number = r.all.indexOf(sel).takeIf { it >= 0 }?.plus(1), night = night, onNavigate = {
                    openInMaps(context, sel)
                }, onPlan = { onPlanThere(GeoLocation(sel.lat, sel.lon), spotLabel(sel)) }, onClose = { selected = null })
            }
            HereCard(r, night)
            if (r.all.isEmpty()) {
                Text(
                    if (r.hereBortle <= 2) "Your sky is about as dark as skies get: nothing darker within 150 km."
                    else "Nothing clearly darker within 150 km.",
                    style = ScType.bodySmall, color = c.text2,
                )
            } else {
                ScLabel("Darker skies within reach")
                r.all.forEachIndexed { i, s ->
                    if (s != sel) SpotRow(i + 1, s, night) { selected = s }
                }
            }
            Text(
                "Estimated from satellite night lights (NPP-VIIRS-like 2025, Chen et al., CC0) with the " +
                    "Duriscoe 2018 sky-glow model; map: Natural Earth. City centres can be brighter than shown, " +
                    "and the sky also needs no cloud and no Moon. Check access and safety before you go.",
                style = ScType.caption, color = c.text3,
            )
            Spacer(Modifier.height(4.dp))
        }
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
private fun BortleBadge(bortle: Int, night: Boolean, size: androidx.compose.ui.unit.Dp = 34.dp) {
    val bg = bortleColor(bortle, night)
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).border(1.dp, Sc.colors.hairlineStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$bortle", style = ScType.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = if (bortle <= 3 || night) Color.White else Color(0xFF101010))
    }
}

@Composable
private fun HereCard(r: SpotFinder.Result, night: Boolean) {
    val c = Sc.colors
    ScCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BortleBadge(r.hereBortle, night, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                ScLabel("Your sky")
                Text("Bortle ${r.hereBortle} · ${SkyQuality.name(r.hereBortle)}", style = ScType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Text(String.format(Locale.US, "About %.1f mag/arcsec² at the zenith", r.hereSqm), style = ScType.caption, color = c.text3)
            }
        }
        Text(SkyQuality.milkyWay(r.hereBortle), style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 8.dp))
        r.nearestDarker?.let { d ->
            Text(
                "Nearest darker sky: ${d.distanceKm.roundToInt()} km ${SpotFinder.compass(d.bearingDeg)}, Bortle ${d.bortle}.",
                style = ScType.bodySmall.copy(fontWeight = FontWeight.Medium), color = c.accentText,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun SpotRow(number: Int, s: DarkSpot, night: Boolean, onClick: () -> Unit) {
    val c = Sc.colors
    Row(
        Modifier.fillMaxWidth().clip(ScShapes.card).background(c.surface).border(1.dp, c.hairline, ScShapes.card)
            .clickable(role = Role.Button, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NumberDot(number)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = ScType.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1)
            Text(
                "${s.distanceKm.roundToInt()} km ${SpotFinder.compass(s.bearingDeg)}" + (s.near?.let { " · $it" } ?: ""),
                style = ScType.caption, color = c.text2, maxLines = 1,
            )
        }
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
private fun SpotCard(s: DarkSpot, number: Int?, night: Boolean, onNavigate: () -> Unit, onPlan: () -> Unit, onClose: () -> Unit) {
    val c = Sc.colors
    ScCard(Modifier.fillMaxWidth(), borderColor = c.selectedBorder) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (number != null) {
                NumberDot(number)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(s.title, style = ScType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Text(
                    "${s.distanceKm.roundToInt()} km ${SpotFinder.compass(s.bearingDeg)} as the crow flies" +
                        (s.near?.let { " · $it" } ?: ""),
                    style = ScType.caption, color = c.text2,
                )
            }
            ScIconButton(ScIcons.Close, "Close the spot", onClose, size = 36.dp, iconSize = 16.dp, filled = false)
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            BortleBadge(s.bortle, night)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Bortle ${s.bortle} · ${SkyQuality.name(s.bortle)}", style = ScType.bodySmall.copy(fontWeight = FontWeight.Medium), color = c.text)
                Text(String.format(Locale.US, "About %.1f mag/arcsec²", s.sqm), style = ScType.caption, color = c.text3)
            }
        }
        Text(SkyQuality.milkyWay(s.bortle), style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScButton("Navigate", onNavigate, Modifier.weight(1f), kind = ScButtonKind.Primary, icon = ScIcons.Navigate)
            ScButton("Plan there", onPlan, Modifier.weight(1f), icon = ScIcons.Moon)
        }
    }
}

@Composable
private fun Legend(night: Boolean, modifier: Modifier) {
    val c = Sc.colors
    ScGlass(modifier, shape = ScShapes.chip, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 5.dp)) {
        Text("Bortle", style = ScType.caption.copy(fontSize = 10.sp), color = c.text2)
        for (b in 1..9) {
            Box(Modifier.size(width = 12.dp, height = 10.dp).background(bortleColor(b, night)))
        }
        Text("1 dark · 9 city", style = ScType.caption.copy(fontSize = 10.sp), color = c.text2)
    }
}

// ---------------------------------------------------------------------------------- map

@Composable
private fun DarkSkyMap(
    layers: MapLayers,
    data: DarkSkyData,
    result: SpotFinder.Result,
    selected: DarkSpot?,
    onSelect: (DarkSpot?) -> Unit,
    hereLat: Double,
    hereLon: Double,
    modifier: Modifier,
) {
    val c = Sc.colors
    val measurer = rememberTextMeasurer()
    val labels = remember { HashMap<String, TextLayoutResult>() }
    // The view: its centre in the map's km and its scale in px per km.
    var panX by remember(layers) { mutableFloatStateOf(0f) }
    var panY by remember(layers) { mutableFloatStateOf(0f) }
    var scale by remember(layers) { mutableFloatStateOf(0f) }
    val spots = result.all
    var probe by remember { mutableStateOf<DarkSpot?>(null) }

    // The first view fits you and every suggestion, at least 60 km around you.
    fun fit(w: Int, h: Int) {
        var reach = 60f
        for (s in spots) {
            val o = layers.km(s.lat, s.lon)
            reach = max(reach, max(abs(o.x), abs(o.y)) * 1.15f)
        }
        panX = 0f
        panY = 0f
        scale = min(w, h) / 2f / reach
    }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // A spot picked from the list but off the screen: bring it and you into view.
    LaunchedEffect(selected) {
        val s = selected ?: return@LaunchedEffect
        if (viewSize.width == 0 || scale <= 0f) return@LaunchedEffect
        val o = layers.km(s.lat, s.lon)
        val halfW = viewSize.width / 2f / scale
        val halfH = viewSize.height / 2f / scale
        if (abs(o.x - panX) > halfW * 0.85f || abs(o.y - panY) > halfH * 0.85f) {
            panX = o.x / 2f
            panY = o.y / 2f
            val reach = max(abs(o.x), abs(o.y)) / 2f * 1.3f + 8f
            scale = min(scale, min(viewSize.width, viewSize.height) / 2f / reach)
        }
    }
    Box(modifier.clip(androidx.compose.ui.graphics.RectangleShape).onSizeChanged {
        viewSize = it
        if (scale == 0f && it.width > 0) fit(it.width, it.height)
    }) {
        Canvas(
            Modifier.fillMaxSize()
                .semantics { contentDescription = "Light pollution map" }
                .pointerInput(layers) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val w = size.width / 2f
                        val h = size.height / 2f
                        val old = scale
                        if (old <= 0f) return@detectTransformGestures
                        val new = (old * zoom).coerceIn(0.25f, 80f)
                        val kmX = panX + (centroid.x - w) / old
                        val kmY = panY + (centroid.y - h) / old
                        panX = (kmX - (centroid.x - w) / new - pan.x / new).coerceIn(-REGION_KM.toFloat(), REGION_KM.toFloat())
                        panY = (kmY - (centroid.y - h) / new - pan.y / new).coerceIn(-REGION_KM.toFloat(), REGION_KM.toFloat())
                        scale = new
                    }
                }
                .pointerInput(layers, spots) {
                    detectTapGestures { pos ->
                        val w = size.width / 2f
                        val h = size.height / 2f
                        if (scale <= 0f) return@detectTapGestures
                        val hit = (spots + listOfNotNull(probe)).minByOrNull { s ->
                            val o = layers.km(s.lat, s.lon)
                            val sx = w + (o.x - panX) * scale
                            val sy = h + (o.y - panY) * scale
                            (sx - pos.x) * (sx - pos.x) + (sy - pos.y) * (sy - pos.y)
                        }?.takeIf { s ->
                            val o = layers.km(s.lat, s.lon)
                            val sx = w + (o.x - panX) * scale
                            val sy = h + (o.y - panY) * scale
                            (sx - pos.x) * (sx - pos.x) + (sy - pos.y) * (sy - pos.y) < (28.dp.toPx() * 28.dp.toPx())
                        }
                        if (hit != null) {
                            onSelect(hit)
                        } else {
                            val p = probeSpot(data, layers, panX + (pos.x - w) / scale, panY + (pos.y - h) / scale, hereLat, hereLon)
                            probe = p
                            onSelect(p)
                        }
                    }
                },
        ) {
            if (scale > 0f) drawMap(layers, spots, selected, probe, panX, panY, scale, measurer, labels, c.night)
        }
        ScIconButton(
            ScIcons.Focus, "Back to where you are", {
                if (viewSize.width > 0) fit(viewSize.width, viewSize.height)
            },
            Modifier.align(Alignment.TopEnd).padding(8.dp), size = 40.dp, iconSize = 18.dp,
        )
    }
}

private val TownStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium,
    shadow = androidx.compose.ui.graphics.Shadow(Color(0xDD000000), Offset.Zero, 5f))
private val SiteStyle = TownStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold)

private fun DrawScope.drawMap(
    l: MapLayers,
    spots: List<DarkSpot>,
    selected: DarkSpot?,
    probe: DarkSpot?,
    panX: Float,
    panY: Float,
    scale: Float,
    measurer: TextMeasurer,
    labels: HashMap<String, TextLayoutResult>,
    night: Boolean,
) {
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
        drawPath(l.lakes, if (lineDay) Color(0x8885B4E8) else Color(0x66803020), style = Stroke(0.8f * density * px))
        drawPath(l.coast, if (lineDay) Color(0xCC9CC8F0) else Color(0x99A03020), style = Stroke(1.1f * density * px))
        drawPath(l.borders, if (lineDay) Color(0xAAE0E0F0) else Color(0x88A03020),
            style = Stroke(1f * density * px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * density * px, 4f * density * px))))
        // Distance rings around you.
        for (ring in listOf(25f, 50f, 100f, 150f)) {
            drawCircle(if (lineDay) Color(0x55FFFFFF) else Color(0x55FF5040), ring, Offset.Zero,
                style = Stroke(1f * density * px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * density * px, 5f * density * px))))
        }
    }
    val textColor = if (night) Color(0xFFFF6A55) else Color(0xEEFFFFFF)
    // Ring labels, to the south of you.
    for (ring in listOf(25, 50, 100, 150)) {
        val y = sy(ring.toFloat())
        if (y in 0f..size.height) labelAt("$ring km", Offset(sx(0f) + 4f, y - 14f * density), textColor.copy(alpha = 0.7f), measurer, labels, TownStyle)
    }

    // ---- towns, largest first, without overlaps
    val placed = ArrayList<Rect>()
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
        drawCircle(textColor, 2.2f * density, Offset(x, y))
        drawText(layout, color = textColor, topLeft = Offset(box.left, box.top))
        shown++
    }
    // ---- dark-sky places: a star
    val siteColor = if (night) Color(0xFFFF8A60) else Color(0xFFFFE07A)
    for (s in l.sites) {
        val x = sx(s.x)
        val y = sy(s.y)
        if (x < -20 || y < -20 || x > size.width + 20 || y > size.height + 20) continue
        drawStar(Offset(x, y), 6f * density, siteColor)
        if (scale > 0.6f) {
            val layout = labels.getOrPut("s|${s.place.name}") { measurer.measure(s.place.name, SiteStyle) }
            val box = Rect(x + 8f * density, y - layout.size.height / 2f, x + 8f * density + layout.size.width, y + layout.size.height / 2f)
            if (placed.none { it.overlaps(box) }) {
                placed += box
                drawText(layout, color = siteColor, topLeft = Offset(box.left, box.top))
            }
        }
    }
    // ---- the suggestions, numbered; the tapped point; you
    val accent = if (night) Color(0xFFFF5A44) else Color(0xFF8FB8FF)
    spots.forEachIndexed { i, s ->
        val o = l.km(s.lat, s.lon)
        val p = Offset(sx(o.x), sy(o.y))
        val sel = s == selected
        val rad = (if (sel) 13f else 10f) * density
        if (sel) drawCircle(accent.copy(alpha = 0.35f), rad * 1.9f, p)
        drawCircle(Color(0xFF0B1020), rad + 2f * density, p)
        drawCircle(accent, rad, p)
        val text = "${i + 1}"
        val layout = labels.getOrPut("n|$text") { measurer.measure(text, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold)) }
        drawText(layout, color = Color(0xFF0B1020), topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
    }
    probe?.let { s ->
        val o = l.km(s.lat, s.lon)
        val p = Offset(sx(o.x), sy(o.y))
        drawCircle(Color(0xFF0B1020), 9f * density, p)
        drawCircle(if (s == selected) accent else accent.copy(alpha = 0.7f), 7f * density, p, style = Stroke(2.5f * density))
    }
    val here = Offset(sx(0f), sy(0f))
    drawCircle(Color.White, 9f * density, here)
    drawCircle(if (night) Color(0xFFFF3B30) else Color(0xFF2F7BFF), 6.5f * density, here)
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
