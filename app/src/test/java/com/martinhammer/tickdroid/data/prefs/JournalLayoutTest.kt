package com.martinhammer.tickdroid.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class JournalLayoutTest {

    @Test fun `missing or unknown names fall back to DAYS_DOWN`() {
        assertEquals(JournalLayout.DAYS_DOWN, JournalLayout.fromName(null))
        assertEquals(JournalLayout.DAYS_DOWN, JournalLayout.fromName("bogus"))
        assertEquals(JournalLayout.DAYS_DOWN, JournalLayout.fromName(""))
    }

    @Test fun `every value round-trips through its name`() {
        JournalLayout.values().forEach { assertEquals(it, JournalLayout.fromName(it.name)) }
    }
}
