package com.wm.starcam.engine

import org.json.JSONObject

/** Parsed result of NativeEngine.finalizeStack. */
data class EngineResult(
    val ok: Boolean,
    val error: String,
    val width: Int,
    val height: Int,
    val finalRgbPath: String,
    val tiffPath: String,
    val framesUsed: Int,
    val framesTotal: Int,
    val rejected: String,          // "idx:reason;idx:reason"
    val rejectionPct: Float,
    val fwhmRef: Float,
    val fwhmStack: Float,
    val elapsedMs: Long,
    val meteorCount: Int,
    /** Comma-separated frame indices of composited meteor candidates ("12,57,203"; "" if none). */
    val meteorFrames: String,
    /** Foreground composite outcome: "applied (sky NN%)", "skipped (reason)", or "" (not attempted). */
    val foreground: String,
    /** One frame developed like the stack, small (u8 RGB), for "1 frame vs stacked"; "" if none. */
    val singlePath: String = "",
    val singleWidth: Int = 0,
    val singleHeight: Int = 0,
    /** The engine's result JSON as returned, for session.json. */
    val json: String = "",
) {
    companion object {
        fun fromJson(json: String): EngineResult {
            val o = runCatching { JSONObject(json) }.getOrElse { JSONObject() }
            return EngineResult(
                json = json,
                ok = o.optBoolean("ok", false),
                error = o.optString("error", "unparseable engine result"),
                width = o.optInt("width", 0),
                height = o.optInt("height", 0),
                finalRgbPath = o.optString("finalRgbPath", ""),
                tiffPath = o.optString("tiffPath", ""),
                framesUsed = o.optInt("framesUsed", 0),
                framesTotal = o.optInt("framesTotal", 0),
                rejected = o.optString("rejected", ""),
                rejectionPct = o.optDouble("rejectionPct", 0.0).toFloat(),
                fwhmRef = o.optDouble("fwhmRef", 0.0).toFloat(),
                fwhmStack = o.optDouble("fwhmStack", 0.0).toFloat(),
                elapsedMs = o.optLong("elapsedMs", 0L),
                meteorCount = o.optInt("meteorCount", 0),
                meteorFrames = o.optString("meteorFrames", ""),
                foreground = o.optString("foreground", ""),
                singlePath = o.optString("singlePath", ""),
                singleWidth = o.optInt("singleWidth", 0),
                singleHeight = o.optInt("singleHeight", 0),
            )
        }
    }
}

/** Parsed result of NativeEngine.addFrame. */
data class FrameStats(
    val index: Int,
    val saved: Boolean,
    val starCount: Int,
    val medianFwhm: Float,
    val bgFrac: Float,
    val dx: Float,
    val dy: Float,
    val rotDeg: Float,
    val inliers: Int,
    val residual: Float,
    val aligned: Boolean,
    val streaks: Int,
) {
    companion object {
        fun fromJson(json: String): FrameStats {
            val o = runCatching { JSONObject(json) }.getOrElse { JSONObject() }
            return FrameStats(
                index = o.optInt("index", -1),
                saved = o.optBoolean("saved", false),
                starCount = o.optInt("starCount", 0),
                medianFwhm = o.optDouble("medianFwhm", 0.0).toFloat(),
                bgFrac = o.optDouble("bgFrac", 0.0).toFloat(),
                dx = o.optDouble("dx", 0.0).toFloat(),
                dy = o.optDouble("dy", 0.0).toFloat(),
                rotDeg = o.optDouble("rotDeg", 0.0).toFloat(),
                inliers = o.optInt("inliers", 0),
                residual = o.optDouble("residual", 0.0).toFloat(),
                aligned = o.optBoolean("aligned", false),
                streaks = o.optInt("streaks", 0),
            )
        }
    }
}

/** Parsed result of NativeEngine.analyzeFrame. */
data class AnalyzeStats(
    val bgFrac: Float,
    val starCount: Int,
    val medianFwhm: Float,
    val noiseSigma: Float,
) {
    companion object {
        fun fromJson(json: String): AnalyzeStats {
            val o = runCatching { JSONObject(json) }.getOrElse { JSONObject() }
            return AnalyzeStats(
                bgFrac = o.optDouble("bgFrac", 0.0).toFloat(),
                starCount = o.optInt("starCount", 0),
                medianFwhm = o.optDouble("medianFwhm", 0.0).toFloat(),
                noiseSigma = o.optDouble("noiseSigma", 0.0).toFloat(),
            )
        }
    }
}
