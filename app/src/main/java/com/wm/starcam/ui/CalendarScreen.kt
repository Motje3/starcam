package com.wm.starcam.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wm.starcam.alerts.NightAlerts
import com.wm.starcam.darksky.DarkSkyData
import com.wm.starcam.darksky.SkyQuality
import com.wm.starcam.sensors.DeviceLocation
import com.wm.starcam.sensors.SkyPlace
import com.wm.starcam.sensors.SkyPlaceStore
import com.wm.starcam.sky.SkyCalendar
import com.wm.starcam.sky.SkyEvent
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.components.ScCard
import com.wm.starcam.ui.components.ScChip
import com.wm.starcam.ui.components.ScLabel
import com.wm.starcam.ui.components.ScScreen
import com.wm.starcam.ui.components.ScSpinner
import com.wm.starcam.ui.components.ScSwitch
import com.wm.starcam.ui.components.ScTopBar
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScShapes
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.ui.theme.ScreenMargin
import com.wm.starcam.ui.theme.scGlow
import com.wm.starcam.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

/** What the calendar shows: everything, or one kind. */
private enum class CalendarFilter(val label: String, val kinds: Set<SkyEvent.Kind>?) {
    All("All", null),
    MilkyWay("Milky Way", setOf(SkyEvent.Kind.MILKY_WAY)),
    Meteors("Meteors", setOf(SkyEvent.Kind.METEORS)),
    Eclipses("Eclipses", setOf(SkyEvent.Kind.LUNAR_ECLIPSE, SkyEvent.Kind.SOLAR_ECLIPSE)),
    Moon("Moon", setOf(SkyEvent.Kind.NEW_MOON, SkyEvent.Kind.FULL_MOON)),
    Planets("Planets", setOf(SkyEvent.Kind.PLANET, SkyEvent.Kind.CONJUNCTION)),
}

internal fun kindIcon(kind: SkyEvent.Kind): ImageVector = when (kind) {
    SkyEvent.Kind.MILKY_WAY -> ScIcons.Astro
    SkyEvent.Kind.METEORS -> ScIcons.Meteor
    SkyEvent.Kind.NEW_MOON, SkyEvent.Kind.FULL_MOON -> ScIcons.Moon
    SkyEvent.Kind.LUNAR_ECLIPSE, SkyEvent.Kind.SOLAR_ECLIPSE -> ScIcons.Eclipse
    SkyEvent.Kind.PLANET, SkyEvent.Kind.CONJUNCTION -> ScIcons.Planet
}

/** The night an event belongs to: an early-morning sight is the night that began the evening before. */
internal fun nightDate(e: SkyEvent, zone: ZoneId): LocalDate {
    val local = Instant.ofEpochMilli(e.lookAtMs ?: e.atMs).atZone(zone)
    return if (local.hour < 12) local.toLocalDate().minusDays(1) else local.toLocalDate()
}

/**
 * The sky calendar: a year of what the sky does where you are - meteor showers rated for the
 * Moon and your sky, the Milky Way's nights and season, new and full moons, eclipses, planets at
 * their best and close pairings - each one opening in the sky view at its moment, planned in the
 * Tonight planner, or set as a reminder. The afternoon alerts live here too.
 */
