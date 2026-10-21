package com.wm.starcam.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The app's log, built for debugging a phone that is out in a field at night with no laptop
 * attached. Every line goes three places:
 *
 *  - logcat (tag "StarCam"), for when a USB cable *is* attached;
 *  - an in-memory ring, which the Diagnostics screen and the self-test show live;
 *  - a file under `Android/media/com.wm.starcam/StarCam/logs/`, one per process start. It
 *    survives the app being killed and the session failing, and is visible over USB and in the
 *    phone's own Files app — "what happened on Saturday night" is one file.
 *
 * While a capture session runs, its lines are also copied into that session's `meta/session.log`,
 * so every session folder carries its own complete story next to its frames.
 *
 * File writes happen on one background thread: a log call from the camera thread must never
 * block on storage. [flushBlocking] exists for the crash handler, because the lines right before
 * a crash are exactly the ones that matter.
 */
object AppLog {
    private const val TAG = "StarCam"

    /** Lines kept in memory: enough to cover a whole session's camera trace. */
    private const val RING_LINES = 4000

    /** Lines exposed through [lines] (the self-test log view). */
    private const val UI_LINES = 300

    /** A single log file never grows past this; the next one continues the story. */
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    private val stampFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val fileStampFmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    private class Line(val seq: Long, val text: String)

    private val ring = ArrayDeque<Line>()
    private var nextSeq = 0L

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    /** Bumped on every line, so a screen can refresh a [snapshot] without polling. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    // ---- file sink ----

    private sealed class Item {
        class Text(val s: String) : Item()
        class AttachSession(val file: File, val backlog: List<String>) : Item()
        object DetachSession : Item()
        class Flush(val done: CountDownLatch) : Item()
    }

    private val queue = LinkedBlockingQueue<Item>()
    @Volatile private var writerStarted = false

    /** Directory holding every log the app writes; null until [init]. */
    @Volatile var logDir: File? = null
        private set

    /** The file this process is writing right now; null until [init]. */
    @Volatile var currentFile: File? = null
        private set

    /**
     * Starts the file sink. Called once from Application.onCreate, before anything else runs.
     * Lines logged before this (there should be none) are written out first, in order.
     */
    @Synchronized
    fun init(context: Context) {
        if (writerStarted) return
        val base = runCatching { context.externalMediaDirs.firstOrNull() }.getOrNull()
            ?: context.filesDir
        val dir = File(base, "StarCam/logs").apply { mkdirs() }
        logDir = dir
        pruneOldLogs(dir)
        val stamp = LocalDateTime.now().format(fileStampFmt)
        currentFile = File(dir, "app_$stamp.log")
        synchronized(ring) {
            ring.forEach { queue.put(Item.Text(it.text)) }
            writerStarted = true
        }
        Thread({ writerLoop(dir, stamp) }, "StarCamLog").apply {
            isDaemon = true
            start()
        }
    }

    // ---- public logging API ----

