package com.martinhammer.tickdroid.ui.today

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** ICU and the platform's date patterns only exist on a device, so these run instrumented. */
class DayTitleTest {

    private val today = LocalDate.of(2026, 10, 28) // a Wednesday

    @Test fun titlesInBritishEnglish() {
        val uk = Locale.UK
        assertEquals("Today", dayTitle(today, today, uk))
        assertEquals("Yesterday", dayTitle(today.minusDays(1), today, uk))
        assertEquals("3 days ago", dayTitle(today.minusDays(3), today, uk))
        assertEquals("21 October", dayTitle(LocalDate.of(2026, 10, 21), today, uk))
        assertEquals("21 October 2025", dayTitle(LocalDate.of(2025, 10, 21), today, uk))
    }

    @Test fun linesInBritishEnglish() {
        val uk = Locale.UK
        assertEquals("Wednesday 28 October", dayLine(today, today, uk))
        assertEquals("Monday 26 October", dayLine(today.minusDays(2), today, uk))
        // The comma is the locale's own pattern for a full date with the year.
        assertEquals("Wednesday, 21 October 2026", dayLine(today.minusDays(7), today, uk))
    }

    @Test fun relativeTitlesFollowThePhonesLanguage_capitalised() {
        assertEquals("Gestern", dayTitle(today.minusDays(1), today, Locale.GERMANY))
    }
}