@Composable
fun CalendarScreen(onBack: () -> Unit, onShowSky: (Long) -> Unit, onPlanNight: (LocalDate) -> Unit) {
    val c = Sc.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SkyPlaceStore(context) }
    var place by remember { mutableStateOf(store.load()) }
    var locating by remember { mutableStateOf(false) }
    var events by remember { mutableStateOf<List<SkyEvent>?>(null) }
    var filter by rememberSaveable { mutableStateOf(CalendarFilter.All) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var alertsOn by remember { mutableStateOf(NightAlerts.enabled(context)) }
    var reminders by remember { mutableStateOf(NightAlerts.reminders(context)) }
    val zone = remember(place) { place?.let { planZone(it.loc) } ?: ZoneId.systemDefault() }

    LaunchedEffect(place) {
        val p = place ?: return@LaunchedEffect
        events = withContext(Dispatchers.Default) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val sqm = runCatching { SkyQuality.sqm(DarkSkyData.glow(context).alr(p.loc.latDeg, p.loc.lonDeg)) }.getOrNull()
            SkyCalendar.events(System.currentTimeMillis() - 6 * 3_600_000L, 366, p.loc, zone, sqm).also {
                AppLog.i("calendar", "${it.size} events in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            }
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
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locate()
    }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        NightAlerts.setEnabled(context, granted)
        alertsOn = granted
    }
    fun setAlerts(on: Boolean) {
        if (on && !NightAlerts.canNotify(context)) {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        NightAlerts.setEnabled(context, on)
        alertsOn = on
    }

    ScScreen {
        ScTopBar(
            title = "Sky calendar",
            subtitle = if (place != null) "Plan your next night" else "What the sky does, and when",
            onBack = onBack,
            actions = { if (place != null) AlertsPill("Sky calendar", alertsOn, ::setAlerts) },
        )
        val p = place
        if (p == null) {
            Column(Modifier.padding(ScreenMargin)) {
                ScCard(Modifier.fillMaxWidth()) {
                    Text("Where are you?", style = ScType.headline, color = c.text)
                    Text(
                        "Meteor showers, eclipses and the Milky Way's nights depend on where you stand. Your " +
                            "location stays on this phone: Stelli has no internet access.",
                        style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 4.dp),
                    )
                    ScButton(if (locating) "Finding you…" else "Use my location", {
                        if (DeviceLocation.permitted(context)) locate()
                        else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }, Modifier.fillMaxWidth().padding(top = 12.dp), kind = ScButtonKind.Primary,
                        icon = ScIcons.Pin, enabled = !locating, height = 52.dp)
                }
            }
            return@ScScreen
        }
        val list = events
        val big = remember(list) { list?.let { SkyCalendar.nextBigNight(it, System.currentTimeMillis()) } }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = ScreenMargin, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("next") {
                // Held open while the year is worked out, so nothing jumps when it arrives.
                if (big != null) NextNightCard(big, zone, onPlanNight)
                else if (list == null) Box(Modifier.fillMaxWidth().height(NextNightHeight).clip(ScShapes.card).background(c.surface))
            }
            item("filters") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CalendarFilter.entries.forEach { f ->
                        ScChip(f.label, filter == f, { filter = f }, height = 40.dp,
                            icon = f.kinds?.first()?.let { kindIcon(it) } ?: ScIcons.Calendar)
                    }
                }
            }
            if (list == null) {
                item("loading") {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { ScSpinner(size = 28.dp) }
                }
                return@LazyColumn
            }
            val shown = list.filter { e -> filter.kinds?.contains(e.kind) ?: true }
            if (shown.isEmpty()) {
                item("empty") {
                    Text(
                        if (filter == CalendarFilter.MilkyWay) "No dark, moonless nights with the core up in the next 12 months from here."
                        else "Nothing of this kind in the next 12 months.",
                        style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            var month = ""
            shown.forEach { e ->
                val m = Instant.ofEpochMilli(e.atMs).atZone(zone).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US))
                if (m != month) {
                    month = m
                    item("month:$m") { ScLabel(m, Modifier.padding(top = 10.dp, bottom = 2.dp)) }
                }
                item(e.id) {
                    EventCard(
                        e, zone, expanded = open == e.id,
                        reminded = e.id in reminders,
                        onToggle = { open = if (open == e.id) null else e.id },
                        onShowSky = e.lookAtMs?.let { at -> { onShowSky(at) } },
                        onPlan = { onPlanNight(nightDate(e, zone)) },
                        onRemind = {
                            if (e.id in reminders) NightAlerts.removeReminder(context, e.id)
                            else {
                                if (!NightAlerts.canNotify(context)) {
                                    notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                NightAlerts.addReminder(context, e, zone)
                            }
                            reminders = NightAlerts.reminders(context)
                        },
                    )
                }
            }
            item("credits") {
                Text(
                    "Worked out on this phone for ${placeLabel(p.loc)}: the Sun, Moon and planets from Meeus' and JPL's " +
                        "methods, meteor showers from the IMO's list. Clouds are the one thing it cannot know.",
                    style = ScType.caption, color = c.text3, modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
                )
            }
        }
    }
}

/**
 * Night alerts on the top bar: a Milky Way window, a meteor peak, an eclipse, a close pairing,
 * said in the afternoon. A bell, the name where that fits beside [title], and the switch; the
 * whole pill is the switch.
 */
