package com.wm.starcam.engine

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * JNI bridge to the native stacking engine (libstarcam.so). The C++ side (`jni_bridge.cpp`) must
 * mirror these signatures exactly: Java_com_wm_starcam_engine_NativeEngine_<name>.
 *
 * All JSON payloads are FLAT objects (numbers, strings, booleans, arrays of numbers only).
 *
 * configJson (createSession): {sessionDir, mode, width, height, cfa, whiteLevel,
 *   blackLevel:[4], keepCache, livePreview}
 * metaJson (addFrame/addDarkFrame): {index, exposureNs, iso, timestampNs, wbGains:[4],
 *   ccm:[9], gyroRms}
 * metaJson (analyzeFrame): {cfa, whiteLevel, blackLevel:[4], wbGains:[4]}
 * optionsJson (finalizeStack): see [StackConfig.toJson].
 */
object NativeEngine {

    init {
        System.loadLibrary("starcam")
    }

    /** Returns an opaque session handle, or 0 on failure. */
    external fun createSession(configJson: String): Long

    /**
     * [bayer] must be the direct ByteBuffer of an ImageReader RAW_SENSOR plane (or any direct
     * buffer of uint16 Bayer data). [rowStrideBytes] is the plane row stride in BYTES. The native
     * side copies what it needs before returning. Returns per-frame stats JSON.
     */
    external fun addFrame(
        handle: Long, bayer: ByteBuffer, width: Int, height: Int, rowStrideBytes: Int,
        metaJson: String
    ): String

    external fun addDarkFrame(
        handle: Long, bayer: ByteBuffer, width: Int, height: Int, rowStrideBytes: Int,
        metaJson: String
    ): String

    /** Marks a light frame rejected before stacking (e.g. gyro detected a bump mid-exposure). */
    external fun rejectFrame(handle: Long, index: Int, reason: String)

    /** Fills [bitmap] (ARGB_8888) with the current live-stack preview. False if nothing yet. */
    external fun getPreview(handle: Long, bitmap: Bitmap): Boolean

    /**
     * Runs the full stack + post pipeline. Blocking — call from a background dispatcher.
     * [callback] is invoked on the calling thread. Returns result JSON (see EngineResult).
     */
    external fun finalizeStack(handle: Long, optionsJson: String, callback: ProgressCallback): String

    external fun abortSession(handle: Long)

    external fun destroySession(handle: Long)

    /** Stateless star/background analysis for autofocus sweeps and the exposure probe. */
    external fun analyzeFrame(
        bayer: ByteBuffer, width: Int, height: Int, rowStrideBytes: Int, metaJson: String
    ): String

    /** Synthetic end-to-end pipeline validation. Returns JSON report. Blocking (~1 min). */
    external fun runSelfTest(tmpDir: String): String

    interface ProgressCallback {
        fun onProgress(stage: String, percent: Int)
    }
}
