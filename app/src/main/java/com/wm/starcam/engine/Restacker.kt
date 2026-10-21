package com.wm.starcam.engine

import com.wm.starcam.util.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Re-runs the native stack over a session whose .sraw cache was kept ("Keep raw cache" toggle),
 * with new StackConfig options — no camera involved. Reads our own sraw format (see sraw.h) and
 * feeds frames back through the normal addFrame path.
 */
object Restacker {

    data class SrawInfo(
        val width: Int, val height: Int, val cfa: Int, val whiteLevel: Int,
        val blackLevel: FloatArray, val wbGain: FloatArray, val ccm: FloatArray,
        val exposureNs: Long, val iso: Int, val timestampNs: Long,
    )

    /**
     * Blocking; call on a background dispatcher. Returns the engine result. [rejectIndices] are
     * capture indices to exclude again, e.g. the frames the gyro flagged as bumped during capture
     * (the live stack left them out, so a re-stack must too).
     */
    fun restack(
        sessionDir: File,
        cacheFiles: List<File>,
        options: StackConfig,
        modeOrdinal: Int,
        rejectIndices: Set<Int> = emptySet(),
        onProgress: (stage: String, percent: Int) -> Unit,
    ): EngineResult {
        // Callers should already exclude it, but never let the master dark stack as a light frame.
        val lights = cacheFiles.filter { it.name != MASTER_DARK_NAME }
        if (lights.isEmpty()) {
            return EngineResult.fromJson("""{"ok":false,"error":"no cached frames"}""")
        }
        val first = readHeader(lights.first())
            ?: return EngineResult.fromJson("""{"ok":false,"error":"unreadable sraw header"}""")

        val config = JSONObject().apply {
            put("sessionDir", sessionDir.absolutePath)
            put("mode", modeOrdinal)
            put("width", first.width)
            put("height", first.height)
            put("cfa", first.cfa)
            put("whiteLevel", first.whiteLevel)
            put("blackLevel", JSONArray(first.blackLevel.map { it.toDouble() }))
            put("keepCache", true)  // never delete during a re-stack
            put("livePreview", false)
        }.toString()
        val handle = NativeEngine.createSession(config)
        if (handle == 0L) return EngineResult.fromJson("""{"ok":false,"error":"engine init failed"}""")
        // Captured under a different name: inside the ProgressCallback object below, the plain name
        // `onProgress` would resolve to the object's own member and recurse into itself.
        val progressFn = onProgress
        try {
            val pixelBytes = first.width * first.height * 2
            val buffer = ByteBuffer.allocateDirect(pixelBytes)
            for ((i, f) in lights.withIndex()) {
                val info = readHeader(f) ?: continue
                if (info.width != first.width || info.height != first.height) continue
                if (!readPixels(f, pixelBytes, buffer)) {
                    // Truncated cache file (disk filled during capture). Feeding it would splice the
                    // previous frame's pixels into this frame's tail, poisoning the stack.
                    AppLog.e("restack: skipping truncated cache frame ${f.name}")
                    continue
                }
                buffer.rewind()
                // The capture index is in the file name (frame_0007.sraw); the list position
                // drifts from it as soon as one cache file is missing.
                val index = f.name.removePrefix("frame_").removeSuffix(".sraw").toIntOrNull() ?: i
                val metaJson = JSONObject().apply {
                    put("index", index)
                    // Use this file as it is: the engine no longer rewrites a re-fed frame.
                    put("srawPath", f.absolutePath)
                    put("exposureNs", info.exposureNs)
                    put("iso", info.iso)
                    put("timestampNs", info.timestampNs)
                    put("wbGains", JSONArray(info.wbGain.map { it.toDouble() }))
                    put("ccm", JSONArray(info.ccm.map { it.toDouble() }))
                    put("gyroRms", 0.0)
                    // Carry each frame's own (dynamic) black level through the re-feed; without
                    // this the engine rewrites every sraw with the static black and the per-frame
                    // values recorded at capture are permanently lost after the first re-stack.
                    put("blackLevel", JSONArray(info.blackLevel.map { it.toDouble() }))
                }.toString()
                NativeEngine.addFrame(handle, buffer, info.width, info.height, info.width * 2, metaJson)
                if (index in rejectIndices) {
                    NativeEngine.rejectFrame(handle, index, "gyro bump (recorded at capture)")
                }
                progressFn("reload", (i + 1) * 100 / lights.size)
            }
            addMasterDark(handle, sessionDir, first, pixelBytes, buffer)
            val resultJson = NativeEngine.finalizeStack(handle, options.toJson(),
                object : NativeEngine.ProgressCallback {
                    override fun onProgress(stage: String, percent: Int) {
                        progressFn(stage, percent)
                    }
                })
            return EngineResult.fromJson(resultJson)
        } catch (t: Throwable) {
            AppLog.e("restack failed", t)
            val msg = JSONObject().put("ok", false).put("error", t.message ?: "restack failed")
            return EngineResult.fromJson(msg.toString())
        } finally {
            NativeEngine.destroySession(handle)
        }
    }

