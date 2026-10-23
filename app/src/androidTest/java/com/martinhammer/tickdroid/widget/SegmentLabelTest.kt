package com.martinhammer.tickdroid.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The setup screen's segmented labels stay inside their segment at phone widths and large font
 * sizes, also beside the selected segment's check mark. SegmentedButton measures a label at the
 * segment's full width and only then puts the mark before it, so a long selected word used to run
 * under the button's edge. Whether a word shrinks or ellipsizes is checked by eye (screenshots).
 */
class SegmentLabelTest {

    @get:Rule val rule = createComposeRule()

    private val padding = SegmentedButtonDefaults.ContentPadding

    /** Rounding between dp and pixels. */
    private val Slack = 0.5.dp

    private data class Case(val widthDp: Int, val fontScale: Float, val percent: Int)

    @Test fun backgroundLabels_stayInsideTheirSegment() {
        val case = mutableStateOf(Case(360, 1f, 100))
        rule.setContent {
            val (width, scale, percent) = case.value
            // Linear scaling, harsher than Android 14's non-linear curve at the large end.
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                Box(Modifier.width(width.dp)) { BackgroundSetting(percent) {} }
            }
        }
        val failures = mutableListOf<String>()
        for (width in listOf(320, 360, 411)) for (scale in listOf(1f, 1.3f, 2f)) for (percent in listOf(100, 50, 0)) {
            case.value = Case(width, scale, percent)
            rule.waitForIdle()
            for (label in listOf("Solid", "Translucent", "Transparent")) {
                // Merged, the segment (it merges its label); unmerged, the label's own text node.
                val segment = rule.onNode(hasText(label)).getUnclippedBoundsInRoot()
                val text = rule.onNode(hasText(label), useUnmergedTree = true).getUnclippedBoundsInRoot()
                // Inside the content padding, not just the segment: past it the word reaches
                // the outline and, on an end segment, the rounded corner.
                val start = segment.left + padding.calculateStartPadding(LayoutDirection.Ltr)
                val end = segment.right - padding.calculateEndPadding(LayoutDirection.Ltr)
                if (text.left < start - Slack || text.right > end + Slack) {
                    failures += "${width}dp ×$scale selected=$percent: \"$label\" at ${text.left}..${text.right}, room $start..$end"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
