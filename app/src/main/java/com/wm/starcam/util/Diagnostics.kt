package com.wm.starcam.util

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Everything that turns "it failed in the field" into something diagnosable at home:
 *
 *  - a crash handler that gets the stack trace onto disk before the process dies;
 *  - why the *previous* run ended (native crash, ANR, killed for memory, force-stopped...),
 *    from Android's own exit records — including the native tombstone when the C++ engine
 *    crashed, which nothing inside the dying process could have written;
 *  - a mirror of this app's logcat into a file, which interleaves our lines with the camera
 *    framework's and the native engine's;
 *  - [snapshot]: one line of the phone's condition (battery, heat, memory, storage, screen,
 *    foreground state) for the log at every interesting moment.
 */
object Diagnostics {
    private const val TAG = "diag"

    // java.time formatters, not SimpleDateFormat: the crash handler runs on whichever thread
    // crashed, concurrently with the diag thread, and SimpleDateFormat is not thread-safe.
    private val fileFmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
    private val humanFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun stampOf(ms: Long, fmt: DateTimeFormatter): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(fmt)

    private fun fileStamp(ms: Long = System.currentTimeMillis()) = stampOf(ms, fileFmt)
    private fun humanStamp(ms: Long = System.currentTimeMillis()) = stampOf(ms, humanFmt)

    // ------------------------------------------------------------------ startup

    /** Called once from Application.onCreate, right after [AppLog.init]. */
    fun start(app: Application) {
        installCrashHandler(app)
        logStartup(app)
        registerSystemEventLogging(app)
        // Exit records are a binder call and logcat is a child process: neither belongs on the
        // main thread during app start.
        Thread({
            recordPastExits(app)
            runLogcatMirror(app)
        }, "StarCamDiag").apply {
            isDaemon = true
            start()
        }
    }

    private fun logStartup(context: Context) {
        val pkg = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        AppLog.i(TAG, "==== StarCam ${pkg?.versionName ?: "?"} (${pkg?.longVersionCode ?: "?"}) " +
            "started, pid ${android.os.Process.myPid()} ====")
        AppLog.i(TAG, "installed ${pkg?.lastUpdateTime?.let { humanStamp(it) } ?: "?"} · " +
            "device ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT}, patch ${Build.VERSION.SECURITY_PATCH}) · build ${Build.DISPLAY}")
        AppLog.i(TAG, "logs: ${AppLog.logDir?.absolutePath}")
        AppLog.i(TAG, "condition: ${snapshot(context)}")
    }

    // ------------------------------------------------------------------ crashes

    private fun installCrashHandler(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                AppLog.e("crash", "FATAL uncaught exception on thread '${thread.name}'", throwable)
                runCatching { AppLog.e("crash", "condition at crash: ${snapshot(app)}") }
                AppLog.flushBlocking(2000)
                // Its own file too: a crash should be findable without reading a whole log.
                AppLog.logDir?.let { dir ->
                    File(dir, "crash_${fileStamp()}.txt").writeText(
                        "StarCam crash on thread '${thread.name}' at ${humanStamp()}\n" +
                            "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n\n" +
                            throwable.stackTraceToString()
                    )
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Why previous runs of the app ended, from the system's records — the only way to learn
     * about a native crash in the stacking engine or the system killing us for memory, since
     * the dying process cannot report either itself.
     */
    private fun recordPastExits(context: Context) {
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val prefs = context.getSharedPreferences("starcam_diag", Context.MODE_PRIVATE)
            val lastSeen = prefs.getLong("lastExitTs", 0L)
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 16)
            val fresh = exits.filter { it.timestamp > lastSeen }.sortedBy { it.timestamp }
            if (fresh.isEmpty()) {
                AppLog.i("exit", "no new process exits since the last start")
                return
            }
            for (x in fresh) {
                val line = "previous run (pid ${x.pid}) ended ${humanStamp(x.timestamp)}: " +
                    "${exitReasonName(x.reason)} · status ${x.status} · was ${importanceName(x.importance)} · " +
                    "pss ${x.pss / 1024}MB rss ${x.rss / 1024}MB" +
                    (x.description?.let { " · \"$it\"" } ?: "")
                if (x.reason in ABNORMAL_EXITS) AppLog.w("exit", line) else AppLog.i("exit", line)
                if (x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    x.reason == ApplicationExitInfo.REASON_ANR
                ) saveExitTrace(x)
            }
            prefs.edit().putLong("lastExitTs", fresh.maxOf { it.timestamp }).apply()
        }.onFailure { AppLog.w("exit", "could not read the exit history", it) }
    }