@Composable
private fun AlertsPill(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Sc.colors
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = ScType.caption.copy(fontWeight = FontWeight.Medium)
    BoxWithConstraints {
        fun width(s: String, st: TextStyle) = with(density) { measurer.measure(s, st).size.width.toDp() }
        val bare = 10.dp + 18.dp + 42.dp + 2.dp
        val words = width(title, ScType.screenTitle) + 10.dp + bare + 8.dp + width("Night alerts", style) <= maxWidth
        Row(
            Modifier.height(40.dp)
                .scGlow(c.accentGlow.copy(alpha = c.accentGlow.alpha * 0.35f), radius = 10.dp)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(c.raised, c.buttonSecondary)))
                .border(1.dp, c.selectedBorder.copy(alpha = 0.75f), CircleShape)
                .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
                .semantics { contentDescription = "Night alerts" }
                .padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ScIcons.Bell, null, Modifier.size(18.dp), tint = c.iconAccent)
            if (words) {
                Spacer(Modifier.width(5.dp))
                Text("Night alerts", style = style, color = c.text, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(3.dp))
            }
            ScSwitch(on, null, compact = true)
        }
    }
}

/** How tall the card at the head of the calendar stands. */
private val NextNightHeight = 200.dp

/**
 * The one night to look forward to, at the head of the calendar: a picture of what it is, the
 * date, its name, a few words and its rating in five dots. It opens that night in the planner.
 */
@Composable
private fun NextNightCard(e: SkyEvent, zone: ZoneId, onPlan: (LocalDate) -> Unit) {
    val c = Sc.colors
    // The night it is seen on; what has no moment to look at (a new moon) simply its date.
    val day = if (e.lookAtMs != null) nightDate(e, zone) else Instant.ofEpochMilli(e.atMs).atZone(zone).toLocalDate()
    val date = day.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
    val words = e.tagline ?: e.summary
    Box(
        Modifier.fillMaxWidth().height(NextNightHeight).clip(ScShapes.card).background(c.surface)
            .border(1.dp, c.hairlineStrong, ScShapes.card)
            .clickable(role = Role.Button, onClick = { onPlan(day) })
            .clearAndSetSemantics {
                contentDescription = listOf(date, e.title, words, e.rating.label).filter { it.isNotEmpty() }
                    .joinToString(". ") + ". Plan this night."
            },
    ) {
        NightPicture(e.kind, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.BottomStart).padding(start = 105.dp, end = 14.dp, bottom = 34.dp)) {
            Text(date, style = ScType.headline, color = c.accentText)
            Text(e.title, style = if (e.title.length <= 18) ScType.title else ScType.screenTitle, color = c.text,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(words, style = ScType.body, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (e.rating != SkyEvent.Rating.INFO) RatingDots(e.rating, Modifier.padding(top = 10.dp))
        }
    }
}

/** A rating as five dots and its word: Great fills all five, each step down one fewer. */
@Composable
private fun RatingDots(r: SkyEvent.Rating, modifier: Modifier) {
    val c = Sc.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(5) { i ->
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (i < 5 - r.ordinal) c.accent else c.hairlineStrong))
        }
        Text(r.label, style = ScType.bodySmall, color = c.text2)
    }
}

