package com.wm.starcam.session

import android.content.Context
import android.media.MediaScannerConnection
import android.util.AtomicFile
import android.util.Size
import com.wm.starcam.camera.ShadingMapData
import com.wm.starcam.engine.FrameStats
import com.wm.starcam.util.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Folder handles for one capture session (PLAN.md §9). */
data class SessionPaths(
    val dir: File,
    val lights: File,
    val darks: File,
    val cache: File,
    val out: File,
    val meta: File,
) {
    val name: String get() = dir.name
    val jsonFile: File get() = File(meta, "session.json")
    val finalJpg: File get() = File(out, "starcam_${dir.name}.jpg")
}

data class SessionSummary(
    val dir: File,
    val name: String,
    val mode: String,
    val startedAt: Long,
    val frameCount: Int,
    val finished: Boolean,
    val finalJpg: File?,
    /** Problems recorded in session.json (a camera failure, a lost dark frame). */
    val issueCount: Int = 0,
    val meteorCount: Int = 0,
)

/**
 * Sessions live under Android/media/<pkg>/StarCam/sessions/ so they're visible over USB for PC
 * post-processing. session.json is written incrementally: a crash loses nothing already recorded.
 */
class SessionRepository(private val context: Context) {

    fun sessionsRoot(): File {
        val base = context.externalMediaDirs.firstOrNull() ?: context.filesDir
        return File(base, "StarCam/sessions").apply { mkdirs() }
    }

    /**
     * Keeps the StarCam folder out of gallery apps. MediaStore indexes Android/media, so every DNG
     * frame and dark showed up in Google Photos and the like - dozens of near-black frames per
     * session burying the finished photos, which go to Pictures/StarCam and stay visible. Over
     * USB and in file managers the folder lists as before.
     */
    fun hideFromGallery() {
        val base = context.externalMediaDirs.firstOrNull() ?: return
        val root = File(base, "StarCam").apply { mkdirs() }
        val marker = File(root, ".nomedia")
        if (marker.exists()) return
        runCatching {
            marker.createNewFile()
            // Files indexed before the marker existed stay in galleries until a scan sees it.
            MediaScannerConnection.scanFile(context, arrayOf(root.path), null, null)
            AppLog.i("StarCam folder hidden from gallery apps (.nomedia)")
        }.onFailure { AppLog.w("could not hide the StarCam folder from gallery apps", it) }
    }

    fun newSession(mode: Mode): SessionPaths {
        pruneOldCaches()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(sessionsRoot(), "${stamp}_${mode.name.lowercase()}")
        val paths = SessionPaths(
            dir = dir,
            lights = File(dir, "lights"),
            darks = File(dir, "darks"),
            cache = File(dir, "cache"),
            out = File(dir, "out"),
            meta = File(dir, "meta"),
        )
        listOf(paths.lights, paths.darks, paths.cache, paths.out, paths.meta).forEach { it.mkdirs() }
        return paths
    }

    /**
     * keepRawCache defaults ON, so every session leaves a full sraw cache behind. Keep the 5
     * newest sessions re-stackable and empty the cache of everything older. master_dark.sraw is
     * spared (small, and needed for dark-calibrated re-stacks); DNGs, outputs and metadata are
     * never touched. A session that never got its photo (cut off, failed to stack) keeps its cache
     * for a week whatever its position: four retries after a crash used to delete the frames the
     * crashed session needed to be stacked from. And a session whose cache is its only copy of
     * the light (no photo and no DNGs: RAW-only with DNG saving off) is never pruned at all.
     */
    private fun pruneOldCaches() {
        runCatching {
            val dirs = sessionsRoot().listFiles { f -> f.isDirectory }
                ?.sortedByDescending { it.name } ?: return
            var files = 0
            var bytes = 0L
            val weekAgo = System.currentTimeMillis() - 7L * 86_400_000L
            // drop(4): this runs BEFORE the new session dir exists, so 4 kept + the one about to
            // be created = the documented 5 re-stackable sessions.
            for (dir in dirs.drop(4)) {
                val unstacked = readSessionJson(dir)?.has("result") != true
                if (unstacked && dir.lastModified() > weekAgo) continue
                val hasDngs = File(dir, "lights").listFiles { f -> f.extension == "dng" }
                    ?.isNotEmpty() == true
                if (unstacked && !hasDngs) continue
                val cached = File(dir, "cache")
                    .listFiles { f -> f.isFile && f.name != "master_dark.sraw" } ?: continue
                for (f in cached) {
                    val len = f.length()
                    if (f.delete()) {
                        files++
                        bytes += len
                    }
                }
            }
            if (files > 0) {
                AppLog.d("cache prune: $files files, ${bytes / (1024 * 1024)} MB freed")
            }
        }
    }

