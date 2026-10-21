package com.wm.starcam.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.wm.starcam.StarCamViewModel
import com.wm.starcam.util.AppLog

/**
 * The camera viewfinder: a SurfaceView fixed to a camera-supported size so it is never stretched,
 * wired to the view model's attach/detach. Used by the capture screen and the Diagnostics screen
 * (whose camera test must run with a live preview in the session, like a real shoot).
 */
@Composable
fun CameraPreview(vm: StarCamViewModel, modifier: Modifier = Modifier) {
    val camInfo by vm.camInfo.collectAsState()
    val previewSize = remember(camInfo) { camInfo?.previewSize() }
    // Compose aspectRatio is width/height; the sensor is landscape, the app portrait.
    val previewAspect = previewSize?.let { it.height.toFloat() / it.width } ?: 0.75f
    AndroidView(
        modifier = modifier.aspectRatio(previewAspect),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        AppLog.d("ui", "preview surface created")
                        vm.attachSurface(h.surface)
                    }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                        AppLog.d("ui", "preview surface changed: ${w}x$hh format $f")
                        vm.attachSurface(h.surface)
                    }
                    override fun surfaceDestroyed(h: SurfaceHolder) = vm.detachSurface()
                })
            }
        },
        update = { view ->
            previewSize?.let { view.holder.setFixedSize(it.width, it.height) }
        },
    )
}
