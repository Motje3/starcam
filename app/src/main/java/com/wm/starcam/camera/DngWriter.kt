package com.wm.starcam.camera

import android.hardware.camera2.DngCreator
import com.wm.starcam.util.AppLog
import java.io.File
import java.io.FileOutputStream

/** Archival DNG writing (PLAN.md §3.3). Failure-tolerant: OnePlus quirks must not kill a session. */
object DngWriter {

    fun write(cam: CamInfo, shot: CapturedRaw, file: File): Boolean {
        return try {
            file.parentFile?.mkdirs()
            DngCreator(cam.characteristics, shot.result).use { dng ->
                FileOutputStream(file).use { out ->
                    dng.writeImage(out, shot.image)
                }
            }
            true
        } catch (t: Throwable) {
            AppLog.e("DNG write failed for ${file.name} (continuing session)", t)
            runCatching { file.delete() }
            false
        }
    }
}