    // ------------------------------------------------------------------ lens shading

    /**
     * Writes the session's vignetting map next to its metadata, where the native engine looks for
     * it at finalize. Living in the session (rather than in the session config) means a later
     * re-stack of the same folder picks up the identical correction for free.
     *
     * A null map deletes any stale file: "no map" must mean no correction, never a leftover one.
     */
    fun writeShadingMap(paths: SessionPaths, map: ShadingMapData?) {
        val f = File(paths.meta, "shading.json")
        if (map == null) {
            runCatching { if (f.exists()) f.delete() }
            return
        }
        runCatching {
            val json = JSONObject().apply {
                put("cols", map.cols)
                put("rows", map.rows)
                put("maxGain", map.maxGain.toDouble())
                put("gains", JSONArray(map.gains.map { it.toDouble() }))
            }
            f.writeText(json.toString())
            AppLog.d("shading map written: ${map.cols}x${map.rows}")
        }.onFailure { AppLog.e("shading map write failed", it) }
    }

    // ------------------------------------------------------------------ dark library

    /**
     * Master darks kept between sessions. A dark is only valid for the exact camera, geometry,
     * exposure and ISO it was shot at, so all four are in the filename — a mismatch simply finds
     * nothing and the session shoots its own, which is the old behaviour.
     */
    fun darkLibraryDir(): File {
        val base = context.externalMediaDirs.firstOrNull() ?: context.filesDir
        return File(base, "StarCam/darks").apply { mkdirs() }
    }

    private fun darkName(cameraId: String, size: Size, exposureNs: Long, iso: Int): String =
        "dark_${cameraId}_${size.width}x${size.height}_${exposureNs}_$iso.sraw"

    /** The library dark's sidecar: the battery temperature it was shot at. */
    private fun darkInfoFile(dark: File) = File(dark.parentFile, dark.nameWithoutExtension + ".json")

    /**
     * Copies a matching library dark into the new session as its master dark, which is where the
     * engine looks. Returns its age in days when one was installed, null when the session should
     * shoot its own.
     */
    fun installLibraryDark(
        paths: SessionPaths,
        cameraId: String,
        size: Size,
        exposureNs: Long,
        iso: Int,
        maxAgeDays: Int,
        tempC: Float?,
    ): Int? = runCatching {
        val src = File(darkLibraryDir(), darkName(cameraId, size, exposureNs, iso))
        if (!src.isFile || src.length() <= 160L) return@runCatching null
        val ageMs = System.currentTimeMillis() - src.lastModified()
        val ageDays = (ageMs / 86_400_000L).toInt()
        // Hot pixels and dark current drift with sensor age and temperature; an old dark is worse
        // than none, so it expires rather than being silently trusted forever.
        if (ageDays > maxAgeDays) {
            AppLog.d("dark library: ${src.name} is ${ageDays}d old (limit $maxAgeDays) — skipping")
            return@runCatching null
        }
        // Dark current roughly doubles every 6 °C: a dark shot on a warm evening does not match a
        // cold night. Darks filed without a temperature (before it was recorded) are not reused.
        val darkTemp = runCatching {
            JSONObject(darkInfoFile(src).readText()).getDouble("tempC").toFloat()
        }.getOrNull()
        if (darkTemp == null || tempC == null || kotlin.math.abs(darkTemp - tempC) > DARK_REUSE_MAX_TEMP_SHIFT_C) {
            AppLog.d("dark library: ${src.name} shot at ${darkTemp ?: "?"}°C, phone now at " +
                "${tempC ?: "?"}°C — shooting fresh darks")
            return@runCatching null
        }
        src.copyTo(File(paths.cache, "master_dark.sraw"), overwrite = true)
        // Tells the engine this dark is from another night (it then subtracts only the dark's
        // structure, not its black point), on this stack and on every re-stack.
        File(paths.cache, "master_dark.library").writeText(src.name)
        AppLog.d("dark library: reusing ${src.name} (${ageDays}d old, ${darkTemp}°C)")
        ageDays
    }.onFailure { AppLog.e("dark library: install failed", it) }.getOrNull()

