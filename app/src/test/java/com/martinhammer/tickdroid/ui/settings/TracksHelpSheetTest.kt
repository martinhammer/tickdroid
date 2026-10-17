package com.martinhammer.tickdroid.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TracksHelpSheetTest {

    private val offline = "The following actions are not available while you're offline: add, rename, " +
        "toggle private, reorder, archive, restore and delete tracks."

    @Test fun `the archive paragraph only with the flag, after the private one`() {
        val with = tracksHelpParagraphs(archiveSupported = true)
        val archive = with.indexOfFirst { it.startsWith("Archived tracks") }
        assertEquals(with.indexOfFirst { it.startsWith("Private tracks") } + 1, archive)
        assertTrue(tracksHelpParagraphs(archiveSupported = false).none { it.startsWith("Archived tracks") })
    }

    @Test fun `the offline sentence includes archive and restore either way`() {
        assertEquals(offline, tracksHelpParagraphs(archiveSupported = true).last())
        assertEquals(offline, tracksHelpParagraphs(archiveSupported = false).last())
    }
}
