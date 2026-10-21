package com.wm.starcam.session

import android.content.Context
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
     * never touched.
     */
    private fun pruneOldCaches() {
        runCatching {
            val dirs = sessionsRoot().listFiles { f -> f.isDirectory }
                ?.sortedByDescending { it.name } ?: return
            var files = 0
            var bytes = 0L
            // drop(4): this runs BEFORE the new session dir exists, so 4 kept + the one about to
            // be created = the documented 5 re-stackable sessions.
            for (dir in dirs.drop(4)) {
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
        src.copyTo(File(paths.cache, "master_dark.sraw"), overwrite = true)
        AppLog.d("dark library: reusing ${src.name} (${ageDays}d old)")
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
        keep: Int = 6,
    ) {
        runCatching {
            val src = File(paths.cache, "master_dark.sraw")
            if (!src.isFile || src.length() <= 160L) return@runCatching
            val dir = darkLibraryDir()
            src.copyTo(File(dir, darkName(cameraId, size, exposureNs, iso)), overwrite = true)
            // Each master dark is a full-resolution frame (~25 MB), and auto exposure produces a
            // new key most nights. Keep only the newest few.
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".sraw") }
                ?.sortedByDescending { it.lastModified() } ?: return@runCatching
            for (old in files.drop(keep)) {
                if (old.delete()) AppLog.d("dark library: pruned ${old.name}")
            }
        }.onFailure { AppLog.e("dark library: save failed", it) }
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
        val root = readJson(paths) ?: JSONObject()
        root.put("header", header)
        if (!root.has("frames")) root.put("frames", JSONArray())
        writeJson(paths, root)
    }

    fun appendFrame(
        paths: SessionPaths, stats: FrameStats, exposureNs: Long, iso: Int,
        gyroPeak: Float, bumped: Boolean, dngFile: String?
    ) {
        val root = readJson(paths) ?: JSONObject().put("frames", JSONArray())
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
        val root = readJson(paths) ?: JSONObject()
        val issues = root.optJSONArray("issues") ?: JSONArray().also { root.put("issues", it) }
        issues.put(JSONObject().apply {
            put("at", System.currentTimeMillis())
            put("message", message)
        })
        writeJson(paths, root)
    }

    fun writeResult(paths: SessionPaths, result: JSONObject) {
        val root = readJson(paths) ?: JSONObject()
        root.put("result", result)
        writeJson(paths, root)
    }

    fun listSessions(): List<SessionSummary> {
        val root = sessionsRoot()
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.sortedByDescending { it.name }.map { dir ->
            val json = runCatching {
                JSONObject(File(dir, "meta/session.json").readText())
            }.getOrNull()
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
            )
        }
    }

    fun readSessionJson(dir: File): JSONObject? =
        runCatching { JSONObject(File(dir, "meta/session.json").readText()) }.getOrNull()

    fun deleteSession(dir: File) {
        runCatching { dir.deleteRecursively() }
    }

    /** Light frames re-openable for a re-stack if the sraw cache was kept (master dark excluded). */
    fun cacheFrames(dir: File): List<File> =
        File(dir, "cache")
            .listFiles { f -> f.extension == "sraw" && f.name != "master_dark.sraw" }
            ?.sorted() ?: emptyList()

    private fun readJson(paths: SessionPaths): JSONObject? =
        runCatching { JSONObject(paths.jsonFile.readText()) }.getOrNull()

    private fun writeJson(paths: SessionPaths, root: JSONObject) {
        runCatching {
            paths.jsonFile.writeText(root.toString(2))
        }.onFailure { AppLog.e("session.json write failed", it) }
    }
}
