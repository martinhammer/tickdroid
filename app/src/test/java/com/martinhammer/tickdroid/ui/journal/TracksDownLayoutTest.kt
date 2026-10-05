package com.martinhammer.tickdroid.ui.journal

import com.martinhammer.tickdroid.data.prefs.GridDensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TracksDownLayoutTest {

    private val today = LocalDate.parse("2026-10-02")
    private val defaultWindow = DateWindow(oldestVisible = today.minusDays(29), today = today)

    private fun assertMetrics(screen: Float, maxDays: Int, days: Int, slot: Float, cell: Float) {
        val m = tracksDownMetrics(screen, maxDays)
        assertEquals("days @ $screen/$maxDays", days, m.daysVisible)
        assertEquals("slot @ $screen/$maxDays", slot, m.slotDp, 0.1f)
        assertEquals("cell @ $screen/$maxDays", cell, m.cellDp, 0.1f)
    }

    @Test fun `days visible by density`() {
        assertEquals(5, GridDensity.LOW.tracksDownDays)
        assertEquals(6, GridDensity.MEDIUM.tracksDownDays)
        assertEquals(8, GridDensity.HIGH.tracksDownDays)
    }

    @Test fun `metrics match the reference table`() {
        assertMetrics(360f, 5, days = 5, slot = 42.9f, cell = 36.9f)
        assertMetrics(360f, 6, days = 6, slot = 35.7f, cell = 29.7f)
        assertMetrics(360f, 8, days = 6, slot = 35.7f, cell = 29.7f)
        // 411dp = Pixel 6a: Medium shows 6 days with cells at their Days-down size (~35dp).
        assertMetrics(411f, 5, days = 5, slot = 49.4f, cell = 43.4f)
        assertMetrics(411f, 6, days = 6, slot = 41.2f, cell = 35.2f)
        assertMetrics(411f, 8, days = 7, slot = 35.3f, cell = 29.3f)
        // Landscape: cells stay at the 480dp cap's size and the extra width becomes extra days.
        assertMetrics(800f, 5, days = 10, slot = 58.2f, cell = 52.2f)
        assertMetrics(800f, 6, days = 12, slot = 48.5f, cell = 42.5f)
        assertMetrics(800f, 8, days = 16, slot = 36.4f, cell = 30.4f)
    }

    @Test fun `beyond the cap the slot size is the cap's and days grow with width`() {
        listOf(5, 6, 8).forEach { maxDays ->
            val atCap = tracksDownMetrics(GridSizingMaxWidthDp, maxDays)
            var previousDays = atCap.daysVisible
            listOf(481f, 600f, 867f, 1200f).forEach { width ->
                val m = tracksDownMetrics(width, maxDays)
                assertEquals(atCap.slotDp, m.slotDp, 0.01f)
                assertEquals(atCap.cellDp, m.cellDp, 0.01f)
                assertTrue(m.daysVisible >= previousDays)
                previousDays = m.daysVisible
                // The label column never drops below the share it has at the cap.
                val label = width - m.slotDp * m.daysVisible - RightPadDp
                assertTrue(label >= GridSizingMaxWidthDp * NameColumnFraction - 0.01f)
            }
        }
    }

    @Test fun `label column keeps its share of the width`() {
        val m = tracksDownMetrics(411f, 6)
        val label = 411f - m.slotDp * m.daysVisible - RightPadDp
        assertEquals(411f * NameColumnFraction, label, 0.1f)
    }

    @Test fun `metrics stay within bounds on a narrow screen`() {
        GridDensity.values().map { it.tracksDownDays }.forEach { maxDays ->
            val m = tracksDownMetrics(280f, maxDays)
            assertTrue(m.daysVisible >= MinDaysVisible)
            assertTrue(m.cellDp in MinCellSizeDp..MaxCellSizeDp)
        }
    }

    @Test fun `column 0 is today and higher columns are older`() {
        assertEquals(today, dayForColumn(0, today))
        assertEquals(today.minusDays(3), dayForColumn(3, today))
    }

    @Test fun `days in view run newest first from the leftmost column`() {
        assertEquals((2L..7L).map { today.minusDays(it) }, daysInView(2, 6, today))
    }

    @Test fun `column count covers the whole loaded window`() {
        assertEquals(30, columnCount(defaultWindow))
        assertEquals(1, columnCount(DateWindow(oldestVisible = today, today = today)))
    }

    // Width measured in characters: a line fits if it has at most 12 of them.
    private val fits12: (String) -> Boolean = { it.length <= 12 }

    @Test fun `splitLabel keeps a fitting name on one line`() {
        assertEquals(listOf("Read a book"), splitLabel("Read a book", fits12))
    }

    @Test fun `splitLabel wraps at word boundaries`() {
        assertEquals(listOf("Made someone", "smile today"), splitLabel("Made someone smile today", fits12))
    }

    @Test fun `splitLabel never breaks a single word that does not fit`() {
        assertEquals(listOf("Supercalifragilistic"), splitLabel("Supercalifragilistic", fits12))
        assertEquals(listOf("Supercalifragilistic", "word"), splitLabel("Supercalifragilistic word", fits12))
        assertEquals(listOf("Read", "Supercalifragilistic"), splitLabel("Read Supercalifragilistic", fits12))
    }

    @Test fun `splitLabel collapses whitespace`() {
        assertEquals(listOf("Read a book"), splitLabel("  Read   a book ", fits12))
    }
}
