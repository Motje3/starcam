package com.wm.starcam

import android.app.Application
import com.wm.starcam.util.AppLog
import com.wm.starcam.util.Diagnostics

/**
 * Exists to start logging before anything else runs: the file log, the crash handler and the
 * lifecycle trace must already be in place when the first activity or camera call happens.
 */
class StarCamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        Diagnostics.start(this)
    }
}
