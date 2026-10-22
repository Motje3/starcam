package com.wm.starcam.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.wm.starcam.sensors.DeviceLocation
import com.wm.starcam.sensors.PointingSensor
import com.wm.starcam.sensors.SkyPlace
import com.wm.starcam.sensors.SkyPlaceStore
import com.wm.starcam.sky.GeoLocation
import com.wm.starcam.sky.MoonPhase
import com.wm.starcam.sky.NightPlan
import com.wm.starcam.sky.NightPlanner
import com.wm.starcam.sky.SkyMath
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.components.ScCard
import com.wm.starcam.ui.components.ScDialog
import com.wm.starcam.ui.components.ScGlass
import com.wm.starcam.ui.components.ScIconButton
import com.wm.starcam.ui.components.ScLabel
import com.wm.starcam.ui.components.ScNotice
import com.wm.starcam.ui.components.ScScreen
import com.wm.starcam.ui.components.ScSpinner
import com.wm.starcam.ui.components.ScTopBar
import com.wm.starcam.ui.components.Tone
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScShapes
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.ui.theme.ScreenMargin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ------------------------------------------------------------------------------- helpers

/**
 * The clock a night is planned and shown on: the phone's, unless the place lies far from it
 * (planning somewhere else, a phone set to another zone) - then that place's own solar time, so
 * "tonight" is that place's night and not a noon-to-noon window cut through the middle of it.
 */
private fun planZone(loc: GeoLocation): ZoneId {
    val phone = ZoneId.systemDefault()
    val phoneHours = phone.rules.getOffset(Instant.now()).totalSeconds / 3600.0
    val solarHours = loc.lonDeg / 15.0
    if (abs(phoneHours - solarHours) <= 3.0) return phone
    val quarterHours = (solarHours * 4).roundToInt().coerceIn(-18 * 4, 18 * 4)
    return ZoneOffset.ofTotalSeconds(quarterHours * 900)
}

/** The night that is "tonight": until noon it is the one that began yesterday evening. */
private fun tonightDate(zone: ZoneId): LocalDate {
    val now = ZonedDateTime.now(zone)
    return if (now.hour < 12) now.toLocalDate().minusDays(1) else now.toLocalDate()
}

private val HourMinute: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

private fun hm(ms: Long?, zone: ZoneId): String =
    ms?.let { Instant.ofEpochMilli(it).atZone(zone).format(HourMinute) } ?: "-"

private val CompassPoints = listOf(
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
)

private fun compass(azDeg: Double): String = CompassPoints[((azDeg / 22.5).roundToInt() % 16 + 16) % 16]

private fun placeLabel(p: GeoLocation): String = String.format(
    Locale.US, "%.2f°%s %.2f°%s",
    abs(p.latDeg), if (p.latDeg >= 0) "N" else "S", abs(p.lonDeg), if (p.lonDeg >= 0) "E" else "W",
)

/** Short state of tonight for the capture screen's pill: "Dark 20:21", "Dark until 05:58". */
private fun pillText(p: NightPlan?, nowMs: Long, zone: ZoneId): String {
    if (p == null) return "Tonight"
    val dusk = p.astroDuskMs
    val dawn = p.astroDawnMs
    return when {
        dusk == null || dawn == null -> "No dark night"
        nowMs < dusk -> "Dark ${hm(dusk, zone)}"
        nowMs < dawn -> "Dark until ${hm(dawn, zone)}"
        else -> "Tonight"
    }
}

/**
 * The capture screen's way in, on the viewfinder: tonight's darkness at a glance, computed from
 * the place last used in the planner (no location request from here).
 */
