package com.martinhammer.tickdroid.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class HelpSheetTest {

    private val tracks = "Add and manage tracks in Tracks settings. They're shared with Tickbuddy on your Nextcloud."
    private val cells = "Tap a cell to toggle a yes/no value or increment a counter; long-press to decrement a counter value."
    private val settings = "Editable days, show/hide private tracks, and UI options are configured in App settings."
    private val colours = "Custom track colours and heading icons can be configured in Tracks settings."

    @Test fun `journal with notes`() {
        assertEquals(
            listOf(tracks, cells, "Tap a day to open it on Today, where you can read and write its notes.", settings, colours),
            journalHelpParagraphs(notes = true),
        )
    }

    @Test fun `today with notes`() {
        assertEquals(
            listOf(tracks, cells, "Tap a track's name to add or change its note for the day.", settings, colours),
            todayHelpParagraphs(notes = true),
        )
    }

    @Test fun `without notes both sheets say the same`() {
        assertEquals(listOf(tracks, cells, settings, colours), journalHelpParagraphs(notes = false))
        assertEquals(journalHelpParagraphs(notes = false), todayHelpParagraphs(notes = false))
    }

    @Test fun `one title for both`() {
        assertEquals("How to use Tickdroid", HelpTitle)
    }
}
