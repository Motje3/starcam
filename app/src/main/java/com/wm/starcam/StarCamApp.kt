package com.wm.starcam

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.wm.starcam.util.AppLog
import com.wm.starcam.util.Diagnostics

/**
 * Exists to start logging before anything else runs: the file log, the crash handler and the
 * lifecycle trace must already be in place when the first activity or camera call happens.
 *
 * It also owns the view models, which therefore live as long as the process rather than the
 * activity: a capture session has to outlive its screen. Swiping StarCam out of recents mid-session
 * used to destroy the activity, clear its view model and cancel the capture with it; now the
 * session carries on under its foreground service, and opening the app again shows it where it is.
 */
class StarCamApp : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        Diagnostics.start(this)
    }
}
