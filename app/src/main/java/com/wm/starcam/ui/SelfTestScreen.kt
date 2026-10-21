package com.wm.starcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wm.starcam.engine.NativeEngine
import com.wm.starcam.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Green used for a passing verdict (visible but not blinding at night). */
private val PassGreen = Color(0xFF6EDB8F)

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfTestScreen(onBack: () -> Unit) {
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

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Engine self-test") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(contentScroll),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(0.dp))

                Text(
                    text = "This test synthesizes an artificial starfield and runs it through " +
                        "the full align/stack pipeline on this phone, validating the native " +
                        "engine end to end. No camera or dark sky needed — it works fine in " +
                        "daylight.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                Button(
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
                                        AppLog.d(
                                            "Self-test finished: " +
                                                if (parsed.pass) "PASS" else "FAIL"
                                        )
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
                    enabled = !running,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                ) {
                    Text(
                        text = "Run self-test",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (running) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "running (~1 min)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }

                errorMessage?.let { msg ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Text(
                            text = "Self-test could not run: $msg",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                report?.let { r ->
                    Text(
                        text = if (r.pass) "PASS" else "FAIL",
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (r.pass) PassGreen else MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )

                    r.checks.forEach { check ->
                        CheckCard(check)
                    }

                    if (r.log.isNotBlank()) {
                        Text(
                            text = r.log,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(8.dp),
                        )
                    }
                }

                Spacer(Modifier.height(0.dp))
            }

            Text(
                text = "Engine log",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .verticalScroll(logScroll)
                    .padding(8.dp),
            ) {
                Text(
                    text = if (logLines.isEmpty()) {
                        "(no log output yet)"
                    } else {
                        logLines.joinToString("\n")
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CheckCard(check: SelfTestCheck) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (check.pass) Icons.Default.Check else Icons.Default.Close,
                contentDescription = if (check.pass) "Passed" else "Failed",
                tint = if (check.pass) PassGreen else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = check.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "value ${check.value}   limit ${check.limit}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
