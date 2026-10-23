package com.wm.starcam.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.wm.starcam.sensors.DeviceLocation
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
import kotlin.math.roundToInt
import kotlin.math.sin

// ------------------------------------------------------------------------------- helpers

/**
 * The clock a night is planned and shown on: the phone's, unless the place lies far from it
 * (planning somewhere else, a phone set to another zone) - then that place's own solar time, so
 * "tonight" is that place's night and not a noon-to-noon window cut through the middle of it.
 */
internal fun planZone(loc: GeoLocation): ZoneId {
    val phone = ZoneId.systemDefault()
    val phoneHours = phone.rules.getOffset(Instant.now()).totalSeconds / 3600.0
    val solarHours = loc.lonDeg / 15.0
    if (abs(phoneHours - solarHours) <= 3.0) return phone
    val quarterHours = (solarHours * 4).roundToInt().coerceIn(-18 * 4, 18 * 4)
    return ZoneOffset.ofTotalSeconds(quarterHours * 900)
}

/** The night that is "tonight": until noon it is the one that began yesterday evening. */
internal fun tonightDate(zone: ZoneId): LocalDate {
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

internal fun placeLabel(p: GeoLocation): String = String.format(
    Locale.US, "%.2f°%s %.2f°%s",
    abs(p.latDeg), if (p.latDeg >= 0) "N" else "S", abs(p.lonDeg), if (p.lonDeg >= 0) "E" else "W",
)

/** Short state of tonight for the capture screen's button: "Dark 20:21", "Dark now". */
private fun pillText(p: NightPlan?, nowMs: Long, zone: ZoneId): String {
    if (p == null) return "Tonight"
    val dusk = p.astroDuskMs
    val dawn = p.astroDawnMs
    return when {
        dusk == null || dawn == null -> "Tonight"  // no full darkness: the planner says so
        nowMs < dusk -> "Dark ${hm(dusk, zone)}"
        nowMs < dawn -> "Dark now"
        else -> "Tonight"
    }
}

/**
 * Tonight's darkness at a glance for the capture screen, from the place last used in the planner
 * (no location request from here).
 */
@Composable
fun rememberTonightText(): String {
    val context = LocalContext.current
    var text by remember { mutableStateOf("Tonight") }
    LaunchedEffect(Unit) {
        text = withContext(Dispatchers.Default) {
            val place = SkyPlaceStore(context).load()
            val z = place?.let { planZone(it.loc) } ?: ZoneId.systemDefault()
            val plan = place?.let { runCatching { NightPlanner.plan(tonightDate(z), z, it.loc) }.getOrNull() }
            pillText(plan, System.currentTimeMillis(), z)
        }
    }
    return text
}

// -------------------------------------------------------------------------------- screen

/**
 * The Tonight planner: when it gets properly dark where you are, when the Moon is out of the way,
 * when and where the Milky Way's core stands, and which way to point the phone. Everything is
 * worked out on the phone (sky/NightPlanner.kt); the location never leaves it.
 */
@Composable
fun TonightScreen(
    onBack: () -> Unit,
    onShowSky: (Long?) -> Unit,
    /** Plan for this place instead of yours (a dark spot from the map), without saving it. */
    there: GeoLocation? = null,
    thereLabel: String? = null,
    onOpenDarkSky: (() -> Unit)? = null,
    onOpenCalendar: (() -> Unit)? = null,
    /** Open on this night instead of tonight (from the sky calendar). */
    initialDate: LocalDate? = null,
) {
    val c = Sc.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SkyPlaceStore(context) }
    var place by remember {
        mutableStateOf(there?.let { SkyPlace(it, manual = true, atMs = System.currentTimeMillis()) } ?: store.load())
    }
    val zone = remember(place) { place?.let { planZone(it.loc) } ?: ZoneId.systemDefault() }
    var locating by remember { mutableStateOf(false) }
    var locateFailed by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var dayOffset by rememberSaveable {
        mutableIntStateOf(initialDate?.let { java.time.temporal.ChronoUnit.DAYS.between(tonightDate(zone), it).toInt() } ?: 0)
    }
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
        if (there == null && DeviceLocation.permitted(context) && place?.manual != true) locate()
    }
    LaunchedEffect(place, date) {
        val p = place ?: return@LaunchedEffect
        plan = withContext(Dispatchers.Default) { NightPlanner.plan(date, zone, p.loc) }
    }

    ScScreen {
        ScTopBar(
            title = if (dayOffset == 0) "Tonight" else date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)),
            subtitle = thereLabel ?: place?.let { placeLabel(it.loc) } ?: "Planner",
            onBack = onBack,
            actions = {
                if (place != null) {
                    ScIconButton(ScIcons.ChevronLeft, "Night before", { dayOffset-- }, enabled = dayOffset > -1)
                    Spacer(Modifier.width(6.dp))
                    ScIconButton(ScIcons.ChevronRight, "Night after", { dayOffset++ }, enabled = dayOffset < 400)
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
                // The answer first, the night at a glance, the sky itself one tap away; the
                // numbers behind it only on request.
                TonightAnswer(pl, if (dayOffset == 0) nowMs else null, zone, onShowSky)
                NightBar(pl, if (dayOffset == 0) nowMs else null, zone)
                ScButton(
                    if (showDetails) "Hide the details" else "Details: heights, the Moon, all the times",
                    { showDetails = !showDetails }, Modifier.fillMaxWidth(), kind = ScButtonKind.Text, height = 40.dp,
                )
                if (showDetails) {
                    TimelineCard(pl, if (dayOffset == 0) nowMs else null, zone)
                    MoonCard(pl, zone)
                    TimesCard(pl, zone)
                }
            }
            if (onOpenCalendar != null) CalendarCard(p.loc, onOpenCalendar)
            if (onOpenDarkSky != null) DarkSkyCard(p.loc, onOpenDarkSky)
            // ---- where
            if (there != null) {
                ScCard(Modifier.fillMaxWidth()) {
                    ScLabel("Planned for")
                    Text(
                        (thereLabel ?: "A dark spot") + " · " + placeLabel(there),
                        style = ScType.body, color = c.text, modifier = Modifier.padding(top = 4.dp),
                    )
                    Text("Your own place in the planner stays as it is.", style = ScType.caption,
                        color = c.text3, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                return@Column
            }
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

/** How dark your own sky is, and the way to the map of darker ones. */
@Composable
private fun DarkSkyCard(loc: GeoLocation, onOpen: () -> Unit) {
    val c = Sc.colors
    val context = LocalContext.current
    var bortle by remember(loc) { mutableStateOf<Int?>(null) }
    LaunchedEffect(loc) {
        bortle = withContext(Dispatchers.Default) {
            runCatching {
                val g = com.wm.starcam.darksky.DarkSkyData.glow(context)
                com.wm.starcam.darksky.SkyQuality.bortle(com.wm.starcam.darksky.SkyQuality.sqm(g.alr(loc.latDeg, loc.lonDeg)))
            }.getOrNull()
        }
    }
    ScCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ScIcons.Map, null, Modifier.size(22.dp), tint = c.accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Find a darker sky", style = ScType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Text(
                    bortle?.let { "Your sky: Bortle $it, ${com.wm.starcam.darksky.SkyQuality.name(it).lowercase()}. See the darker places within reach." }
                        ?: "Light pollution around you and the darker places within reach.",
                    style = ScType.bodySmall, color = c.text2,
                )
            }
            Icon(ScIcons.ChevronRight, null, Modifier.size(18.dp), tint = c.text3)
        }
    }
}

