package com.wm.starcam.alerts

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wm.starcam.MainActivity
import com.wm.starcam.R
import com.wm.starcam.darksky.DarkSkyData
import com.wm.starcam.darksky.SkyQuality
import com.wm.starcam.sensors.SkyPlaceStore
import com.wm.starcam.sky.NightPlanner
import com.wm.starcam.sky.SkyCalendar
import com.wm.starcam.sky.SkyEvent
import com.wm.starcam.ui.planZone
import com.wm.starcam.ui.tonightDate
import com.wm.starcam.util.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Night alerts: in the afternoon, a notification when tonight is worth going out for - a Milky
 * Way window, a meteor shower's peak, an eclipse, a close pairing - worked out on the phone for
 * the place in the planner (no internet, nothing leaves the phone). And reminders for single
 * events picked in the sky calendar. Inexact alarms: a few minutes either way, no special
 * permission, kind to the battery.
 */
object NightAlerts {
    private const val PREFS = "night_alerts"
    private const val KEY_ON = "on"
    private const val KEY_REMINDERS = "reminders"
    private const val CHANNEL = "night_alerts"
    const val ACTION_DAILY = "com.wm.starcam.NIGHT_ALERT_DAILY"
    const val ACTION_REMINDER = "com.wm.starcam.NIGHT_ALERT_REMINDER"
    private const val ALERT_HOUR = 15

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, on).apply()
        if (on) scheduleDaily(context) else cancelDaily(context)
        AppLog.i("alerts", "night alerts ${if (on) "on" else "off"}")
    }

    fun canNotify(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun alarms(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun dailyIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, NightAlertReceiver::class.java).setAction(ACTION_DAILY),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The next afternoon alert, at [ALERT_HOUR] local time give or take a quarter of an hour. */
    fun scheduleDaily(context: Context) {
        val now = ZonedDateTime.now()
        var at = now.withHour(ALERT_HOUR).withMinute(0).withSecond(0).withNano(0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        alarms(context)?.setWindow(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), 30 * 60_000L, dailyIntent(context))
    }

    private fun cancelDaily(context: Context) {
        alarms(context)?.cancel(dailyIntent(context))
    }

    // ------------------------------------------------------------------------- reminders

    /** Ids of the events with a reminder set. */
    fun reminders(context: Context): Set<String> = readReminders(context).map { it.optString("id") }.toSet()

    private fun readReminders(context: Context): List<JSONObject> = runCatching {
        val a = JSONArray(prefs(context).getString(KEY_REMINDERS, "[]"))
        List(a.length()) { a.getJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun writeReminders(context: Context, list: List<JSONObject>) {
        prefs(context).edit().putString(KEY_REMINDERS, JSONArray(list).toString()).apply()
    }

    private fun reminderIntent(context: Context, r: JSONObject): PendingIntent = PendingIntent.getBroadcast(
        context, r.optString("id").hashCode(),
        Intent(context, NightAlertReceiver::class.java).setAction(ACTION_REMINDER)
            .putExtra("id", r.optString("id")).putExtra("title", r.optString("title")).putExtra("text", r.optString("text")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * A reminder for [e]: at 17:00 on the day its night begins (an early-morning sight belongs
     * to the evening before), or straight away if that has passed.
     */
    fun addReminder(context: Context, e: SkyEvent, zone: java.time.ZoneId) {
        val look = e.lookAtMs ?: e.atMs
        val local = Instant.ofEpochMilli(look).atZone(zone)
        val evening = if (local.hour < 12) local.toLocalDate().minusDays(1) else local.toLocalDate()
        val at = maxOf(evening.atTime(17, 0).atZone(zone).toInstant().toEpochMilli(), System.currentTimeMillis() + 60_000L)
        val r = JSONObject().apply {
            put("id", e.id)
            put("at", at)
            put("title", e.title)
            put("text", e.summary)
        }
        writeReminders(context, readReminders(context).filter { it.optString("id") != e.id } + r)
        alarms(context)?.setWindow(AlarmManager.RTC_WAKEUP, at, 15 * 60_000L, reminderIntent(context, r))
        AppLog.i("alerts", "reminder for ${e.title} at ${Instant.ofEpochMilli(at)}")
    }

    fun removeReminder(context: Context, id: String) {
        val all = readReminders(context)
        all.firstOrNull { it.optString("id") == id }?.let { alarms(context)?.cancel(reminderIntent(context, it)) }
        writeReminders(context, all.filter { it.optString("id") != id })
    }

    /** After a reboot or an update: the alarms are gone and come back from what is stored. */
    fun restore(context: Context) {
        if (enabled(context)) scheduleDaily(context)
        val now = System.currentTimeMillis()
        val keep = readReminders(context).filter { it.optLong("at") > now - 3_600_000L }
        writeReminders(context, keep)
        for (r in keep) {
            alarms(context)?.setWindow(AlarmManager.RTC_WAKEUP, maxOf(r.optLong("at"), now + 60_000L), 15 * 60_000L, reminderIntent(context, r))
        }
    }

    // --------------------------------------------------------------------------- tonight

    /** What makes tonight worth it, a line each; empty when nothing does. */
    fun tonight(context: Context, nowMs: Long = System.currentTimeMillis()): List<String> {
        val place = SkyPlaceStore(context).load() ?: return emptyList()
        val zone = planZone(place.loc)
        val sqm = runCatching { SkyQuality.sqm(DarkSkyData.glow(context).alr(place.loc.latDeg, place.loc.lonDeg)) }.getOrNull()
        val lines = ArrayList<String>()
        val plan = runCatching { NightPlanner.plan(tonightDate(zone), zone, place.loc) }.getOrNull()
        plan?.bestCoreWindow?.takeIf { it.durationMs >= 45 * 60_000L && it.endMs > nowMs }?.let { w ->
            val peak = com.wm.starcam.sky.SkyMath.galacticCenter(w.startMs + w.durationMs / 2, place.loc)
            lines += "Milky Way ${SkyCalendar.hm(w.startMs, zone)}–${SkyCalendar.hm(w.endMs, zone)}, the core " +
                "${peak.altDeg.roundToInt()}° up in the ${SkyCalendar.compass(peak.azDeg)}, no Moon"
        }
        val until = nowMs + 20 * 3_600_000L
        for (e in SkyCalendar.events(nowMs - 6 * 3_600_000L, 2, place.loc, zone, sqm)) {
            val look = e.lookAtMs ?: continue
            if (look !in nowMs..until) continue
            val worth = when (e.kind) {
                SkyEvent.Kind.METEORS, SkyEvent.Kind.CONJUNCTION, SkyEvent.Kind.PLANET ->
                    e.rating == SkyEvent.Rating.GREAT || e.rating == SkyEvent.Rating.GOOD
                SkyEvent.Kind.LUNAR_ECLIPSE, SkyEvent.Kind.SOLAR_ECLIPSE -> e.rating != SkyEvent.Rating.INFO
                else -> false
            }
            if (worth) lines += "${e.title}: ${e.summary}"
        }
        return lines
    }

    fun post(context: Context, id: Int, title: String, text: String) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Night alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Good nights for the Milky Way, meteor showers, eclipses and reminders you set."
                }
            )
        }
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_star_vector)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { manager.notify(id, n) }
    }
}