/** The card's picture: stars over hills against a violet horizon, and on the left what the night is about. */
@Composable
private fun NightPicture(kind: SkyEvent.Kind, modifier: Modifier) {
    val c = Sc.colors
    val stars = remember { Random(11).let { r -> List(54) { floatArrayOf(r.nextFloat(), r.nextFloat() * 0.72f, r.nextFloat()) } } }
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(0f to c.background, 0.6f to c.surface, 0.9f to c.accent.copy(alpha = 0.27f).compositeOver(c.surface)))
        stars.forEach { (x, y, b) ->
            // None behind the words, where a star reads as a stray dot.
            if (x * size.width > 96.dp.toPx() && y > 0.24f) return@forEach
            drawCircle(c.text.copy(alpha = 0.18f + b * 0.5f), (0.5f + b * 0.7f).dp.toPx(), Offset(x * size.width, y * size.height))
        }
        val o = Offset(54.dp.toPx(), size.height * 0.45f)
        val r = 34.dp.toPx()
        fun halo(at: Offset, radius: Float, alpha: Float) = drawCircle(
            Brush.radialGradient(listOf(c.accent.copy(alpha = alpha), Color.Transparent), at, radius), radius, at)
        when (kind) {
            SkyEvent.Kind.METEORS -> listOf(Triple(-26f, 36f, 80f), Triple(12f, 10f, 56f), Triple(-10f, -24f, 40f)).forEach { (x, y, length) ->
                val head = o + Offset(x.dp.toPx(), y.dp.toPx())
                // Each tail points back up the sky to the shower's radiant.
                val tail = head + Offset(0.62f, -0.78f) * length.dp.toPx()
                drawLine(Brush.linearGradient(listOf(c.accentText, Color.Transparent), head, tail), head, tail,
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(c.text, 1.6.dp.toPx(), head)
            }
            SkyEvent.Kind.MILKY_WAY -> withTransform({ rotate(-52f, o); scale(1f, 0.26f, o) }) {
                drawCircle(Brush.radialGradient(listOf(c.accentText.copy(alpha = 0.55f), Color.Transparent), o, r * 2.2f), r * 2.2f, o)
            }
            SkyEvent.Kind.LUNAR_ECLIPSE -> {
                halo(o, r * 1.6f, 0.3f)
                drawCircle(Brush.radialGradient(listOf(c.accent2, c.accent2.copy(alpha = 0.45f)), o - Offset(r, r) * 0.3f, r * 1.3f), r * 0.85f, o)
            }
            SkyEvent.Kind.PLANET -> {
                halo(o, r * 1.5f, 0.25f)
                drawCircle(Brush.radialGradient(listOf(c.accentText, c.accent.copy(alpha = 0.5f)), o - Offset(r, r) * 0.25f, r), r * 0.6f, o)
                withTransform({ rotate(-18f, o); scale(1f, 0.28f, o) }) {
                    drawCircle(c.accentText.copy(alpha = 0.7f), r * 1.05f, o, style = Stroke(2.dp.toPx()))
                }
            }
            SkyEvent.Kind.CONJUNCTION -> listOf(Triple(-12f, 8f, 7f), Triple(14f, -9f, 5f)).forEach { (x, y, radius) ->
                val at = o + Offset(x.dp.toPx(), y.dp.toPx())
                halo(at, radius.dp.toPx() * 3.2f, 0.5f)
                drawCircle(c.text, radius.dp.toPx(), at)
            }
            // The new moon: only just there, as it is on the night. (Nothing else gets this far.)
            else -> {
                halo(o, r * 1.6f, 0.14f)
                drawCircle(c.accent.copy(alpha = 0.1f), r, o)
                drawCircle(c.accent.copy(alpha = 0.35f), r, o, style = Stroke(1.dp.toPx()))
            }
        }
        hills(listOf(22f, 30f, 42f, 33f, 38f, 28f, 40f, 31f, 26f, 44f, 34f, 38f), c.accent.copy(alpha = 0.12f).compositeOver(c.surface))
        hills(listOf(16f, 22f, 18f, 27f, 20f, 15f, 24f, 18f, 28f, 21f, 16f, 22f), c.surface)
    }
}

/** A line of hills along the foot: [heights] in dp, evenly spaced, the ridge smoothed between them. */
private fun DrawScope.hills(heights: List<Float>, color: Color) {
    val step = size.width / (heights.size - 1)
    fun y(i: Int) = size.height - heights[i].dp.toPx()
    val ridge = Path().apply {
        moveTo(0f, size.height)
        lineTo(0f, y(0))
        for (i in 1 until heights.size) {
            val x = (i - 1) * step
            quadraticTo(x, y(i - 1), x + step / 2f, (y(i - 1) + y(i)) / 2f)
        }
        lineTo(size.width, y(heights.lastIndex))
        lineTo(size.width, size.height)
        close()
    }
    drawPath(ridge, color)
}

@Composable
private fun RatingChip(r: SkyEvent.Rating) {
    if (r == SkyEvent.Rating.INFO) return
    val c = Sc.colors
    val (bg, fg) = when (r) {
        SkyEvent.Rating.GREAT -> c.accent to c.background
        SkyEvent.Rating.GOOD -> c.selectedFill to c.accentText
        SkyEvent.Rating.FAIR -> c.raised to c.text2
        else -> c.sunken to c.text3
    }
    Box(Modifier.clip(ScShapes.pill).background(bg).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(r.label, style = ScType.caption.copy(fontWeight = FontWeight.SemiBold), color = fg)
    }
}