/**
 * Tonight in one card: a verdict, the best time in big type, where to look, the darkness and the
 * Moon in a line each, and the sky view. [nowMs] is null for other nights than tonight.
 */
@Composable
private fun TonightAnswer(p: NightPlan, nowMs: Long?, zone: ZoneId, onShowSky: (Long?) -> Unit) {
    val c = Sc.colors
    val best = p.bestCoreWindow
    // Where the core stands matters only while the sky is fully dark (the transit itself can be
    // in daylight): its highest point then.
    val darkPeak = p.samples.filter { it.sunAlt < NightPlanner.ASTRONOMICAL_TWILIGHT_ALT_DEG }.maxByOrNull { it.coreAlt }
    val moonLit = "${(p.moonPhase.illuminated * 100).roundToInt()}% lit"
    val verdict = when {
        best != null && nowMs != null && nowMs in best.startMs..best.endMs -> "Milky Way time: now"
        best != null && (nowMs == null || nowMs < best.endMs) -> "A good night for the Milky Way"
        best != null -> "Tonight's Milky Way window is over"
        p.coreMaxAltDeg < 0 -> "The Milky Way's core never rises here"
        darkPeak == null -> "It never gets fully dark tonight"
        darkPeak.coreAlt < 0 -> "The core is down while it is dark"
        darkPeak.coreAlt < p.coreMinAltDeg -> "The core stays low tonight"
        p.darkWindows.isEmpty() -> "Too bright for the Milky Way tonight"
        else -> "The Moon is up while the core is"
    }
    val advice = when {
        best != null -> {
            // Highest in the window: at the transit if it falls inside, else at an end.
            val peak = listOfNotNull(best.startMs, best.endMs, p.coreTransitMs?.takeIf { it in best.startMs..best.endMs })
                .maxOf { SkyMath.galacticCenter(it, p.location).altDeg }
            "${durationText(best.durationMs / 1000)} of dark sky with the core up: look " +
                "${compass(coreAzimuthAt(p, best.startMs))}, up to ${peak.roundToInt()}° high" +
                if (peak < NightPlanner.CORE_MIN_ALT_DEG) " - low, so find an open horizon that way." else "."
        }
        p.coreMaxAltDeg < 0 ->
            "The band of the Milky Way still crosses the sky: shoot it higher up, or stars and trails."
        darkPeak == null ->
            "The Sun stays less than 18° below the horizon, so a glow lingers all night and the Milky " +
                "Way is faint. Try another night with the arrows above."
        darkPeak.coreAlt < 0 -> {
            val set = p.coreSetMs
            val rise = p.coreRiseMs
            val dusk = p.astroDuskMs
            val dawn = p.astroDawnMs
            when {
                set != null && dusk != null && set <= dusk ->
                    "It sets at ${hm(set, zone)}, before the sky is fully dark at ${hm(dusk, zone)}. "
                rise != null && dawn != null && rise >= dawn ->
                    "It rises at ${hm(rise, zone)}, after the dark ends at ${hm(dawn, zone)}. "
                else -> "It is below the horizon through the dark hours. "
            } + "The rest of the Milky Way is up: the sky view shows where."
        }
        darkPeak.coreAlt < p.coreMinAltDeg ->
            "At most ${darkPeak.coreAlt.roundToInt()}° up while it is dark, around ${hm(darkPeak.epochMs, zone)}: " +
                "you need an open view to the ${compass(darkPeak.coreAz)}."
        p.darkWindows.isEmpty() ->
            "The Moon ($moonLit) is up all through the dark hours. Try another night with the arrows " +
                "above, or shoot moonlit landscapes and trails."
        else ->
            "The core is highest in the dark at ${hm(darkPeak.epochMs, zone)}, with the Moon up ($moonLit); " +
                "the dark hours without the Moon come when the core is down."
    }
    ScCard(Modifier.fillMaxWidth()) {
        Text(verdict, style = ScType.title, color = c.text)
        if (best != null) {
            Text("${hm(best.startMs, zone)} – ${hm(best.endMs, zone)}", style = ScType.readout,
                color = c.accentText, modifier = Modifier.padding(top = 6.dp))
        }
        Text(advice, style = ScType.body, color = c.text2, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        val dusk = p.astroDuskMs
        val dawn = p.astroDawnMs
        Fact(ScIcons.Night, "Dark",
            if (dusk != null && dawn != null) "${hm(dusk, zone)} – ${hm(dawn, zone)}" else "never fully dark")
        Fact(ScIcons.Moon, "Moon",
            listOfNotNull(
                moonLit,
                p.moonriseMs?.let { "rises ${hm(it, zone)}" },
                p.moonsetMs?.let { "sets ${hm(it, zone)}" },
            ).joinToString(" · "))
        Fact(ScIcons.Astro, "Core",
            if (p.coreMaxAltDeg < 0) "below the horizon all night"
            else listOfNotNull(
                p.coreRiseMs?.let { "rises ${hm(it, zone)}" },
                "highest ${hm(p.coreTransitMs, zone)}",
                p.coreSetMs?.let { "sets ${hm(it, zone)}" },
            ).joinToString(" · "))
        // The sky view: at the start of the best window when that is still ahead, or when the
        // core stands highest in the dark; else now.
        val target = (best?.startMs ?: darkPeak?.takeIf { it.coreAlt > 0 }?.epochMs)?.takeIf { nowMs == null || it > nowMs }
        ScButton(
            if (target != null) "Show me the sky at ${hm(target, zone)}" else "Show me the sky now",
            { onShowSky(target) }, Modifier.fillMaxWidth().padding(top = 14.dp),
            kind = ScButtonKind.Primary, icon = ScIcons.Astro, height = 52.dp,
        )
        Text(
            "Hold the phone up: the Milky Way, the stars and the Moon are drawn over the camera where " +
                "they are.",
            style = ScType.caption, color = c.text3, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun Fact(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    val c = Sc.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = c.accent)
        Text(label, style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(start = 8.dp).width(54.dp))
        Text(value, style = ScType.bodySmall, color = c.text, modifier = Modifier.weight(1f))
    }
}

/**
 * The night at a glance, sunset to sunrise: twilight, moonlight, true darkness, and the best
 * window for the Milky Way on top; a line for now.
 */
@Composable
private fun NightBar(p: NightPlan, nowMs: Long?, zone: ZoneId) {
    val c = Sc.colors
    val measurer = rememberTextMeasurer()
    val start = p.sunsetMs ?: p.windowStartMs
    val end = p.sunriseMs ?: p.windowEndMs
    if (end <= start) return
    val twilight = c.text.copy(alpha = 0.14f)
    val moonlight = c.text.copy(alpha = 0.32f)
    val dark = c.accent.copy(alpha = 0.22f)
    val bestColor = c.accent
    ScCard(Modifier.fillMaxWidth()) {
        ScLabel("The night")
        val labelStyle = ScType.data.copy(color = c.text3)
        Canvas(Modifier.fillMaxWidth().height(58.dp).padding(top = 8.dp)) {
            val barH = 26.dp.toPx()
            fun x(t: Long) = ((t - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f) * size.width
            // One block per stretch of the same light (translucent blocks side by side striped).
            var runColor: Color? = null
            var runStart = start
            fun flush(until: Long) {
                runColor?.let { drawRect(it, Offset(x(runStart), 0f), Size(x(until) - x(runStart), barH)) }
            }
            for (s in p.samples) {
                if (s.epochMs + 600_000L < start || s.epochMs > end) continue
                val col = when {
                    s.sunAlt > -18 -> twilight
                    s.moonAlt > 0 && p.moonPhase.illuminated >= NightPlanner.DIM_MOON_FRACTION -> moonlight
                    else -> dark
                }
                if (col != runColor) {
                    flush(s.epochMs.coerceAtLeast(start))
                    runColor = col
                    runStart = s.epochMs.coerceAtLeast(start)
                }
            }
            flush(end)
            p.bestCoreWindow?.let { b ->
                drawRect(bestColor.copy(alpha = 0.85f), Offset(x(b.startMs), barH * 0.25f),
                    Size(x(b.endMs) - x(b.startMs), barH * 0.5f))
            }
            nowMs?.let { n ->
                if (n in start..end) drawLine(c.warning, Offset(x(n), -3.dp.toPx()), Offset(x(n), barH + 3.dp.toPx()), 2.dp.toPx())
            }
            fun timeLabel(t: Long, align: Float) {
                val l = measurer.measure(hm(t, zone), labelStyle)
                val lx = (x(t) - l.size.width * align).coerceIn(0f, size.width - l.size.width)
                drawText(l, topLeft = Offset(lx, barH + 6.dp.toPx()))
            }
            timeLabel(start, 0f)
            timeLabel(end, 1f)
            p.bestCoreWindow?.let { b ->
                val mid = (b.startMs + b.endMs) / 2
                if (x(mid) > size.width * 0.18f && x(mid) < size.width * 0.82f) timeLabel(mid, 0.5f)
            }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend(twilight, "Twilight")
            Legend(moonlight, "Moonlight")
            Legend(dark, "Dark")
            Legend(bestColor, "Best")
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    val c = Sc.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(ScShapes.chip).background(color).border(1.dp, c.hairline, ScShapes.chip))
        Text(text, style = ScType.caption, color = c.text2, modifier = Modifier.padding(start = 5.dp))
    }
}

private fun coreAzimuthAt(p: NightPlan, ms: Long?): Double =
    if (ms == null) 180.0 else SkyMath.galacticCenter(ms, p.location).azDeg

@Composable
private fun TimelineCard(p: NightPlan, nowMs: Long?, zone: ZoneId) {
    val c = Sc.colors
    val measurer = rememberTextMeasurer()
    ScCard(Modifier.fillMaxWidth()) {
        ScLabel("How high, hour by hour")
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
            drawLine(c.hairline, Offset(0f, y(p.coreMinAltDeg)), Offset(size.width, y(p.coreMinAltDeg)),
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
        Text("Height above the horizon; the dashed line is ${p.coreMinAltDeg.roundToInt()}°, the core's working height " +
            "here. Shading: twilight.",
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
