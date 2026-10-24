package com.wm.starcam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.wm.starcam.camera.CameraTest
import com.wm.starcam.camera.SkySim
import com.wm.starcam.sky.GeoLocation
import com.wm.starcam.ui.CalendarScreen
import com.wm.starcam.ui.CaptureScreen
import com.wm.starcam.ui.DarkSkyScreen
import com.wm.starcam.ui.DiagnosticsScreen
import com.wm.starcam.ui.OnboardingScreen
import com.wm.starcam.ui.PermissionScreen
import com.wm.starcam.ui.ProbeScreen
import com.wm.starcam.ui.SelfTestScreen
import com.wm.starcam.ui.SessionDetailScreen
import com.wm.starcam.ui.SessionsScreen
import com.wm.starcam.ui.SettingsScreen
import com.wm.starcam.ui.SettingsTab
import com.wm.starcam.ui.SkyViewRequest
import com.wm.starcam.ui.StudioScreen
import com.wm.starcam.ui.TonightScreen
import com.wm.starcam.ui.UiPrefs
import com.wm.starcam.ui.VideoScreen
import com.wm.starcam.ui.theme.LocalNightDim
import com.wm.starcam.ui.theme.StarCamTheme
import com.wm.starcam.util.AppLog
import java.io.File

sealed class Screen {
    data object Capture : Screen()
    data object Sessions : Screen()
    data class SessionDetail(val dir: File) : Screen()
    data class Studio(val dir: File) : Screen()
    data class Video(val file: File) : Screen()
    data object Tonight : Screen()
    /** The planner for a place from the dark-sky map, not saved as yours. */
    data class TonightThere(val lat: Double, val lon: Double, val label: String) : Screen()
    /** The planner on a given night (from the sky calendar). */
    data class TonightOn(val date: java.time.LocalDate) : Screen()
    data object DarkSky : Screen()
    data object Calendar : Screen()
    data object Settings : Screen()
    data object Probe : Screen()
    data object SelfTest : Screen()
    data object Diagnostics : Screen()

    /** Identity of the screen's saved UI state in the back stack. */
    val key: String
        get() = when (this) {
            is SessionDetail -> "detail:${dir.path}"
            is Studio -> "studio:${dir.path}"
            is Video -> "video:${file.path}"
            is TonightThere -> "tonight:$lat,$lon"
            is TonightOn -> "tonight:$date"
            else -> javaClass.simpleName
        }
}

class MainActivity : ComponentActivity() {

    private var permissionGranted = mutableStateOf(false)

    /** The system will not show the camera dialog again; only app settings can grant it now. */
    private var permissionDeniedForever = mutableStateOf(false)

    /** A screen an adb-triggered test or session wants on top; consumed by [App]. */
    private val requestedScreen = mutableStateOf<Screen?>(null)

    // From the application's store, not the activity's: see StarCamApp.
    private val vm: StarCamViewModel by lazy {
        ViewModelProvider(
            application as StarCamApp,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application),
        )[StarCamViewModel::class.java]
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppLog.i("life", "camera permission ${if (granted) "granted" else "DENIED"}")
            permissionGranted.value = granted
            // After a denial the system stops asking once the user has refused twice (or ticked
            // "don't ask again"); the screen then points to app settings instead.
            permissionDeniedForever.value =
                !granted && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
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

    private fun cameraGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    /** Called from the permission screen, after the intro has said why. */
    private fun requestCamera() {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the system bars on every Android version (35+ enforces it anyway), with
        // light icons: the app is dark everywhere, and the screens pad themselves by the insets.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // A night tool: never let the screen sleep mid-session.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        permissionGranted.value = cameraGranted()
        if (permissionGranted.value) requestNotificationsIfNeeded()

        setContent {
            val granted by permissionGranted
            val deniedForever by permissionDeniedForever
            App(
                vm = vm,
                cameraGranted = granted,
                deniedForever = deniedForever,
                requestedScreen = requestedScreen,
                onRequestCamera = ::requestCamera,
                onOpenAppSettings = ::openAppSettings,
            )
        }
        handleDebugIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.onUiShown()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.onUiHidden()
    }