    private val ABNORMAL_EXITS = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED,
    )

    /**
     * Saves the trace attached to a native crash (a protobuf tombstone) or an ANR (thread dump).
     * The tombstone is binary, so a readable copy of its strings — function names, the abort
     * message, the faulting library — is written next to it.
     */
    private fun saveExitTrace(x: ApplicationExitInfo) {
        val dir = AppLog.logDir ?: return
        runCatching {
            val bytes = x.traceInputStream?.use { it.readBytes() } ?: return
            val base = "exit_${fileStamp(x.timestamp)}_${exitReasonName(x.reason).lowercase()}"
            File(dir, "$base.trace").writeBytes(bytes)
            if (x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                File(dir, "$base.txt").writeText(printableStrings(bytes).joinToString("\n"))
            }
            AppLog.w("exit", "saved the ${bytes.size}-byte trace of that exit as $base.*")
        }.onFailure { AppLog.w("exit", "could not save the exit trace", it) }
    }

    private fun printableStrings(bytes: ByteArray, minLen: Int = 5): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) sb.append(c.toChar())
            else {
                if (sb.length >= minLen) out += sb.toString()
                sb.setLength(0)
            }
        }
        if (sb.length >= minLen) out += sb.toString()
        return out
    }

    // ------------------------------------------------------------------ logcat mirror

    /**
     * Copies this app's logcat into logs/logcat_*.txt for as long as the process lives. An app
     * may read its own log lines without any permission, and those include what our tag alone
     * misses: the camera framework's client-side errors, ImageReader buffer warnings, the native
     * engine (tag StarCamEngine), ART, and the fatal-signal line of a native crash. Started
     * without -d, logcat first replays whatever of the *previous* run is still in the buffer.
     */
    private fun runLogcatMirror(context: Context) {
        val dir = AppLog.logDir ?: return
        pruneLogcat(dir)
        val stamp = fileStamp()
        var proc: Process? = null
        var out: BufferedWriter? = null
        try {
            proc = ProcessBuilder("logcat", "-v", "threadtime", "-v", "year")
                .redirectErrorStream(true)
                .start()
            var part = 1
            var file = File(dir, "logcat_$stamp.txt")
            AppLog.i(TAG, "logcat mirror -> ${file.name}")
            out = openWriter(file)
            var written = 0L
            var lastFlush = SystemClock.elapsedRealtime()
            proc.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    // A write error (disk full, storage hiccup) moves on to a fresh file instead
                    // of ending the mirror for the rest of the night.
                    try {
                        out?.write(line)
                        out?.write("\n")
                    } catch (e: java.io.IOException) {
                        runCatching { out?.close() }
                        part++
                        file = File(dir, "logcat_${stamp}_$part.txt")
                        out = runCatching { openWriter(file) }.getOrNull()
                        written = 0L
                        continue
                    }
                    written += line.length + 1
                    val now = SystemClock.elapsedRealtime()
                    if (!reader.ready() || now - lastFlush > 1000L) {
                        runCatching { out?.flush() }
                        lastFlush = now
                    }
                    if (written > LOGCAT_FILE_BYTES) {
                        runCatching { out?.close() }
                        // The frames need the space more than the mirror does.
                        val free = runCatching { dir.usableSpace }.getOrDefault(Long.MAX_VALUE)
                        if (free < LOGCAT_MIN_FREE_BYTES) {
                            AppLog.w(TAG, "logcat mirror stopped: only ${free shr 20} MB of storage left")
                            out = null
                            proc.destroy()
                            break
                        }
                        part++
                        file = File(dir, "logcat_${stamp}_$part.txt")
                        out = runCatching { openWriter(file) }.getOrNull()
                        written = 0L
                        // Limits held at startup only, so one long run could rotate forever.
                        pruneLogcat(dir)
                    }
                }
            }
            AppLog.w(TAG, "logcat mirror ended (logcat exited ${proc.waitFor()})")
        } catch (t: Throwable) {
            AppLog.w(TAG, "logcat mirror unavailable — only the app log will be kept", t)
        } finally {
            runCatching { out?.close() }
            proc?.destroy()
        }
    }

    private const val LOGCAT_FILE_BYTES = 16L * 1024 * 1024
    private const val LOGCAT_MIN_FREE_BYTES = 300L * 1024 * 1024

    private fun openWriter(f: File): BufferedWriter =
        BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8))

    private fun pruneLogcat(dir: File) {
        runCatching {
            val files = dir.listFiles { f -> f.isFile && f.name.startsWith("logcat_") }
                ?.sortedByDescending { it.lastModified() } ?: return
            var total = 0L
            for ((i, f) in files.withIndex()) {
                total += f.length()
                // The newest is the one being written.
                if (i > 0 && (i >= 16 || total > 160L * 1024 * 1024)) f.delete()
            }
        }
    }

    // ------------------------------------------------------------------ system events

    /**
     * Logs the events that can change what the camera does under us: the activity going to the
     * background, the screen turning off, memory pressure, power state. When a capture fails, the
     * line just before it in the log is often one of these.
     */
    private fun registerSystemEventLogging(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private fun log(a: Activity, what: String) =
                AppLog.i("life", "${a.javaClass.simpleName} $what")
            override fun onActivityCreated(a: Activity, b: Bundle?) =
                log(a, if (b == null) "created" else "re-created (restored state)")
            override fun onActivityStarted(a: Activity) = log(a, "started (visible)")
            override fun onActivityResumed(a: Activity) = log(a, "resumed (foreground)")
            override fun onActivityPaused(a: Activity) = log(a, "paused")
            override fun onActivityStopped(a: Activity) =
                log(a, "stopped (no longer visible${if (a.isChangingConfigurations) ", config change" else ""})")
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) =
                log(a, "destroyed${if (a.isFinishing) " (finishing)" else ""}")
        })

        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                AppLog.w("life", "system asked to trim memory: ${trimLevelName(level)} — ${snapshot(app)}")
            }
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                AppLog.e("life", "LOW MEMORY — ${snapshot(app)}")
            }
        })

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val what = i.action?.substringAfterLast('.') ?: "?"
                val extra = when (i.action) {
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED ->
                        " (saver ${if (c.getSystemService(PowerManager::class.java).isPowerSaveMode) "ON" else "off"})"
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED ->
                        " (doze ${if (c.getSystemService(PowerManager::class.java).isDeviceIdleMode) "ON" else "off"})"
                    else -> ""
                }
                AppLog.i("life", "system: $what$extra — ${snapshot(c)}")
            }
        }
        runCatching {
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { AppLog.w(TAG, "could not register for system events", it) }
    }

    // ------------------------------------------------------------------ battery

    /** Battery temperature in °C - the only temperature an app can read - or null if unknown. */
    fun batteryTempC(context: Context): Float? = runCatching {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (t == Int.MIN_VALUE) null else t / 10f
    }.getOrNull()

    /** Whether the phone is on a charger or power bank. */
    fun isPlugged(context: Context): Boolean = runCatching {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }.getOrDefault(false)

    // ------------------------------------------------------------------ snapshot

    /**
     * One line of the phone's condition, e.g.
     * `batt 81% 24.5°C · thermal none (headroom 0.42) · ram 5.1/11.6G free · heap 41/256M ·
     * native 312M · disk 88.4G free · screen on · fg-service`.
     */
    fun snapshot(context: Context): String = buildString {
        runCatching {
            val bm = context.getSystemService(BatteryManager::class.java)
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            append("batt $pct%")
            if (temp != null && temp != Int.MIN_VALUE) append(" %.1f°C".format(temp / 10f))
            if (plugged) append(" charging")
        }
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java)
            append(" · thermal ${thermalName(pm.currentThermalStatus)}")
            val headroom = pm.getThermalHeadroom(0)
            if (!headroom.isNaN()) append(" (headroom %.2f)".format(headroom))
            append(" · screen ${if (pm.isInteractive) "on" else "OFF"}")
            if (pm.isPowerSaveMode) append(" · power saver")
            if (pm.isDeviceIdleMode) append(" · DOZE")
        }
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            append(" · ram %.1f/%.1fG free".format(mi.availMem / 1e9, mi.totalMem / 1e9))
            if (mi.lowMemory) append(" LOW")
            val rt = Runtime.getRuntime()
            append(" · heap ${(rt.totalMemory() - rt.freeMemory()) shr 20}/${rt.maxMemory() shr 20}M")
            append(" · native ${Debug.getNativeHeapAllocatedSize() shr 20}M")
            val me = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(me)
            append(" · ${importanceName(me.importance)}")
        }
        runCatching {
            val base = context.externalMediaDirs.firstOrNull() ?: context.filesDir
            append(" · disk %.1fG free".format(base.usableSpace / 1e9))
        }
    }

    // ------------------------------------------------------------------ names

    fun thermalName(s: Int): String = when (s) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "?($s)"
    }

    private fun importanceName(i: Int): String = when {
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "fg-service"
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "background service"
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED (background)"
        else -> "gone($i)"
    }

    private fun trimLevelName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        else -> "level $level"
    }

    private fun exitReasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNKNOWN($r)"
    }
}