@Composable
private fun EventCard(
    e: SkyEvent,
    zone: ZoneId,
    expanded: Boolean,
    reminded: Boolean,
    onToggle: () -> Unit,
    onShowSky: (() -> Unit)?,
    onPlan: () -> Unit,
    onRemind: () -> Unit,
) {
    val c = Sc.colors
    val local = Instant.ofEpochMilli(e.atMs).atZone(zone)
    val strong = e.rating == SkyEvent.Rating.GREAT
    Column(
        Modifier.fillMaxWidth().clip(ScShapes.card).background(c.surface)
            .border(1.dp, if (strong) c.selectedBorder else c.hairline, ScShapes.card)
            .clickable(role = Role.Button, onClick = onToggle).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${local.dayOfMonth}", style = ScType.title, color = if (strong) c.accentText else c.text)
                Text(local.format(DateTimeFormatter.ofPattern("EEE", Locale.US)), style = ScType.caption, color = c.text3)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(kindIcon(e.kind), null, Modifier.size(16.dp), tint = c.accent)
                    Spacer(Modifier.width(6.dp))
                    Text(e.title, style = ScType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text,
                        modifier = Modifier.weight(1f), maxLines = 2)
                    RatingChip(e.rating)
                }
                Text(e.summary, style = ScType.bodySmall, color = c.text2, modifier = Modifier.padding(top = 3.dp),
                    maxLines = if (expanded) 6 else 2)
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 54.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                e.details.forEach { Text(it, style = ScType.bodySmall, color = c.text2) }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onShowSky != null) {
                        ScButton("Sky view", onShowSky, Modifier.weight(1f), kind = ScButtonKind.Primary, icon = ScIcons.Astro, height = 44.dp)
                    }
                    ScButton("Plan night", onPlan, Modifier.weight(1f), icon = ScIcons.Moon, height = 44.dp)
                }
                ScButton(if (reminded) "Reminder set · tap to remove" else "Remind me", onRemind, Modifier.fillMaxWidth(),
                    kind = ScButtonKind.Text, icon = ScIcons.Bell, height = 40.dp)
            }
        }
    }
}

/** The planner's way into the calendar: the next two notable things. */
@Composable
internal fun CalendarCard(loc: com.wm.starcam.sky.GeoLocation, onOpen: () -> Unit) {
    val c = Sc.colors
    val context = LocalContext.current
    val zone = remember(loc) { planZone(loc) }
    var next by remember(loc) { mutableStateOf<List<SkyEvent>?>(null) }
    LaunchedEffect(loc) {
        next = withContext(Dispatchers.Default) {
            runCatching {
                val now = System.currentTimeMillis()
                val sqm = runCatching { SkyQuality.sqm(DarkSkyData.glow(context).alr(loc.latDeg, loc.lonDeg)) }.getOrNull()
                upcomingHighlights(SkyCalendar.events(now - 6 * 3_600_000L, 75, loc, zone, sqm), now)
            }.getOrDefault(emptyList())
        }
    }
    ScCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ScIcons.Calendar, null, Modifier.size(22.dp), tint = c.accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Sky calendar", style = ScType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                val n = next
                Text(
                    when {
                        n == null -> "Meteor showers, eclipses, the Milky Way's nights…"
                        n.isEmpty() -> "Meteor showers, eclipses, the Milky Way's nights and alerts for good nights."
                        else -> "Next: " + n.joinToString(" · ") { e ->
                            e.title + ", " + Instant.ofEpochMilli(e.atMs).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM", Locale.US))
                        }
                    },
                    style = ScType.bodySmall, color = c.text2,
                )
            }
            Icon(ScIcons.ChevronRight, null, Modifier.size(18.dp), tint = c.text3)
        }
    }
}

/** The next notable things, for the planner's card. */
internal fun upcomingHighlights(all: List<SkyEvent>, nowMs: Long): List<SkyEvent> =
    all.filter { it.atMs > nowMs - 6 * 3_600_000L && (it.rating == SkyEvent.Rating.GREAT || it.rating == SkyEvent.Rating.GOOD ||
        ((it.kind == SkyEvent.Kind.LUNAR_ECLIPSE || it.kind == SkyEvent.Kind.SOLAR_ECLIPSE) && it.lookAtMs != null)) }
        .take(2)
