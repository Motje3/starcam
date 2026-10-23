package com.wm.starcam.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
import com.wm.starcam.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
            subtitle = place?.let { "The next 12 months · " + placeLabel(it.loc) } ?: "What the sky does, and when",
            onBack = onBack,
        )
        val p = place
        if (p == null) {
            Column(Modifier.padding(ScreenMargin)) {
                ScCard(Modifier.fillMaxWidth()) {
                    Text("Where are you?", style = ScType.headline, color = c.text)
                    Text(
                        "Meteor showers, eclipses and the Milky Way's nights depend on where you stand. Your " +
                            "location stays on this phone: StarCam has no internet access.",
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
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = ScreenMargin, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("alerts") { AlertsCard(alertsOn, ::setAlerts) }
            item("filters") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CalendarFilter.entries.forEach { f ->
                        ScChip(f.label, filter == f, { filter = f })
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
                    "Worked out on this phone for your place: the Sun, Moon and planets from Meeus' and JPL's " +
                        "methods, meteor showers from the IMO's list. Clouds are the one thing it cannot know.",
                    style = ScType.caption, color = c.text3, modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun AlertsCard(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Sc.colors
    ScCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ScIcons.Bell, null, Modifier.size(20.dp), tint = c.accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Night alerts", style = ScType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Text(
                    "A notification in the afternoon when tonight is worth it: a Milky Way window, a meteor peak, " +
                        "an eclipse, a close pairing.",
                    style = ScType.caption, color = c.text2,
                )
            }
            ScSwitch(on, onChange)
        }
    }
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
