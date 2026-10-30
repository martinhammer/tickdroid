package com.martinhammer.tickdroid.ui.today

import androidx.compose.ui.graphics.Color
import com.martinhammer.tickdroid.domain.TrackColor
import com.martinhammer.tickdroid.domain.TrackRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TodayTextTest {

    private val today = LocalDate.of(2026, 10, 28)

    @Test fun `dayKind by how far back the day is`() {
        assertEquals(DayKind.Today, dayKind(today, today))
        assertEquals(DayKind.Yesterday, dayKind(today.minusDays(1), today))
        assertEquals(DayKind.DaysAgo(2), dayKind(today.minusDays(2), today))
        assertEquals(DayKind.DaysAgo(6), dayKind(today.minusDays(6), today))
        assertEquals(DayKind.Date(titleHasYear = false, lineHasYear = true), dayKind(today.minusDays(7), today))
        assertEquals(DayKind.Date(titleHasYear = true, lineHasYear = true), dayKind(LocalDate.of(2025, 10, 21), today))
    }

    @Test fun `runText in Tickcli's words`() {
        assertEquals("3-day streak", runText(TrackRun(isStreak = true, length = 3, upToYesterday = false, maxValue = 0)))
        assertEquals(
            "1-day streak, up to yesterday",
            runText(TrackRun(isStreak = true, length = 1, upToYesterday = true, maxValue = 0)),
        )
        assertEquals("21-day break", runText(TrackRun(isStreak = false, length = 21, upToYesterday = false, maxValue = 0)))
        assertEquals("No ticks yet", runText(TrackRun(isStreak = null, length = null, upToYesterday = false, maxValue = 0)))
        assertNull(runText(null))
    }

    @Test fun `levelFor as the web's`() {
        assertEquals(0, levelFor(0, isCounter = true, maxValue = 7))
        assertEquals(0, levelFor(-1, isCounter = false, maxValue = 0))
        assertEquals(2, levelFor(1, isCounter = false, maxValue = 0))
        // maxValue 0 to 4: the value itself, capped at 4.
        assertEquals(3, levelFor(3, isCounter = true, maxValue = 0))
        assertEquals(4, levelFor(9, isCounter = true, maxValue = 0))
        assertEquals(2, levelFor(2, isCounter = true, maxValue = 4))
        // maxValue 7.
        assertEquals(1, levelFor(1, isCounter = true, maxValue = 7))
        assertEquals(2, levelFor(2, isCounter = true, maxValue = 7))
        assertEquals(3, levelFor(4, isCounter = true, maxValue = 7))
        assertEquals(4, levelFor(7, isCounter = true, maxValue = 7))
        // Never 0 for a tick.
        assertEquals(1, levelFor(1, isCounter = true, maxValue = 100))
    }

    private fun window(day: LocalDate) = squareWindow(today, day)

    @Test fun `squareWindow ends today while the day is in the last seven`() {
        val expected = (0L..6L).map { today.minusDays(it) }
        assertEquals(expected, window(today))
        assertEquals(expected, window(today.minusDays(3)))
        assertEquals(today.minusDays(3), window(today.minusDays(3))[3])
        assertEquals(today.minusDays(6), window(today.minusDays(6)).last())
    }

    @Test fun `further back, the day is the last square and the six after it come first`() {
        val day = today.minusDays(7)
        assertEquals((6L downTo 0L).map { day.plusDays(it) }, window(day))
        val old = today.minusDays(40)
        assertEquals(old.plusDays(6), window(old).first())
        assertEquals(old, window(old).last())
    }

    @Test fun `squareColour - primary without a colour, container in dark, strong in light`() {
        val primary = Color(0xFF6750A4)
        assertEquals(primary, squareColour(null, dark = false, primary = primary))
        assertEquals(primary, squareColour(null, dark = true, primary = primary))
        assertEquals(primary, squareColour("no-such-colour", dark = false, primary = primary))
        assertEquals(TrackColor.BLUE.container, squareColour("blue", dark = true, primary = primary))
        assertEquals(TrackColor.BLUE.strong, squareColour("blue", dark = false, primary = primary))
    }
}
