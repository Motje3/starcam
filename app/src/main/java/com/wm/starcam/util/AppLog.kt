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
import java.util.concurrent.atomic.AtomicInteger

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

    /** All app logs together stay under this, also while the process runs (see [pruneOldLogs]). */
    private const val MAX_TOTAL_BYTES = 120L * 1024 * 1024
    private const val MAX_FILES = 40

    /** One session's own log stops here; the rest of the story is in the app log. */
    private const val MAX_SESSION_LOG_BYTES = 32L * 1024 * 1024

    /** Below this much free storage, log files stop growing: the space is for the frames. */
    private const val MIN_FREE_BYTES = 200L * 1024 * 1024

    /**
     * Lines waiting for the writer. A stalled disk or a runaway error loop must not turn the queue
     * into a memory leak: past this, lines are counted and dropped (they stay in logcat and the
     * in-memory ring) and the file says how many went missing.
     */
    private const val MAX_QUEUED_LINES = 20_000

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
    private val queuedLines = AtomicInteger(0)
    private val droppedLines = AtomicInteger(0)
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
            ring.forEach {
                queuedLines.incrementAndGet()
                queue.put(Item.Text(it.text))
            }
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
            // itself is unbounded, so put() never blocks while holding the lock; text lines are
            // bounded by count instead (see MAX_QUEUED_LINES).
            if (writerStarted) {
                if (queuedLines.incrementAndGet() <= MAX_QUEUED_LINES) {
                    queue.put(Item.Text(text))
                } else {
                    queuedLines.decrementAndGet()
                    droppedLines.incrementAndGet()
                }
            }
        }
    }

    private fun writerLoop(dir: File, stamp: String) {
        var part = 1
        var file = File(dir, "app_$stamp.log")
        var out: BufferedWriter? = null
        var written = 0L
        var session: BufferedWriter? = null
        var sessionWritten = 0L
        var sinceSpaceCheck = 0L
        // False while storage is almost full: the lines still reach logcat and the ring.
        var filesOn = true

        fun open() {
            out = runCatching {
                BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
            }.onFailure { Log.e(TAG, "log file open failed: $file", it) }.getOrNull()
            written = file.length()
        }
        open()

        fun writeLine(s: String) {
            if (!filesOn) return
            out?.apply { write(s); write("\n") }
            written += s.length + 1
            session?.let { w ->
                if (sessionWritten < MAX_SESSION_LOG_BYTES) {
                    w.write(s); w.write("\n")
                    sessionWritten += s.length + 1
                    if (sessionWritten >= MAX_SESSION_LOG_BYTES) {
                        w.write("(session log stops here at ${MAX_SESSION_LOG_BYTES shr 20} MB; the rest is in ${file.name})\n")
                    }
                }
            }
        }

        while (true) {
            val item = try { queue.take() } catch (_: InterruptedException) { continue }
            try {
                when (item) {
                    is Item.Text -> {
                        queuedLines.decrementAndGet()
                        val dropped = droppedLines.getAndSet(0)
                        if (dropped > 0) writeLine("($dropped log lines dropped: the log writer fell behind)")
                        writeLine(item.s)
                        sinceSpaceCheck += item.s.length + 1
                        if (sinceSpaceCheck > 1L shl 20) {
                            sinceSpaceCheck = 0L
                            val free = runCatching { dir.usableSpace }.getOrDefault(Long.MAX_VALUE)
                            if (filesOn && free < MIN_FREE_BYTES) {
                                writeLine("(log files paused: only ${free shr 20} MB of storage left)")
                                out?.flush()
                                session?.flush()
                                filesOn = false
                            } else if (!filesOn && free > MIN_FREE_BYTES * 2) {
                                filesOn = true
                                writeLine("(log files resumed: ${free shr 20} MB of storage free)")
                            }
                        }
                        if (written > MAX_FILE_BYTES) {
                            runCatching { out?.close() }
                            part++
                            file = File(dir, "app_${stamp}_$part.log")
                            currentFile = file
                            open()
                            // Limits held at startup only, so one long run could rotate forever.
                            pruneOldLogs(dir)
                        }
                    }
                    is Item.AttachSession -> {
                        runCatching { session?.close() }
                        sessionWritten = 0L
                        session = runCatching {
                            item.file.parentFile?.mkdirs()
                            BufferedWriter(OutputStreamWriter(FileOutputStream(item.file, true), Charsets.UTF_8))
                        }.onFailure { Log.e(TAG, "session log open failed: ${item.file}", it) }.getOrNull()
                        session?.let { s ->
                            item.backlog.forEach {
                                s.write(it); s.write("\n")
                                sessionWritten += it.length + 1
                            }
                        }
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

    /**
     * Keeps the newest [MAX_FILES] app logs and at most [MAX_TOTAL_BYTES] of them. The file being
     * written is the newest, so it is always kept.
     */
    private fun pruneOldLogs(dir: File) {
        runCatching {
            val files = dir.listFiles { f -> f.isFile && f.name.startsWith("app_") }
                ?.sortedByDescending { it.lastModified() } ?: return
            var total = 0L
            for ((i, f) in files.withIndex()) {
                total += f.length()
                if (i > 0 && (i >= MAX_FILES || total > MAX_TOTAL_BYTES)) f.delete()
            }
        }
    }
}
