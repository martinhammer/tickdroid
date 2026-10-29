package com.martinhammer.tickdroid.ui.today

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayHelpSheetTest {

    @Test fun `six paragraphs in order with both flags`() {
        val paragraphs = todayHelpParagraphs(notes = true, stats = true)
        assertEquals(6, paragraphs.size)
        assertTrue(paragraphs[0].startsWith("Today shows your tracks for one day."))
        assertTrue(paragraphs[1].startsWith("Tap a cell"))
        assertTrue(paragraphs[2].startsWith("The squares show a week"))
        assertTrue(paragraphs[3].startsWith("The text after them"))
        assertTrue(paragraphs[4].startsWith("Tap a track's name"))
        assertEquals("Days you can change are set in App settings.", paragraphs[5])
    }

    @Test fun `no streak paragraph without stats`() {
        val paragraphs = todayHelpParagraphs(notes = true, stats = false)
        assertEquals(5, paragraphs.size)
        assertFalse(paragraphs.any { it.contains("streak") })
    }

    @Test fun `no notes paragraph without notes`() {
        val paragraphs = todayHelpParagraphs(notes = false, stats = true)
        assertEquals(5, paragraphs.size)
        assertFalse(paragraphs.any { it.contains("note", ignoreCase = true) })
    }
}
