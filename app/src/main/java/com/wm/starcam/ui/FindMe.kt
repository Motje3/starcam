package com.wm.starcam.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wm.starcam.sensors.DeviceLocation
import com.wm.starcam.sensors.PlaceFinder
import com.wm.starcam.sensors.PlaceSearch
import com.wm.starcam.ui.components.ScButton
import com.wm.starcam.ui.components.ScButtonKind
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScType
import kotlinx.coroutines.delay

/** What "Use my location" does on any screen: the permission if need be, then the one search. */
@Composable
fun rememberFindMe(): () -> Unit {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) PlaceFinder.find(context) else PlaceFinder.notAllowed()
    }
    return remember(permission) {
        {
            if (DeviceLocation.permitted(context)) PlaceFinder.find(context)
            else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
}

/**
 * For a screen with no place yet: "Use my location" or, whichever screen started it, the search
 * going on. Once the permission has been given it starts by itself.
 */
@Composable
fun FindMeButton(
    modifier: Modifier = Modifier,
    icon: ImageVector? = ScIcons.Pin,
    height: Dp = 52.dp,
    failText: String = "Could not find you. Is location on?",
) {
    val c = Sc.colors
    val context = LocalContext.current
    val status by PlaceFinder.status.collectAsState()
    val findMe = rememberFindMe()
    LaunchedEffect(Unit) { if (DeviceLocation.permitted(context)) PlaceFinder.find(context) }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(status) {
        slow = false
        if (status == PlaceSearch.Finding) {
            delay(8_000L)
            slow = true
        }
    }
    Column(modifier.width(IntrinsicSize.Max)) {
        if (status == PlaceSearch.Finding) {
            FindingYou(Modifier.fillMaxWidth(), height)
        } else {
            ScButton("Use my location", findMe, Modifier.fillMaxWidth(), kind = ScButtonKind.Primary,
                icon = icon, height = height)
        }
        val note = when {
            status == PlaceSearch.NotFound -> failText
            slow -> "Still looking. Indoors this can take half a minute."
            else -> null
        }
        if (note != null) {
            Text(note, style = ScType.bodySmall,
                color = if (status == PlaceSearch.NotFound) c.warning else c.text2,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** The search going on: a pin sending out rings, and dots lighting one after the other. */
@Composable
fun FindingYou(modifier: Modifier = Modifier, height: Dp = 52.dp) {
    val c = Sc.colors
    val t = rememberInfiniteTransition(label = "finding")
    val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "wave")
    val rings = c.accent
    val dots = c.accentText
    val radar = height - 10.dp
    Row(
        modifier.height(height).clip(CircleShape).background(c.selectedFill)
            .border(1.dp, c.selectedBorder, CircleShape)
            .semantics(mergeDescendants = true) {
                contentDescription = "Finding you"
                liveRegion = LiveRegionMode.Polite
            }
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(radar), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val most = size.minDimension / 2f
                for (i in 0..1) {
                    val p = (wave + i * 0.5f) % 1f
                    drawCircle(rings.copy(alpha = (1f - p) * 0.9f), most * (0.35f + 0.65f * p), style = Stroke(1.5.dp.toPx()))
                }
            }
            Icon(ScIcons.Pin, null, Modifier.size(radar * 0.45f), tint = c.accentText)
        }
        Spacer(Modifier.width(8.dp))
        Text("Finding you", style = ScType.button, color = c.text, maxLines = 1)
        // Their room is kept, so the words stand still.
        Canvas(Modifier.padding(start = 4.dp).size(width = 18.dp, height = 12.dp)) {
            val r = 1.8.dp.toPx()
            for (i in 0..2) {
                val p = (wave - i / 3f + 1f) % 1f
                val alpha = if (p < 0.5f) 1f - p * 1.5f else 0.25f
                drawCircle(dots.copy(alpha = alpha), r, Offset(r + i * 6.dp.toPx(), size.height / 2f))
            }
        }
    }
}
