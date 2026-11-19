package com.martinhammer.tickdroid.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TracksHelpSheetTest {

    private val offline = "Changes to tracks that are synced to your server can't be made while you're offline."

    @Test fun `the archive paragraph only with the flag, after the private one`() {
        val with = tracksHelpParagraphs(archiveSupported = true)
        val archive = with.indexOfFirst { it.startsWith("Archived tracks") }
        assertEquals(with.indexOfFirst { it.startsWith("Private tracks") } + 1, archive)
        assertTrue(tracksHelpParagraphs(archiveSupported = false).none { it.startsWith("Archived tracks") })
    }

    @Test fun `the offline sentence is the same either way`() {
        assertEquals(offline, tracksHelpParagraphs(archiveSupported = true).last())
        assertEquals(offline, tracksHelpParagraphs(archiveSupported = false).last())
    }

    @Test fun `the synced sentence names type settings`() {
        assertEquals(
            "Name, type, type settings, private status and track order are synced to Tickbuddy on your Nextcloud server.",
            tracksHelpParagraphs(archiveSupported = false).first(),
        )
    }
}