    fun d(msg: String) = log('D', "app", msg, null)
    fun d(tag: String, msg: String) = log('D', tag, msg, null)
    fun i(msg: String) = log('I', "app", msg, null)
    fun i(tag: String, msg: String) = log('I', tag, msg, null)
    fun w(msg: String, tr: Throwable? = null) = log('W', "app", msg, tr)
    fun w(tag: String, msg: String, tr: Throwable? = null) = log('W', tag, msg, tr)
    fun e(msg: String, tr: Throwable? = null) = log('E', "app", msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log('E', tag, msg, tr)

    /** For the crash handler: everything logged so far is on disk when this returns. */
    fun flushBlocking(timeoutMs: Long = 2000L) {
        if (!writerStarted) return
        val latch = CountDownLatch(1)
        queue.put(Item.Flush(latch))
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    /** The most recent [max] lines, oldest first. */
    fun snapshot(max: Int = RING_LINES): List<String> = synchronized(ring) {
        ring.toList().takeLast(max).map { it.text }
    }

    // ---- per-session log ----

    /** Marks where a session starts, before its folder exists. Pass to [attachSessionLog]. */
    fun markSessionStart(): Long = synchronized(ring) { nextSeq }

    /**
     * Starts copying lines into [file], beginning with everything logged since [fromSeq] — the
     * stillness wait, probe and focus sweep all happen before the session folder is created.
     */
    fun attachSessionLog(file: File, fromSeq: Long) {
        // Snapshot and enqueue under the same lock as log(): a line logged in between would
        // otherwise land in both the backlog and the tee, or in neither.
        synchronized(ring) {
            val lost = ring.isNotEmpty() && ring.first().seq > fromSeq
            val kept = ring.filter { it.seq >= fromSeq }.map { it.text }
            val backlog = if (lost) {
                listOf("(earlier lines of this session dropped from memory — see ${currentFile?.name})") + kept
            } else kept
            if (writerStarted) queue.put(Item.AttachSession(file, backlog))
        }
    }

    fun detachSessionLog() {
        if (writerStarted) queue.put(Item.DetachSession)
    }

    // ---- internals ----

    private fun log(level: Char, tag: String, msg: String, tr: Throwable?) {
        when (level) {
            'E' -> Log.e(TAG, "[$tag] $msg", tr)
            'W' -> Log.w(TAG, "[$tag] $msg", tr)
            'I' -> Log.i(TAG, "[$tag] $msg", tr)
            else -> Log.d(TAG, "[$tag] $msg", tr)
        }
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(System.currentTimeMillis()), ZoneId.systemDefault())
        val body = if (tr == null) msg else "$msg\n${tr.stackTraceToString().trimEnd()}"
        val text = "${now.format(stampFmt)} $level/$tag [${Thread.currentThread().name}] $body"
        val uiText = if (tr == null) "$level/$tag $msg" else "$level/$tag $msg — ${tr.javaClass.simpleName}: ${tr.message}"
        synchronized(ring) {
            ring.addLast(Line(nextSeq++, text))
            while (ring.size > RING_LINES) ring.removeFirst()
            _lines.value = (_lines.value + uiText).takeLast(UI_LINES)
            _version.value = nextSeq
            // Same lock as attachSessionLog, so the file order matches the ring order. The queue
            // is unbounded: put() never blocks while holding it.
            if (writerStarted) queue.put(Item.Text(text))
        }
    }

    private fun writerLoop(dir: File, stamp: String) {
        var part = 1
        var file = File(dir, "app_$stamp.log")
        var out: BufferedWriter? = null
        var written = 0L
        var session: BufferedWriter? = null

        fun open() {
            out = runCatching {
                BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
            }.onFailure { Log.e(TAG, "log file open failed: $file", it) }.getOrNull()
            written = file.length()
        }
        open()

        while (true) {
            val item = try { queue.take() } catch (_: InterruptedException) { continue }
            try {
                when (item) {
                    is Item.Text -> {
                        out?.apply { write(item.s); write("\n") }
                        written += item.s.length + 1
                        session?.apply { write(item.s); write("\n") }
                        if (written > MAX_FILE_BYTES) {
                            runCatching { out?.close() }
                            part++
                            file = File(dir, "app_${stamp}_$part.log")
                            currentFile = file
                            open()
                        }
                    }
                    is Item.AttachSession -> {
                        runCatching { session?.close() }
                        session = runCatching {
                            item.file.parentFile?.mkdirs()
                            BufferedWriter(OutputStreamWriter(FileOutputStream(item.file, true), Charsets.UTF_8))
                        }.onFailure { Log.e(TAG, "session log open failed: ${item.file}", it) }.getOrNull()
                        session?.let { s -> item.backlog.forEach { s.write(it); s.write("\n") } }
                    }
                    Item.DetachSession -> {
                        runCatching { session?.close() }
                        session = null
                    }
                    is Item.Flush -> {
                        out?.flush()
                        session?.flush()
                        item.done.countDown()
                    }
                }
                // Flush whenever we catch up: a burst is written in one go, and nothing sits in a
                // buffer while the app is idle — or about to be killed by the system.
                if (queue.isEmpty()) {
                    out?.flush()
                    session?.flush()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "log writer error", t)
            }
        }
    }

    /** Keeps the newest 40 app logs and at most ~120 MB of them. */
    private fun pruneOldLogs(dir: File) {
        runCatching {
            val files = dir.listFiles { f -> f.isFile && f.name.startsWith("app_") }
                ?.sortedByDescending { it.lastModified() } ?: return
            var total = 0L
            for ((i, f) in files.withIndex()) {
                total += f.length()
                if (i >= 40 || total > 120L * 1024 * 1024) f.delete()
            }
        }
    }
}
