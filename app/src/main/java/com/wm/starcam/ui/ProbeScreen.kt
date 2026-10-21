package com.wm.starcam.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.wm.starcam.camera.CapabilityProbe
import com.wm.starcam.ui.components.ScIconButton
import com.wm.starcam.ui.components.ScScreen
import com.wm.starcam.ui.components.ScSpinner
import com.wm.starcam.ui.components.ScTopBar
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PROBING_PLACEHOLDER = "probing..."

/**
 * Shows the full [CapabilityProbe] report — the definitive answer to what the OnePlus
 * camera HAL exposes to third-party apps. Must never crash: the probe runs inside
 * runCatching and any exception is rendered into the report text instead.
 */
@Composable
fun ProbeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var report by remember { mutableStateOf(PROBING_PLACEHOLDER) }

    LaunchedEffect(Unit) {
        val appContext = context.applicationContext
        report = withContext(Dispatchers.Default) {
            runCatching { CapabilityProbe.buildReport(appContext) }
                .getOrElse { t -> "!! Probe threw an exception:\n\n${t.stackTraceToString()}" }
        }
    }

    ScScreen {
        ScTopBar(
            "Capability probe", onBack,
            subtitle = "What this camera exposes to apps",
            actions = {
                ScIconButton(ScIcons.Log, "Copy report", { clipboard.setText(AnnotatedString(report)) }, filled = false)
                ScIconButton(ScIcons.Share, "Share report", {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "StarCam capability probe")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(send, "Share probe report"))
                    }
                }, filled = false)
            },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (report == PROBING_PLACEHOLDER) {
                ScSpinner(Modifier.align(Alignment.CenterHorizontally).padding(24.dp), size = 28.dp)
            } else {
                SelectionContainer {
                    Text(
                        text = report,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        style = ScType.data,
                        color = Sc.colors.text2,
                        softWrap = true,
                    )
                }
            }
        }
    }
}