/** The afternoon alert, the reminders, and the alarms' return after a reboot or an update. */
class NightAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            NightAlerts.ACTION_DAILY -> {
                // The sky maths takes a moment: off the main thread, the broadcast held open.
                val pending = goAsync()
                thread(name = "night-alert") {
                    try {
                        if (NightAlerts.enabled(app)) {
                            val lines = NightAlerts.tonight(app)
                            AppLog.i("alerts", "afternoon check: ${if (lines.isEmpty()) "nothing tonight" else lines.joinToString(" | ")}")
                            if (lines.isNotEmpty()) {
                                NightAlerts.post(app, 100, "Tonight: " + lines.first().substringBefore(':').take(60),
                                    lines.joinToString("\n"))
                            }
                            NightAlerts.scheduleDaily(app)
                        }
                    } catch (t: Throwable) {
                        AppLog.e("alerts", "afternoon check failed", t)
                    } finally {
                        pending.finish()
                    }
                }
            }
            NightAlerts.ACTION_REMINDER -> {
                val id = intent.getStringExtra("id") ?: return
                NightAlerts.post(app, 200 + (id.hashCode() and 0xffff), intent.getStringExtra("title") ?: "StarCam",
                    intent.getStringExtra("text") ?: "")
                NightAlerts.removeReminder(app, id)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> NightAlerts.restore(app)
        }
    }
}
