package com.martinhammer.tickdroid.ui.settings

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/** Width of the fade at a horizontally scrolling row's edges. */
internal val EdgeFadeWidth = 32.dp

/**
 * Fades the content out towards an edge while there's more to scroll that way: the start edge
 * while [state] can scroll backward, the end edge while it can scroll forward. Mirrored in RTL.
 */
internal fun Modifier.edgeFade(state: LazyListState, width: Dp = EdgeFadeWidth): Modifier = this
    // Offscreen, so DstIn masks this layer's own pixels rather than everything drawn below it.
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val px = width.toPx().coerceAtMost(size.width / 2)
        val rtl = layoutDirection == LayoutDirection.Rtl
        if (state.canScrollBackward) drawEdgeFade(left = !rtl, px)
        if (state.canScrollForward) drawEdgeFade(left = rtl, px)
    }

private fun DrawScope.drawEdgeFade(left: Boolean, px: Float) {
    val x = if (left) 0f else size.width - px
    val colors = if (left) listOf(Color.Transparent, Color.Black) else listOf(Color.Black, Color.Transparent)
    drawRect(
        brush = Brush.horizontalGradient(colors, startX = x, endX = x + px),
        topLeft = Offset(x, 0f),
        size = Size(px, size.height),
        blendMode = BlendMode.DstIn,
    )
}

/**
 * Scroll needed so the item at [selected] ends [fadePx] inside the viewport, i.e. clear of the
 * end fade; 0 if it's first or already clear. Items are [itemPx] wide with [gapPx] between them.
 */
internal fun openingScrollPx(selected: Int, itemPx: Float, gapPx: Float, viewportPx: Float, fadePx: Float): Float {
    if (selected <= 0) return 0f
    val end = selected * (itemPx + gapPx) + itemPx
    return (end - (viewportPx - fadePx)).coerceAtLeast(0f)
}

/**
 * Once per visit to the screen (saveable, so a rotation doesn't scroll again), after the first
 * layout, scrolls [state] so the item at [selected] is in view and clear of the end fade.
 * Choosing a different item later never scrolls the row.
 */
@Composable
internal fun OpeningScroll(state: LazyListState, selected: Int, itemSize: Dp, gap: Dp) {
    var done by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current
    LaunchedEffect(state) {
        if (done) return@LaunchedEffect
        val viewport = snapshotFlow { state.layoutInfo.viewportSize.width }.first { it > 0 }
        val itemPx = with(density) { itemSize.toPx() }
        val gapPx = with(density) { gap.toPx() }
        val fadePx = with(density) { EdgeFadeWidth.toPx() }
        val px = openingScrollPx(selected, itemPx, gapPx, viewport.toFloat(), fadePx)
        if (px > 0f) {
            val step = itemPx + gapPx
            val index = (px / step).toInt()
            state.scrollToItem(index, (px - index * step).roundToInt())
        }
        done = true
    }
}