    private const val HEADER_SIZE = 160
    private const val MASTER_DARK_NAME = "master_dark.sraw"

    /** Streams the packed pixel payload after the header into [buffer]. False if truncated. */
    private fun readPixels(f: File, pixelBytes: Int, buffer: ByteBuffer): Boolean {
        buffer.clear()
        var remaining = pixelBytes
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(HEADER_SIZE.toLong())
            val chunk = ByteArray(1 shl 20)
            while (remaining > 0) {
                val n = raf.read(chunk, 0, minOf(chunk.size, remaining))
                if (n <= 0) break
                buffer.put(chunk, 0, n)
                remaining -= n
            }
        }
        return remaining == 0
    }

    /**
     * Re-feeds cache/master_dark.sraw (written during the original capture) so re-stacks keep
     * their dark calibration. Absent, mismatched or unreadable → skip silently: the stack still
     * runs, just uncalibrated, exactly as it did before darks existed.
     */
    private fun addMasterDark(
        handle: Long, sessionDir: File, first: SrawInfo, pixelBytes: Int, buffer: ByteBuffer,
    ) {
        val f = File(sessionDir, "cache/$MASTER_DARK_NAME")
        if (!f.isFile) return
        val info = readHeader(f) ?: return
        if (info.width != first.width || info.height != first.height) return
        if (!readPixels(f, pixelBytes, buffer)) return
        buffer.rewind()
        val metaJson = JSONObject().apply {
            put("index", 0)
            put("exposureNs", 0L)
            put("iso", 0)
            put("timestampNs", 0L)
            put("wbGains", JSONArray(listOf(1.0, 1.0, 1.0, 1.0)))
            put("ccm", JSONArray(listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)))
            put("gyroRms", 0.0)
            // The black level it was shot at, which the session stored in its header.
            put("blackLevel", JSONArray(info.blackLevel.map { it.toDouble() }))
        }.toString()
        NativeEngine.addDarkFrame(handle, buffer, info.width, info.height, info.width * 2, metaJson)
    }

    /** Parses the fixed little-endian sraw header (layout documented in sraw.h). */
    fun readHeader(f: File): SrawInfo? {
        return try {
            val bytes = ByteArray(HEADER_SIZE)
            RandomAccessFile(f, "r").use { raf ->
                if (raf.read(bytes) != HEADER_SIZE) return null
            }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            if (String(magic) != "SRAW") return null
            val version = b.int
            if (version != 1) return null
            val width = b.int
            val height = b.int
            val cfa = b.int
            val white = b.int
            val black = FloatArray(4) { b.float }
            val wb = FloatArray(4) { b.float }
            val ccm = FloatArray(9) { b.float }
            val exposureNs = b.long
            val iso = b.int
            val timestampNs = b.long
            SrawInfo(width, height, cfa, white, black, wb, ccm, exposureNs, iso, timestampNs)
        } catch (t: Throwable) {
            AppLog.e("sraw header read failed: ${f.name}", t)
            null
        }
    }
}