@Composable
fun TonightChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Sc.colors
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    var text by remember { mutableStateOf("Tonight") }
    LaunchedEffect(Unit) {
        text = withContext(Dispatchers.Default) {
            val place = SkyPlaceStore(context).load()
            val z = place?.let { planZone(it.loc) } ?: zone
            val plan = place?.let { runCatching { NightPlanner.plan(tonightDate(z), z, it.loc) }.getOrNull() }
            pillText(plan, System.currentTimeMillis(), z)
        }
    }
    ScGlass(
        modifier.clip(ScShapes.pill).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Tonight planner: $text" },
    ) {
        Icon(ScIcons.Moon, null, Modifier.size(14.dp), tint = c.accent)
        Text(text, style = ScType.caption, color = c.text)
    }
}

// -------------------------------------------------------------------------------- screen

/**
 * The Tonight planner: when it gets properly dark where you are, when the Moon is out of the way,
 * when and where the Milky Way's core stands, and which way to point the phone. Everything is
 * worked out on the phone (sky/NightPlanner.kt); the location never leaves it.
 */
@Composable
fun TonightScreen(onBack: () -> Unit) {
    val c = Sc.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SkyPlaceStore(context) }
    var place by remember { mutableStateOf(store.load()) }
    val zone = remember(place) { place?.let { planZone(it.loc) } ?: ZoneId.systemDefault() }
    var locating by remember { mutableStateOf(false) }
    var locateFailed by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    var plan by remember { mutableStateOf<NightPlan?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val date = remember(dayOffset, zone) { tonightDate(zone).plusDays(dayOffset.toLong()) }

    fun locate() {
        locating = true
        locateFailed = false
        scope.launch {
            val loc = DeviceLocation.current(context)
            if (loc != null) {
                val p = SkyPlace(loc, manual = false, atMs = System.currentTimeMillis())
                store.save(p)
                place = p
            } else {
                locateFailed = true
            }
            locating = false
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locate() else locateFailed = true
    }
    fun useMyLocation() {
        if (DeviceLocation.permitted(context)) locate()
        else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    // A stored device location is refreshed quietly on every visit; a typed-in place is kept.
    LaunchedEffect(Unit) {
        if (DeviceLocation.permitted(context) && place?.manual != true) locate()
    }
    LaunchedEffect(place, date) {
        val p = place ?: return@LaunchedEffect
        plan = withContext(Dispatchers.Default) { NightPlanner.plan(date, zone, p.loc) }
    }

    ScScreen {
        ScTopBar(
            title = if (dayOffset == 0) "Tonight" else date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)),
            subtitle = place?.let { placeLabel(it.loc) } ?: "Planner",
            onBack = onBack,
            actions = {
                if (place != null) {
                    ScIconButton(ScIcons.ChevronLeft, "Night before", { dayOffset-- }, enabled = dayOffset > -1)
                    Spacer(Modifier.width(6.dp))
                    ScIconButton(ScIcons.ChevronRight, "Night after", { dayOffset++ }, enabled = dayOffset < 30)
                }
            },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenMargin, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val p = place
            if (p == null) {
                ScCard(Modifier.fillMaxWidth()) {
                    Text("Where are you shooting?", style = ScType.headline, color = c.text)
                    Text(
                        "The planner works out when it gets properly dark, when the Moon is out of the " +
                            "way and where the Milky Way stands, for where you are. Your location stays on " +
                            "this phone: StarCam has no internet access at all.",
                        style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 4.dp),
                    )
                    ScButton(
                        if (locating) "Finding you…" else "Use my location", ::useMyLocation,
                        Modifier.fillMaxWidth().padding(top = 12.dp), kind = ScButtonKind.Primary,
                        icon = ScIcons.Focus, enabled = !locating, height = 52.dp,
                    )
                    ScButton("Enter coordinates", { showManual = true }, Modifier.fillMaxWidth().padding(top = 8.dp))
                    if (locateFailed) {
                        Text(
                            "No location: location is off, or StarCam may not use it. Enter coordinates instead.",
                            style = ScType.bodySmall, color = c.warning, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                return@Column
            }
            val pl = plan
            if (pl == null) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { ScSpinner(size = 28.dp) }
            } else {
                SummaryCard(pl, zone)
                TimelineCard(pl, if (dayOffset == 0) nowMs else null, zone)
                if (dayOffset == 0) PointCard(pl, p.loc, nowMs, zone)
                MoonCard(pl, zone)
                TimesCard(pl, zone)
            }
            // ---- where
            ScCard(Modifier.fillMaxWidth()) {
                ScLabel("Location")
                Text(
                    placeLabel(p.loc) + if (p.manual) " · entered by hand" else " · from this phone",
                    style = ScType.body, color = c.text, modifier = Modifier.padding(top = 4.dp),
                )
                if (zone != ZoneId.systemDefault()) {
                    Text(
                        "Far from this phone's time zone: times are that place's solar time " +
                            "(UTC" + Instant.now().atZone(zone).offset.id.replace("Z", "+00:00") + ").",
                        style = ScType.caption, color = c.text3, modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (locateFailed) {
                    Text("Could not get a fresh location; using the last one.", style = ScType.caption,
                        color = c.warning, modifier = Modifier.padding(top = 4.dp))
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScButton(if (locating) "Locating…" else "Use my location", ::useMyLocation, Modifier.weight(1f),
                        enabled = !locating)
                    ScButton("Enter coordinates", { showManual = true }, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (showManual) {
        ManualPlaceDialog(
            initial = place?.loc,
            onDismiss = { showManual = false },
            onSave = { loc ->
                val p = SkyPlace(loc, manual = true, atMs = System.currentTimeMillis())
                store.save(p)
                place = p
                showManual = false
            },
        )
    }
}

// --------------------------------------------------------------------------------- cards

@Composable
private fun SummaryCard(p: NightPlan, zone: ZoneId) {
    val c = Sc.colors
    ScCard(Modifier.fillMaxWidth()) {
        val dusk = p.astroDuskMs
        val dawn = p.astroDawnMs
        Text(
            when {
                dusk != null && dawn != null -> "Dark from ${hm(dusk, zone)} to ${hm(dawn, zone)}"
                p.nauticalDuskMs != null -> "Never fully dark tonight"
                else -> "No real darkness tonight"
            },
            style = ScType.titleSmall, color = c.text,
        )
        Text(
            when {
                dusk != null && dawn != null -> "The Sun is more than 18° down: as dark as the sky gets."
                p.nauticalDuskMs != null && p.nauticalDawnMs != null ->
                    "Midsummer twilight: darkest from ${hm(p.nauticalDuskMs, zone)} to ${hm(p.nauticalDawnMs, zone)}."
                p.nauticalDuskMs != null -> "Midsummer twilight: the sky stays light all night."
                else -> "The Sun stays too close to the horizon."
            },
            style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(12.dp))
        val best = p.bestCoreWindow
        val (icon, tone, text) = when {
            best != null -> Triple(
                ScIcons.Astro, Tone.Accent,
                "Best for the Milky Way: ${hm(best.startMs, zone)} - ${hm(best.endMs, zone)} " +
                    "(${durationText(best.durationMs / 1000)}), the core up to ${p.coreMaxAltDeg.roundToInt()}° " +
                    "in the ${compass(coreAzimuthAt(p, p.coreTransitMs))}.",
            )
            p.coreMaxAltDeg < 0 -> Triple(
                ScIcons.Info, Tone.Normal,
                "The Milky Way's core never rises here. The band itself still crosses the sky: " +
                    "shoot it higher up, or stars and trails.",
            )
            p.coreMaxAltDeg < NightPlanner.CORE_MIN_ALT_DEG -> Triple(
                ScIcons.Info, Tone.Normal,
                "The core stays low tonight: at most ${p.coreMaxAltDeg.roundToInt()}° up, around " +
                    "${hm(p.coreTransitMs, zone)}. Find a clear southern horizon, or shoot the band higher up.",
            )
            p.darkWindows.isEmpty() -> Triple(
                ScIcons.Info, Tone.Normal,
                "No moon-free darkness tonight. Bright moonlight drowns the Milky Way; try a later night " +
                    "(the arrows above), or shoot moonlit landscapes and trails.",
            )
            else -> Triple(
                ScIcons.Info, Tone.Normal,
                "The core is up, but only in twilight or moonlight. Try a later night with the arrows above.",
            )
        }
        ScNotice(text, tone = tone, icon = icon)
        if (p.darkWindows.isNotEmpty()) {
            Text(
                "Moon-free dark: " + p.darkWindows.joinToString(", ") { "${hm(it.startMs, zone)}-${hm(it.endMs, zone)}" },
                style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

private fun coreAzimuthAt(p: NightPlan, ms: Long?): Double =
    if (ms == null) 180.0 else SkyMath.galacticCenter(ms, p.location).azDeg

@Composable
private fun TimelineCard(p: NightPlan, nowMs: Long?, zone: ZoneId) {
    val c = Sc.colors
    val measurer = rememberTextMeasurer()
    ScCard(Modifier.fillMaxWidth()) {
        ScLabel("The night")
        val start = ((p.sunsetMs ?: (p.windowStartMs + 5 * 3_600_000L)) - 30 * 60_000L).coerceAtLeast(p.windowStartMs)
        var end = ((p.sunriseMs ?: (p.windowEndMs - 5 * 3_600_000L)) + 30 * 60_000L).coerceAtMost(p.windowEndMs)
        if (end <= start + 3_600_000L) end = p.windowEndMs
        val labelStyle = ScType.data.copy(color = c.text3)
        Canvas(Modifier.fillMaxWidth().height(170.dp).padding(top = 8.dp)) {
            val labelH = 18.dp.toPx()
            val plotH = size.height - labelH
            fun x(t: Long) = ((t - start).toFloat() / (end - start).toFloat()) * size.width
            fun y(alt: Double) = plotH - (alt.coerceIn(0.0, 90.0) / 90.0).toFloat() * plotH
            // Darkness: the brighter the sky, the more of the text colour shows through.
            for (s in p.samples) {
                if (s.epochMs < start - 600_000L || s.epochMs > end) continue
                val a = when {
                    s.sunAlt > -0.833 -> 0.16f
                    s.sunAlt > -6 -> 0.11f
                    s.sunAlt > -12 -> 0.07f
                    s.sunAlt > -18 -> 0.035f
                    else -> 0f
                }
                if (a > 0f) {
                    val x0 = x(s.epochMs).coerceAtLeast(0f)
                    val x1 = x(s.epochMs + 600_000L).coerceAtMost(size.width)
                    if (x1 > x0) drawRect(c.text.copy(alpha = a), Offset(x0, 0f), Size(x1 - x0, plotH))
                }
            }
            p.bestCoreWindow?.let { b ->
                val x0 = x(b.startMs).coerceIn(0f, size.width)
                val x1 = x(b.endMs).coerceIn(0f, size.width)
                drawRect(c.accent.copy(alpha = 0.16f), Offset(x0, 0f), Size(x1 - x0, plotH))
            }
            // The core's working minimum, and the horizon.
            drawLine(c.hairline, Offset(0f, y(NightPlanner.CORE_MIN_ALT_DEG)), Offset(size.width, y(NightPlanner.CORE_MIN_ALT_DEG)),
                strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            drawLine(c.hairlineStrong, Offset(0f, plotH), Offset(size.width, plotH), strokeWidth = 1.dp.toPx())
            altitudeCurve(p, start, end, ::x, ::y, { it.moonAlt }, c.text2.copy(alpha = 0.7f), 2.dp.toPx())
            altitudeCurve(p, start, end, ::x, ::y, { it.coreAlt }, c.accent, 3.dp.toPx())
            nowMs?.let { n ->
                if (n in start..end) {
                    drawLine(c.warning, Offset(x(n), 0f), Offset(x(n), plotH), strokeWidth = 1.5.dp.toPx())
                }
            }
            // Hour labels every two hours.
            val firstHour = Instant.ofEpochMilli(start).atZone(zone).withMinute(0).withSecond(0).withNano(0).plusHours(1)
            var t = firstHour
            while (t.toInstant().toEpochMilli() < end) {
                if (t.hour % 2 == 0) {
                    val label = measurer.measure(t.format(HourMinute), labelStyle)
                    val lx = x(t.toInstant().toEpochMilli()) - label.size.width / 2f
                    if (lx > 0 && lx + label.size.width < size.width) {
                        drawText(label, topLeft = Offset(lx, plotH + 4.dp.toPx()))
                    }
                }
                t = t.plusHours(1)
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendSwatch(c.accent)
            Text("Milky Way core", style = ScType.caption, color = c.text2)
            Spacer(Modifier.width(12.dp))
            LegendSwatch(c.text2.copy(alpha = 0.7f))
            Text("Moon", style = ScType.caption, color = c.text2)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(10.dp).clip(ScShapes.chip).background(c.accent.copy(alpha = 0.3f)))
            Spacer(Modifier.width(5.dp))
            Text("best", style = ScType.caption, color = c.text2)
        }
        Text("Height above the horizon; the dashed line is 10°. Shading: twilight.",
            style = ScType.caption, color = c.text3, modifier = Modifier.padding(top = 2.dp))
    }
}

private fun DrawScope.altitudeCurve(
    p: NightPlan,
    start: Long,
    end: Long,
    x: (Long) -> Float,
    y: (Double) -> Float,
    alt: (com.wm.starcam.sky.NightSample) -> Double,
    color: Color,
    width: Float,
) {
    val path = Path()
    var drawing = false
    for (s in p.samples) {
        if (s.epochMs < start - 600_000L || s.epochMs > end + 600_000L) continue
        val a = alt(s)
        if (a < 0.0) {
            drawing = false
            continue
        }
        val px = x(s.epochMs)
        val py = y(a)
        if (drawing) path.lineTo(px, py) else path.moveTo(px, py)
        drawing = true
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
}

@Composable
private fun LegendSwatch(color: Color) {
    Box(Modifier.size(width = 14.dp, height = 3.dp).background(color))
    Spacer(Modifier.width(5.dp))
}

/** Where the core is now, and a sight to bring the phone onto it. */
@Composable
private fun PointCard(p: NightPlan, loc: GeoLocation, nowMs: Long, zone: ZoneId) {
    val c = Sc.colors
    val context = LocalContext.current
    val sensor = remember { PointingSensor(context) }
    DisposableEffect(loc) {
        sensor.setPlace(loc.latDeg, loc.lonDeg)
        sensor.start()
        onDispose { sensor.stop() }
    }
    val aim by sensor.aim.collectAsState()
    val core = remember(nowMs, loc) { SkyMath.galacticCenter(nowMs, loc) }
    val moon = remember(nowMs, loc) { SkyMath.moon(nowMs, loc) }
    ScCard(Modifier.fillMaxWidth()) {
        ScLabel("Point the phone")
        if (core.altDeg < 0) {
            val rise = p.coreRiseMs?.takeIf { it > nowMs }
            Text(
                if (rise != null) "The core is below the horizon. It rises at ${hm(rise, zone)} in the " +
                    "${compass(SkyMath.galacticCenter(rise, loc).azDeg)}."
                else "The core is below the horizon now.",
                style = ScType.body, color = c.text, modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Text(
                "The core is ${core.altDeg.roundToInt()}° up in the ${compass(core.azDeg)} " +
                    "(${core.azDeg.roundToInt()}°).",
                style = ScType.body, color = c.text, modifier = Modifier.padding(top = 4.dp),
            )
            val a = aim
            if (sensor.available && a != null) {
                var dAz = core.azDeg - a.azDeg
                while (dAz > 180) dAz -= 360.0
                while (dAz < -180) dAz += 360.0
                val dAlt = core.altDeg - a.altDeg
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Sight(dAz, dAlt, Modifier.size(132.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        val onTarget = abs(dAz) < 6 && abs(dAlt) < 6
                        Text(
                            if (onTarget) "On the core" else listOfNotNull(
                                if (abs(dAz) >= 3) "Turn ${if (dAz > 0) "right" else "left"} ${abs(dAz).roundToInt()}°" else null,
                                if (abs(dAlt) >= 3) "tilt ${if (dAlt > 0) "up" else "down"} ${abs(dAlt).roundToInt()}°" else null,
                            ).joinToString(", "),
                            style = ScType.headline, color = if (onTarget) c.accentText else c.text,
                        )
                        Text(
                            "Move the phone until the dot is in the middle. Compass readings drift near metal and " +
                                "indoors; a figure-of-eight wave recalibrates it.",
                            style = ScType.caption, color = c.text3, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        if (moon.altDeg > 0) {
            Text(
                "Moon: ${moon.altDeg.roundToInt()}° up in the ${compass(moon.azDeg)}. Keep it out of the frame; " +
                    "its glare spreads far.",
                style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** The target's offset from where the phone points: centre = on target, the ring = 45° away. */
@Composable
private fun Sight(dAz: Double, dAlt: Double, modifier: Modifier) {
    val c = Sc.colors
    Canvas(modifier.clip(ScShapes.card).background(c.sunken).border(1.dp, c.hairline, ScShapes.card)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val r = min(size.width, size.height) * 0.42f
        drawCircle(c.hairlineStrong, r, center, style = Stroke(1.dp.toPx()))
        drawCircle(c.hairline, r * 0.33f, center, style = Stroke(1.dp.toPx()))
        drawLine(c.hairline, Offset(center.x - r, center.y), Offset(center.x + r, center.y), 1.dp.toPx())
        drawLine(c.hairline, Offset(center.x, center.y - r), Offset(center.x, center.y + r), 1.dp.toPx())
        var tx = (dAz / 45.0).toFloat() * r
        var ty = (-dAlt / 45.0).toFloat() * r
        val d = kotlin.math.sqrt(tx * tx + ty * ty)
        val outside = d > r
        if (outside) {
            tx *= r / d
            ty *= r / d
        }
        drawCircle(c.accent, if (outside) 6.dp.toPx() else 9.dp.toPx(), Offset(center.x + tx, center.y + ty))
    }
}

@Composable
private fun MoonCard(p: NightPlan, zone: ZoneId) {
    val c = Sc.colors
    val m = p.moonPhase
    ScCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MoonGlyph(m, Modifier.size(52.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("${m.name} · ${(m.illuminated * 100).roundToInt()}% lit", style = ScType.headline, color = c.text)
                Text(
                    listOfNotNull(
                        p.moonriseMs?.let { "rises ${hm(it, zone)}" },
                        p.moonsetMs?.let { "sets ${hm(it, zone)}" },
                    ).joinToString(" · ").ifEmpty { "no rise or set in this night" }
                        .replaceFirstChar { it.uppercase() },
                    style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    when {
                        m.illuminated < NightPlanner.DIM_MOON_FRACTION -> "Too thin to brighten the sky."
                        m.illuminated < 0.5 -> "Brightens the sky while it is up; shoot when it is down."
                        else -> "Washes out the Milky Way while it is up."
                    },
                    style = ScType.caption, color = c.text3, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** The Moon's phase as seen from the northern hemisphere: lit on the right while waxing. */
@Composable
private fun MoonGlyph(m: MoonPhase, modifier: Modifier) {
    val c = Sc.colors
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(c.raised, r, center)
        val f = m.illuminated.toFloat().coerceIn(0f, 1f)
        if (f <= 0.01f) {
            drawCircle(c.hairlineStrong, r, center, style = Stroke(1.dp.toPx()))
            return@Canvas
        }
        // The lit side is a half disc; the terminator an ellipse whose half-width runs from r
        // (new) through 0 (quarter) to -r (full), bulging into the dark half past a quarter.
        val sign = if (m.waxing) 1f else -1f
        val path = Path()
        val steps = 48
        for (i in 0..steps) {
            val t = Math.PI * (-0.5 + i.toDouble() / steps)
            val px = center.x + sign * r * cos(t).toFloat()
            val py = center.y + r * sin(t).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        val k = 1f - 2f * f
        for (i in steps downTo 0) {
            val t = Math.PI * (-0.5 + i.toDouble() / steps)
            val px = center.x + sign * k * r * cos(t).toFloat()
            val py = center.y + r * sin(t).toFloat()
            path.lineTo(px, py)
        }
        path.close()
        drawPath(path, c.text.copy(alpha = 0.85f))
        drawCircle(c.hairlineStrong, r, center, style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun TimesCard(p: NightPlan, zone: ZoneId) {
    val c = Sc.colors
    ScCard(Modifier.fillMaxWidth()) {
        ScLabel("Times")
        val rows = listOf(
            "Sunset" to p.sunsetMs,
            "Nautical dusk (−12°)" to p.nauticalDuskMs,
            "Astronomical dusk (−18°)" to p.astroDuskMs,
            "Core rises" to p.coreRiseMs,
            "Core highest (${p.coreMaxAltDeg.roundToInt()}°)" to p.coreTransitMs,
            "Core sets" to p.coreSetMs,
            "Moonrise" to p.moonriseMs,
            "Moonset" to p.moonsetMs,
            "Astronomical dawn" to p.astroDawnMs,
            "Nautical dawn" to p.nauticalDawnMs,
            "Sunrise" to p.sunriseMs,
        ).filter { it.second != null }.sortedBy { it.second }
        for ((label, ms) in rows) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = ScType.body, color = c.text, modifier = Modifier.weight(1f))
                Text(hm(ms, zone), style = ScType.data, color = c.text2)
            }
        }
    }
}

// -------------------------------------------------------------------------------- dialog

@Composable
private fun ManualPlaceDialog(initial: GeoLocation?, onDismiss: () -> Unit, onSave: (GeoLocation) -> Unit) {
    val c = Sc.colors
    var lat by remember { mutableStateOf(initial?.let { String.format(Locale.US, "%.4f", it.latDeg) } ?: "") }
    var lon by remember { mutableStateOf(initial?.let { String.format(Locale.US, "%.4f", it.lonDeg) } ?: "") }
    val latV = lat.replace(',', '.').toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val lonV = lon.replace(',', '.').toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    ScDialog(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp)) {
            Text("Enter a place", style = ScType.headline, color = c.text)
            Text(
                "Decimal degrees, as a map app shows them: north and east positive, south and west " +
                    "negative. Two decimals (about a kilometre) is plenty.",
                style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 4.dp),
            )
            CoordinateField("Latitude", "52.37", lat, { lat = it }, latV != null || lat.isEmpty())
            CoordinateField("Longitude", "4.90", lon, { lon = it }, lonV != null || lon.isEmpty())
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScButton("Cancel", onDismiss, Modifier.weight(1f))
                ScButton("Use this place", { if (latV != null && lonV != null) onSave(GeoLocation(latV, lonV)) },
                    Modifier.weight(1f), kind = ScButtonKind.Primary, enabled = latV != null && lonV != null)
            }
        }
    }
}

@Composable
private fun CoordinateField(label: String, hint: String, value: String, onChange: (String) -> Unit, valid: Boolean) {
    val c = Sc.colors
    Column(Modifier.padding(top = 12.dp)) {
        ScLabel(label)
        Box(
            Modifier.fillMaxWidth().padding(top = 6.dp).height(48.dp).clip(ScShapes.chip).background(c.sunken)
                .border(1.dp, if (valid) c.hairline else c.warning, ScShapes.chip).padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) Text(hint, style = ScType.body, color = c.text3)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = ScType.body.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
