package com.wm.starcam.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.wm.starcam.ui.components.ScGlass
import com.wm.starcam.ui.theme.Sc
import com.wm.starcam.ui.theme.ScIcons
import com.wm.starcam.ui.theme.ScType
import com.wm.starcam.ui.theme.scImageFilter
import kotlin.math.roundToInt

/**
 * One frame against the finished stack, same framing, split by a handle: drag (or tap) to move
 * it. The quickest way to see what stacking did - and the picture people share.
 */
@Composable
fun CompareSlider(
    before: ImageBitmap,
    after: ImageBitmap,
    beforeLabel: String,
    afterLabel: String,
    modifier: Modifier = Modifier,
) {
    val c = Sc.colors
    var split by remember { mutableFloatStateOf(0.5f) }
    var width by remember { mutableIntStateOf(1) }
    Box(
        modifier.fillMaxWidth().aspectRatio(after.width.toFloat() / after.height.coerceAtLeast(1))
            .background(c.sunken)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures { split = (it.x / width).coerceIn(0f, 1f) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    split = (change.position.x / width).coerceIn(0f, 1f)
                }
            },
    ) {
        Image(after, afterLabel, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            colorFilter = scImageFilter)
        Image(
            before, beforeLabel,
            Modifier.fillMaxSize().drawWithContent {
                clipRect(right = size.width * split) { this@drawWithContent.drawContent() }
            },
            contentScale = ContentScale.Fit, colorFilter = scImageFilter,
        )
        // The handle.
        Box(
            Modifier.fillMaxHeight().width(2.dp)
                .offset { IntOffset((width * split).roundToInt() - 1.dp.roundToPx(), 0) }
                .background(c.text),
        )
        Box(
            Modifier.align(Alignment.CenterStart)
                .offset { IntOffset((width * split).roundToInt() - 16.dp.roundToPx(), 0) }
                .size(32.dp).clip(CircleShape).background(c.glass),
            contentAlignment = Alignment.Center,
        ) { Icon(ScIcons.Compare, "Drag to compare", Modifier.size(18.dp), tint = c.text) }
        ScGlass(Modifier.align(Alignment.TopStart).padding(10.dp)) {
            Text(beforeLabel.uppercase(), style = ScType.label, color = c.text)
        }
        ScGlass(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
            Text(afterLabel.uppercase(), style = ScType.label, color = c.text)
        }
    }
}
