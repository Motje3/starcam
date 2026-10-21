package com.wm.starcam

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wm.starcam.camera.CameraTest
import com.wm.starcam.ui.CaptureScreen
import com.wm.starcam.ui.DiagnosticsScreen
import com.wm.starcam.ui.ProPanelSheet
import com.wm.starcam.ui.ProbeScreen
import com.wm.starcam.ui.SelfTestScreen
import com.wm.starcam.ui.SessionDetailScreen
import com.wm.starcam.ui.SessionsScreen
import com.wm.starcam.ui.theme.StarCamTheme
import com.wm.starcam.util.AppLog
import java.io.File

sealed class Screen {
    data object Capture : Screen()
    data object Sessions : Screen()
    data class SessionDetail(val dir: File) : Screen()
    data object Probe : Screen()
    data object SelfTest : Screen()
    data object Diagnostics : Screen()
}

class MainActivity : ComponentActivity() {

    private var permissionGranted = mutableStateOf(false)

    /** A screen an adb-triggered test or session wants on top; consumed by [App]. */
    private val requestedScreen = mutableStateOf<Screen?>(null)

    private val vm: StarCamViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppLog.i("life", "camera permission ${if (granted) "granted" else "DENIED"}")
            permissionGranted.value = granted
            if (granted) requestNotificationsIfNeeded()
        }

    /**
     * Purely cosmetic: the capture foreground service (and therefore the session) runs whether or
     * not this is granted. Without it the progress notification simply is not drawn, so a refusal
     * is never treated as an error.
     */
    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A night tool: never let the screen sleep mid-session.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        permissionGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted.value) {
            // The notification prompt follows in the camera result callback: two permission
            // dialogs launched together and the second one is dropped.
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            requestNotificationsIfNeeded()
        }

        setContent {
            val granted by permissionGranted
            App(granted, requestedScreen)
        }
        handleDebugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * Lets a PC run the camera test over USB without anyone touching the phone:
     *
     *   adb shell am start -n com.wm.starcam/.MainActivity --ez camtest true
     *       --ei frames 5 --ei exp_ms 20000 --ei iso 800 --ez work true --ez recovery false
     *       [--ez preview false] [--ez fresh true] [--ez fd true] [--ez lsc true]
     *
     * The bracketed extras override the Pro → Debug switches for this one run.
     *
     *   adb shell am start -n com.wm.starcam/.MainActivity --ez session true
     *
     * starts a real session with the current settings, exactly as the shutter button does.
     *
     * Honoured in debuggable builds only: the activity is exported, and no other app should be
     * able to start the camera through it.
     */
    private fun handleDebugIntent(i: Intent?) {
        if (i == null) return
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (i.getBooleanExtra("session", false)) {
            i.removeExtra("session")  // a configuration change must not start it again
            if (!debuggable) return
            requestedScreen.value = Screen.Capture
            vm.startSessionWhenReady()
            return
        }
        if (!i.getBooleanExtra("camtest", false)) return
        i.removeExtra("camtest")
        if (!debuggable) return
        fun opt(name: String) = if (i.hasExtra(name)) i.getBooleanExtra(name, false) else null
        val p = CameraTest.Params(
            frames = i.getIntExtra("frames", 5).coerceIn(1, 50),
            exposureMs = i.getIntExtra("exp_ms", 20_000).toLong().coerceIn(1L, 60_000L),
            iso = i.getIntExtra("iso", 800),
            simulateWork = i.getBooleanExtra("work", true),
            recovery = i.getBooleanExtra("recovery", false),
            previewInSession = opt("preview"),
            freshSession = opt("fresh"),
            frameDuration = opt("fd"),
            shadingEveryFrame = opt("lsc"),
        )
        requestedScreen.value = Screen.Diagnostics
        vm.runCameraTestWhenReady(p)
    }
}

@Composable
private fun App(cameraGranted: Boolean, requestedScreen: MutableState<Screen?>) {
    val vm: StarCamViewModel = viewModel()
    val settings by vm.settingsStore.settings.collectAsState()

    androidx.compose.runtime.LaunchedEffect(cameraGranted) {
        // Idempotent; kicks off camera discovery once.
        if (cameraGranted) vm.onPermissionGranted()
    }

    StarCamTheme(nightMode = settings.nightMode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var screen by remember { mutableStateOf<Screen>(Screen.Capture) }
            var showPro by remember { mutableStateOf(false) }
            val wanted by requestedScreen
            LaunchedEffect(wanted) {
                wanted?.let {
                    screen = it
                    showPro = false
                    requestedScreen.value = null
                }
            }
            LaunchedEffect(screen) { AppLog.d("ui", "screen: ${screen.javaClass.simpleName}") }

            Box(Modifier.fillMaxSize()) {
                if (!cameraGranted) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("StarCam needs the camera permission.\nGrant it in Settings → Apps → StarCam.")
                    }
                } else {
                    when (val sc = screen) {
                        Screen.Capture -> CaptureScreen(
                            vm = vm,
                            onOpenPro = { showPro = true },
                            onOpenSessions = { screen = Screen.Sessions },
                            onOpenProbe = { screen = Screen.Probe },
                            onOpenSelfTest = { screen = Screen.SelfTest },
                            onOpenDiagnostics = { screen = Screen.Diagnostics },
                        )
                        Screen.Sessions -> SessionsScreen(
                            repo = vm.repo,
                            onOpen = { dir -> screen = Screen.SessionDetail(dir) },
                            onBack = { screen = Screen.Capture },
                        )
                        is Screen.SessionDetail -> SessionDetailScreen(
                            dir = sc.dir,
                            repo = vm.repo,
                            onBack = { screen = Screen.Sessions },
                        )
                        Screen.Probe -> ProbeScreen(onBack = { screen = Screen.Capture })
                        Screen.SelfTest -> SelfTestScreen(onBack = { screen = Screen.Capture })
                        Screen.Diagnostics -> DiagnosticsScreen(vm = vm, onBack = { screen = Screen.Capture })
                    }
                    if (showPro) {
                        ProPanelSheet(vm = vm, onDismiss = { showPro = false })
                    }
                    BackHandler(enabled = screen != Screen.Capture || showPro) {
                        if (showPro) showPro = false
                        else screen = when (screen) {
                            is Screen.SessionDetail -> Screen.Sessions
                            else -> Screen.Capture
                        }
                    }
                }

                // Night-vision dim scrim: draws over everything, consumes no touches.
                if (settings.nightMode && settings.nightDim > 0f) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(Color.Black.copy(alpha = settings.nightDim))
                    )
                }
            }
        }
    }
}