    /**
     * Files the master dark this session just built, so the next one at the same settings can skip
     * the "cover the lens" prompt. Called only after darks were genuinely captured.
     */
    fun saveLibraryDark(
        paths: SessionPaths,
        cameraId: String,
        size: Size,
        exposureNs: Long,
        iso: Int,
        tempC: Float?,
        keep: Int = 6,
    ) {
        runCatching {
            val src = File(paths.cache, "master_dark.sraw")
            if (!src.isFile || src.length() <= 160L) return@runCatching
            // No temperature, no reuse: such a dark could never be matched, so it is not filed.
            if (tempC == null) return@runCatching
            val dir = darkLibraryDir()
            val dst = File(dir, darkName(cameraId, size, exposureNs, iso))
            src.copyTo(dst, overwrite = true)
            darkInfoFile(dst).writeText(JSONObject().put("tempC", tempC.toDouble()).toString())
            // Each master dark is a full-resolution frame (~25 MB), and auto exposure produces a
            // new key most nights. Keep only the newest few.
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".sraw") }
                ?.sortedByDescending { it.lastModified() } ?: return@runCatching
            for (old in files.drop(keep)) {
                if (old.delete()) {
                    darkInfoFile(old).delete()
                    AppLog.d("dark library: pruned ${old.name}")
                }
            }
        }.onFailure { AppLog.e("dark library: save failed", it) }
    }

    // ------------------------------------------------------------------ interrupted sessions

    private fun runningMarker(dir: File) = File(dir, "meta/running")

    /** Marks a session as in progress; [clearRunning] removes it when the session ends at all. */
    fun markRunning(paths: SessionPaths) {
        runCatching { runningMarker(paths.dir).writeText(System.currentTimeMillis().toString()) }
    }

    fun clearRunning(paths: SessionPaths) {
        runCatching { runningMarker(paths.dir).delete() }
    }

    /**
     * Sessions the app was cut off in (a crash, the system killing it): their running marker
     * outlived the process. Each gets an issue entry saying so, and the marker is removed, so it
     * is reported exactly once. Call only while no session is running. Newest first.
     */
    fun collectInterrupted(): List<SessionSummary> {
        val found = ArrayList<File>()
        runCatching {
            sessionsRoot().listFiles { f -> f.isDirectory }?.forEach { dir ->
                val marker = runningMarker(dir)
                if (marker.isFile) {
                    found += dir
                    val paths = SessionPaths(dir, File(dir, "lights"), File(dir, "darks"),
                        File(dir, "cache"), File(dir, "out"), File(dir, "meta"))
                    writeIssue(paths, "interrupted: the app was closed or crashed before this " +
                        "session finished")
                    marker.delete()
                    AppLog.w("session $dir was interrupted (running marker found at start)")
                }
            }
        }
        if (found.isEmpty()) return emptyList()
        return listSessions().filter { s -> found.any { it.absolutePath == s.dir.absolutePath } }
    }

    /** Deletes a session folder that holds no frames at all (created, then stopped at once). */
    fun deleteIfEmpty(dir: File) {
        runCatching {
            val lights = File(dir, "lights").listFiles()?.isNotEmpty() == true
            if (!lights && cacheFrames(dir).isEmpty()) {
                dir.deleteRecursively()
                AppLog.d("removed empty session folder ${dir.name}")
            }
        }
    }

    /** Records a re-stack's result in session.json, so the session reads as stacked from now on. */
    fun writeResult(dir: File, result: JSONObject) {
        val paths = SessionPaths(dir, File(dir, "lights"), File(dir, "darks"),
            File(dir, "cache"), File(dir, "out"), File(dir, "meta"))
        writeResult(paths, result)
    }

    /** Total bytes across every session's cache/ dir (for the storage readout). */
    fun cacheSizeBytes(): Long {
        val dirs = sessionsRoot().listFiles { f -> f.isDirectory } ?: return 0L
        var total = 0L
        for (dir in dirs) {
            File(dir, "cache").listFiles()?.forEach { f -> if (f.isFile) total += f.length() }
        }
        return total
    }

    /** Creates/overwrites session.json with the header block. */
    fun writeHeader(paths: SessionPaths, header: JSONObject) {
        val root = loadForUpdate(paths)
        root.put("header", header)
        if (!root.has("frames")) root.put("frames", JSONArray())
        writeJson(paths, root)
    }

    fun appendFrame(
        paths: SessionPaths, stats: FrameStats, exposureNs: Long, iso: Int,
        gyroPeak: Float, bumped: Boolean, dngFile: String?
    ) {
        val root = loadForUpdate(paths)
        val frames = root.optJSONArray("frames") ?: JSONArray().also { root.put("frames", it) }
        frames.put(JSONObject().apply {
            put("index", stats.index)
            put("exposureNs", exposureNs)
            put("iso", iso)
            put("starCount", stats.starCount)
            put("fwhm", stats.medianFwhm.toDouble())
            put("aligned", stats.aligned)
            put("inliers", stats.inliers)
            put("residual", stats.residual.toDouble())
            put("dx", stats.dx.toDouble())
            put("dy", stats.dy.toDouble())
            put("rotDeg", stats.rotDeg.toDouble())
            put("gyroPeak", gyroPeak.toDouble())
            put("bumped", bumped)
            if (dngFile != null) put("dng", dngFile)
        })
        writeJson(paths, root)
    }

    /**
     * Records something that went wrong (a camera failure, a lost dark frame) in session.json,
     * next to the frames it affected, so the Sessions screen and a later reader can see why a
     * session ended the way it did.
     */
    fun writeIssue(paths: SessionPaths, message: String) {
        val root = loadForUpdate(paths)
        val issues = root.optJSONArray("issues") ?: JSONArray().also { root.put("issues", it) }
        issues.put(JSONObject().apply {
            put("at", System.currentTimeMillis())
            put("message", message)
        })
        writeJson(paths, root)
    }

    fun writeResult(paths: SessionPaths, result: JSONObject) {
        val root = loadForUpdate(paths)
        root.put("result", result)
        writeJson(paths, root)
    }

    fun listSessions(): List<SessionSummary> {
        val root = sessionsRoot()
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.sortedByDescending { it.name }.map { dir ->
            val json = readSessionJson(dir)
            val header = json?.optJSONObject("header")
            val jpg = File(dir, "out").listFiles { f -> f.extension == "jpg" }
                ?.maxByOrNull { it.lastModified() }
            SessionSummary(
                dir = dir,
                name = dir.name,
                mode = header?.optString("mode") ?: dir.name.substringAfterLast('_'),
                startedAt = header?.optLong("startedAt") ?: dir.lastModified(),
                frameCount = json?.optJSONArray("frames")?.length() ?: 0,
                finished = json?.has("result") == true,
                finalJpg = jpg,
                issueCount = json?.optJSONArray("issues")?.length() ?: 0,
                meteorCount = json?.optJSONObject("result")?.optInt("meteorCount", 0) ?: 0,
            )
        }
    }

    fun readSessionJson(dir: File): JSONObject? =
        runCatching {
            JSONObject(String(AtomicFile(File(dir, "meta/session.json")).readFully(), Charsets.UTF_8))
        }.getOrNull()

    fun deleteSession(dir: File) {
        runCatching { dir.deleteRecursively() }
    }

    /** Light frames re-openable for a re-stack if the sraw cache was kept (master dark excluded). */
    fun cacheFrames(dir: File): List<File> =
        File(dir, "cache")
            .listFiles { f -> f.extension == "sraw" && f.name != "master_dark.sraw" }
            ?.sorted() ?: emptyList()

    /** Deletes a session's light-frame cache (not its master dark): "working files not kept". */
    fun deleteCacheFrames(dir: File) {
        var n = 0
        cacheFrames(dir).forEach { if (it.delete()) n++ }
        if (n > 0) AppLog.d("deleted $n cached frame(s) of ${dir.name}: working files are not kept")
    }

    /**
     * session.json to update. Missing means a new session; present but unreadable is set aside
     * as session.json.corrupt-<time> rather than overwritten, which used to replace a damaged
     * manifest - header, frames, issues - with an almost empty one.
     */
    private fun loadForUpdate(paths: SessionPaths): JSONObject {
        val text = runCatching { String(AtomicFile(paths.jsonFile).readFully(), Charsets.UTF_8) }
            .getOrNull() ?: return JSONObject()
        return runCatching { JSONObject(text) }.getOrElse {
            val aside = File(paths.meta, "session.json.corrupt-${System.currentTimeMillis()}")
            runCatching { paths.jsonFile.copyTo(aside, overwrite = true) }
            AppLog.e("session.json of ${paths.dir.name} did not parse — kept as ${aside.name}", it)
            JSONObject()
        }
    }

    /**
     * Written to a new file and renamed over the old one (AtomicFile): rewritten after every
     * frame, a plain overwrite cut short by a kill or a full disk left an unreadable manifest.
     */
    private fun writeJson(paths: SessionPaths, root: JSONObject) {
        runCatching {
            val file = AtomicFile(paths.jsonFile)
            val out = file.startWrite()
            try {
                out.write(root.toString(2).toByteArray(Charsets.UTF_8))
                file.finishWrite(out)
            } catch (t: Throwable) {
                file.failWrite(out)
                throw t
            }
        }.onFailure { AppLog.e("session.json write failed", it) }
    }

    private companion object {
        /** A library dark is reused only within this of the battery temperature it was shot at. */
        const val DARK_REUSE_MAX_TEMP_SHIFT_C = 5f
    }
}
