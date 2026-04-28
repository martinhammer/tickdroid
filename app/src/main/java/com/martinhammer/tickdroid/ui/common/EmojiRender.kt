package com.martinhammer.tickdroid.ui.common

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

/**
 * Render the wrapped composable with reduced color saturation and a small alpha drop,
 * so emoji glyphs blend with the UI instead of clashing against M3 surfaces.
 */
fun Modifier.desaturatedEmoji(
    saturation: Float = 0.4f,
    alpha: Float = 0.85f,
): Modifier = composed {
    val matrix = remember(saturation) { ColorMatrix().apply { setToSaturation(saturation) } }
    val filter = remember(matrix) { ColorFilter.colorMatrix(matrix) }
    drawWithContent {
        drawIntoCanvas { canvas ->
            val paint = Paint().apply {
                colorFilter = filter
                this.alpha = alpha
            }
            canvas.saveLayer(Rect(Offset.Zero, size), paint)
            drawContent()
            canvas.restore()
        }
    }
}
