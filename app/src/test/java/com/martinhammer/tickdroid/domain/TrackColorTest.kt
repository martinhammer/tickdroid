package com.martinhammer.tickdroid.domain

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG contrast of Today's ticked squares against an empty one (decision row 19). */
class TrackColorTest {

    // Material's baseline surfaceContainerHighest, light and dark: the empty square.
    private val lightEmpty = Color(0xFFE6E0E9)
    private val darkEmpty = Color(0xFF36343B)

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test fun `every strong colour is at least 3 to 1 against a light empty square`() {
        TrackColor.entries.forEach {
            val ratio = contrast(it.strong, lightEmpty)
            assertTrue("${it.key} strong: %.2f".format(ratio), ratio >= 3.0)
        }
    }

    @Test fun `every container is at least 3 to 1 against a dark empty square`() {
        TrackColor.entries.forEach {
            val ratio = contrast(it.container, darkEmpty)
            assertTrue("${it.key} container: %.2f".format(ratio), ratio >= 3.0)
        }
    }
}