    override fun onResume() {
        super.onResume()
        // Granted from the system's app settings while we were away.
        if (!permissionGranted.value && cameraGranted()) {
            AppLog.i("life", "camera permission granted in system settings")
            permissionGranted.value = true
            permissionDeniedForever.value = false
            requestNotificationsIfNeeded()
        }
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
     * The bracketed extras override the Settings › Advanced switches for this one run.
     *
     *   adb shell am start -n com.wm.starcam/.MainActivity --ez session true
     *
     * starts a real session with the current settings, exactly as the shutter button does.
     * [--es sim "frames=12;exp=300;clouds=5-8;drift=12:0.06;dawn=20"] runs it under a simulated
     * sky instead of the camera's picture (camera/SkySim.kt): the emulator's camera shows no stars.
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
            SkySim.armed = i.getStringExtra("sim")?.let { SkySim.parse(it) }
            i.removeExtra("sim")
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
private fun App(
    vm: StarCamViewModel,
    cameraGranted: Boolean,
    deniedForever: Boolean,
    requestedScreen: MutableState<Screen?>,
    onRequestCamera: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val settings by vm.settingsStore.settings.collectAsState()
    val context = LocalContext.current
    val uiPrefs = remember { UiPrefs(context) }
    var onboarded by remember { mutableStateOf(uiPrefs.onboarded) }

    LaunchedEffect(cameraGranted) {
        // Idempotent; kicks off camera discovery once.
        if (cameraGranted) vm.onPermissionGranted()
    }

    // The system draws its bar icons white on a dark app, which night vision must not show. Asking
    // for "light bars" makes them dark instead: near-invisible on the red-black background. (Hiding
    // the bars would pop the system's bright "Viewing full screen" panel the first time.)
    val activity = context as? Activity
    LaunchedEffect(settings.nightMode) {
        val window = activity?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = settings.nightMode
            isAppearanceLightNavigationBars = settings.nightMode
        }
    }

    StarCamTheme(nightMode = settings.nightMode) {
      CompositionLocalProvider(LocalNightDim provides if (settings.nightMode) settings.nightDim else 0f) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // A tiny back stack: Capture is always the root. Each screen's saveable state (scroll
            // position, open tab) is kept while it is in the stack, so coming back from a
            // sub-screen lands where you left, and dropped when the screen is popped.
            var stack by remember { mutableStateOf(listOf<Screen>(Screen.Capture)) }
            var settingsTab by remember { mutableStateOf(SettingsTab.Exposure) }
            val saveable = rememberSaveableStateHolder()
            val screen = stack.last()
            fun push(s: Screen) { stack = stack + s }
            fun pop() {
                if (stack.size > 1) {
                    val gone = stack.last()
                    stack = stack.dropLast(1)
                    saveable.removeState(gone.key)
                }
            }

            val wanted by requestedScreen
            LaunchedEffect(wanted) {
                wanted?.let {
                    // A PC-driven test or session must never wait behind the intro.
                    if (!onboarded) {
                        onboarded = true
                        uiPrefs.onboarded = true
                    }
                    stack = if (it == Screen.Capture) listOf(Screen.Capture) else listOf(Screen.Capture, it)
                    requestedScreen.value = null
                }
            }
            LaunchedEffect(screen) { AppLog.d("ui", "screen: ${screen.javaClass.simpleName}") }
            // Back pops the screen. Registered before any screen's own BackHandler, so those win
            // while enabled: the dispatcher asks the newest callback first.
            BackHandler(enabled = onboarded && cameraGranted && stack.size > 1) { pop() }

            Box(Modifier.fillMaxSize()) {
                when {
                    !onboarded -> OnboardingScreen(onDone = {
                        onboarded = true
                        uiPrefs.onboarded = true
                    })
                    !cameraGranted -> PermissionScreen(
                        deniedForever = deniedForever,
                        onAllow = onRequestCamera,
                        onOpenSettings = onOpenAppSettings,
                    )
                    else -> saveable.SaveableStateProvider(screen.key) {
                        when (val sc = screen) {
                            Screen.Capture -> CaptureScreen(
                                vm = vm,
                                onOpenSessions = { push(Screen.Sessions) },
                                onOpenSession = { dir ->
                                    stack = listOf(Screen.Capture, Screen.Sessions, Screen.SessionDetail(dir))
                                },
                                onOpenSettings = { push(Screen.Settings) },
                                onOpenProbe = { push(Screen.Probe) },
                                onOpenTonight = { push(Screen.Tonight) },
                                onOpenStudio = { dir -> push(Screen.Studio(dir)) },
                                onOpenDarkSky = { push(Screen.DarkSky) },
                            )
                            Screen.Sessions -> SessionsScreen(
                                repo = vm.repo,
                                onOpen = { dir -> push(Screen.SessionDetail(dir)) },
                                onBack = ::pop,
                            )
                            is Screen.SessionDetail -> SessionDetailScreen(
                                dir = sc.dir,
                                vm = vm,
                                onBack = ::pop,
                                onEdit = { push(Screen.Studio(sc.dir)) },
                                onPlayVideo = { file -> push(Screen.Video(file)) },
                            )
                            is Screen.Studio -> StudioScreen(dir = sc.dir, vm = vm, onBack = ::pop)
                            is Screen.Video -> VideoScreen(file = sc.file, onBack = ::pop)
                            Screen.Tonight -> TonightScreen(
                                onBack = ::pop,
                                onShowSky = { atMs ->
                                    vm.skyView.value = SkyViewRequest(atMs)
                                    stack = listOf(Screen.Capture)
                                },
                                onFind = { atMs, name ->
                                    vm.skyView.value = SkyViewRequest(atMs, name)
                                    stack = listOf(Screen.Capture)
                                },
                                onOpenDarkSky = { push(Screen.DarkSky) },
                                onOpenCalendar = { push(Screen.Calendar) },
                            )
                            is Screen.TonightOn -> TonightScreen(
                                onBack = ::pop,
                                onShowSky = { atMs ->
                                    vm.skyView.value = SkyViewRequest(atMs)
                                    stack = listOf(Screen.Capture)
                                },
                                onFind = { atMs, name ->
                                    vm.skyView.value = SkyViewRequest(atMs, name)
                                    stack = listOf(Screen.Capture)
                                },
                                initialDate = sc.date,
                            )
                            Screen.Calendar -> CalendarScreen(
                                onBack = ::pop,
                                onShowSky = { atMs ->
                                    vm.skyView.value = SkyViewRequest(atMs)
                                    stack = listOf(Screen.Capture)
                                },
                                onPlanNight = { date -> push(Screen.TonightOn(date)) },
                            )
                            is Screen.TonightThere -> TonightScreen(
                                onBack = ::pop,
                                onShowSky = { atMs ->
                                    vm.skyView.value = SkyViewRequest(atMs)
                                    stack = listOf(Screen.Capture)
                                },
                                onFind = { atMs, name ->
                                    vm.skyView.value = SkyViewRequest(atMs, name)
                                    stack = listOf(Screen.Capture)
                                },
                                there = GeoLocation(sc.lat, sc.lon),
                                thereLabel = sc.label,
                            )
                            Screen.DarkSky -> DarkSkyScreen(
                                onBack = ::pop,
                                onPlanThere = { loc, label -> push(Screen.TonightThere(loc.latDeg, loc.lonDeg, label)) },
                            )
                            Screen.Settings -> SettingsScreen(
                                vm = vm,
                                initialTab = settingsTab,
                                onTabChange = { settingsTab = it },
                                onBack = ::pop,
                                onOpenDiagnostics = { push(Screen.Diagnostics) },
                                onOpenProbe = { push(Screen.Probe) },
                                onOpenSelfTest = { push(Screen.SelfTest) },
                                onReplayIntro = {
                                    stack = listOf(Screen.Capture)
                                    onboarded = false
                                    uiPrefs.onboarded = false
                                },
                            )
                            Screen.Probe -> ProbeScreen(onBack = ::pop)
                            Screen.SelfTest -> SelfTestScreen(onBack = ::pop)
                            Screen.Diagnostics -> DiagnosticsScreen(vm = vm, onBack = ::pop)
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
}
