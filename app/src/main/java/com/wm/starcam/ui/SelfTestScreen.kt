package com.wm.starcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wm.starcam.engine.NativeEngine
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.components.ScCard
import com.wm.starcam.ui.components.ScLabel
import com.wm.starcam.ui.components.ScNotice
import com.wm.starcam.ui.components.ScScreen
import com.wm.starcam.ui.components.ScSpinner
import com.wm.starcam.ui.components.ScTopBar
import com.wm.starcam.ui.components.Tone
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.ui.theme.ScreenMargin
import com.wm.starcam.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class SelfTestCheck(
    val name: String,
    val pass: Boolean,
    val value: String,
    val limit: String,
)

private data class SelfTestReport(
    val pass: Boolean,
    val checks: List<SelfTestCheck>,
    val log: String,
)

/** Parses the JSON report returned by [NativeEngine.runSelfTest]. Throws on malformed input. */
private fun parseSelfTestReport(raw: String): SelfTestReport {
    val json = JSONObject(raw)
    val checksJson = json.getJSONArray("checks")
    val checks = ArrayList<SelfTestCheck>(checksJson.length())
    for (i in 0 until checksJson.length()) {
        val c = checksJson.getJSONObject(i)
        checks.add(
            SelfTestCheck(
                name = c.optString("name", "check ${i + 1}"),
                pass = c.optBoolean("pass", false),
                value = c.opt("value")?.toString() ?: "?",
                limit = c.opt("limit")?.toString() ?: "?",
            )
        )
    }
    return SelfTestReport(
        pass = json.getBoolean("pass"),
        checks = checks,
        log = json.optString("log", ""),
    )
}

@Composable
fun SelfTestScreen(onBack: () -> Unit) {
    val c = Sc.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<SelfTestReport?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val logLines by AppLog.lines.collectAsState()
    val contentScroll = rememberScrollState()
    val logScroll = rememberScrollState()

    // Keep the engine log pinned to its newest line as entries arrive.
    LaunchedEffect(logLines) {
        logScroll.scrollTo(logScroll.maxValue)
    }

    ScScreen {
        ScTopBar("Engine self-test", onBack, subtitle = "The stacking engine on a synthetic starfield")
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = ScreenMargin)) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(contentScroll),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "This test synthesizes an artificial starfield and runs it through the full " +
                        "align/stack pipeline on this phone, validating the native engine end to end. " +
                        "No camera or dark sky needed — it works fine in daylight.",
                    style = ScType.body, color = c.text2,
                )

                ScButton(
                    if (running) "Running (about a minute)…" else "Run self-test",
                    onClick = {
                        if (!running) {
                            running = true
                            report = null
                            errorMessage = null
                            AppLog.d("Self-test started")
                            scope.launch {
                                val outcome = withContext(Dispatchers.Default) {
                                    runCatching {
                                        // The engine writes its synthetic frames here; it does not
                                        // create the directory itself.
                                        val tmp = java.io.File(context.cacheDir, "selftest")
                                        tmp.mkdirs()
                                        val raw = NativeEngine.runSelfTest(tmp.absolutePath)
                                        parseSelfTestReport(raw)
                                    }
                                }
                                outcome.fold(
                                    onSuccess = { parsed ->
                                        report = parsed
                                        AppLog.d("Self-test finished: " + if (parsed.pass) "PASS" else "FAIL")
                                    },
                                    onFailure = { t ->
                                        errorMessage = t.message ?: t.toString()
                                        AppLog.e("Self-test could not run", t)
                                    },
                                )
                                running = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    kind = ScButtonKind.Primary,
                    enabled = !running,
                    height = 52.dp,
                )

                if (running) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        ScSpinner(size = 24.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Stacking synthetic frames…", style = ScType.body, color = c.text2)
                    }
                }

                errorMessage?.let { msg ->
                    ScNotice("Self-test could not run: $msg", tone = Tone.Error, icon = ScIcons.Warning)
                }

                report?.let { r ->
                    Text(
                        if (r.pass) "PASS" else "FAIL",
                        style = ScType.display,
                        color = if (r.pass) c.accentText else c.error,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    r.checks.forEach { check -> CheckCard(check) }
                    if (r.log.isNotBlank()) {
                        Text(
                            r.log, style = ScType.data, color = c.text2,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(c.surface).padding(10.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            ScLabel("Engine log", Modifier.padding(top = 10.dp, bottom = 6.dp))
            Box(
                Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp))
                    .background(c.surface).verticalScroll(logScroll).padding(10.dp),
            ) {
                Text(
                    if (logLines.isEmpty()) "(no log output yet)" else logLines.joinToString("\n"),
                    style = ScType.data, color = c.text3,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CheckCard(check: SelfTestCheck) {
    val c = Sc.colors
    ScCard(Modifier.fillMaxWidth(), color = c.tile) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (check.pass) ScIcons.CheckCircle else ScIcons.Warning,
                if (check.pass) "Passed" else "Failed",
                Modifier.size(24.dp),
                tint = if (check.pass) c.accent else c.error,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(check.name, style = ScType.headline, color = c.text)
                Text("value ${check.value}   limit ${check.limit}", style = ScType.data, color = c.text3,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
