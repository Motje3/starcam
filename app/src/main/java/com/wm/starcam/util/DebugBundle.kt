package com.wm.starcam.util

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.wm.starcam.camera.CapabilityProbe
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One zip with everything needed to debug the app from home: the app logs and logcat mirrors,
 * crash files and exit traces, the metadata and log of the most recent sessions (never their
 * frames — those are hundreds of MB), the camera capability report and the current settings.
 * Built on the phone and handed to the share sheet, so it can be sent from a field with no
 * computer in sight.
 */
object DebugBundle {
    private const val TAG = "bundle"

    /** Newest sessions included; older ones are rarely what a bug report is about. */
    private const val SESSIONS = 6

    /** Caps the logs part of the zip; logcat mirrors from a long night can be large. */
    private const val MAX_LOG_BYTES = 80L * 1024 * 1024

    fun build(context: Context, sessionsRoot: File, settingsText: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(context.cacheDir, "share").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }  // only ever keep the latest bundle
        }
        val zip = File(outDir, "starcam_debug_$stamp.zip")
        AppLog.i(TAG, "building debug bundle ${zip.name}")
        AppLog.flushBlocking()
        val contents = ArrayList<String>()

        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            fun addText(name: String, text: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
                contents += name
            }
            fun addFile(name: String, f: File) {
                runCatching {
                    z.putNextEntry(ZipEntry(name))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                    contents += "$name (${f.length() / 1024} KB)"
                }.onFailure { AppLog.w(TAG, "skipped $name", it) }
            }

            // Logs, newest first, within the size budget. The current app log always goes in.
            val logDir = AppLog.logDir
            val logs = logDir?.listFiles { f -> f.isFile }?.sortedByDescending { it.lastModified() }
                ?: emptyList()
            var budget = MAX_LOG_BYTES
            for (f in logs) {
                if (f != AppLog.currentFile && f.length() > budget) continue
                budget -= f.length()
                addFile("logs/${f.name}", f)
            }

            // Recent sessions: everything except the image data.
            val sessions = sessionsRoot.listFiles { f -> f.isDirectory }
                ?.sortedByDescending { it.name }?.take(SESSIONS) ?: emptyList()
            for (dir in sessions) {
                val base = "sessions/${dir.name}"
                File(dir, "meta").listFiles()?.forEach { addFile("$base/meta/${it.name}", it) }
                File(dir, "out/stack_report.json").takeIf { it.isFile }
                    ?.let { addFile("$base/out/stack_report.json", it) }
                addText("$base/files.txt", listing(dir))
            }

            addText("probe.txt", runCatching { CapabilityProbe.buildReport(context) }
                .getOrElse { "probe failed: ${it.stackTraceToString()}" })
            addText("settings.txt", settingsText)
            addText("README.txt", buildString {
                appendLine("StarCam debug bundle, $stamp")
                appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine(runCatching {
                    val p = context.packageManager.getPackageInfo(context.packageName, 0)
                    "app ${p.versionName} (${p.longVersionCode})"
                }.getOrDefault("app ?"))
                appendLine("condition: ${Diagnostics.snapshot(context)}")
                appendLine()
                appendLine("logs/app_*.log      the app's own log, one file per app start")
                appendLine("logs/logcat_*.txt   everything this app wrote to logcat (camera framework, native engine)")
                appendLine("logs/crash_*.txt    Java crashes; logs/exit_*  native crashes / ANRs of earlier runs")
                appendLine("sessions/*/meta     session.json (every frame), session.log (that session's log)")
                appendLine()
                contents.forEach { appendLine(it) }
            })
        }
        AppLog.i(TAG, "debug bundle ready: ${zip.length() / 1024} KB, ${contents.size} entries")
        return zip
    }

    /** Opens the share sheet for [zip]. */
    fun share(context: Context, zip: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "StarCam debug bundle ${zip.nameWithoutExtension.removePrefix("starcam_debug_")}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Send StarCam debug bundle")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Which files a session folder holds and how big they are — shows how far it got. */
    private fun listing(dir: File): String = buildString {
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
            appendLine("${f.relativeTo(dir).path}  ${f.length()}")
        }
    }
}
