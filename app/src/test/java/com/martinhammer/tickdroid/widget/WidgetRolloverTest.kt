package com.martinhammer.tickdroid.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class WidgetRolloverTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi, s), zone)

    private fun local(millis: Long, zone: ZoneId): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)

    @Test fun `an ordinary evening rolls over at 00 01 the next day`() {
        val next = nextRolloverMillis(at(berlin, 2026, 10, 5, 21, 30))
        assertEquals(LocalDateTime.of(2026, 10, 6, 0, 1), local(next, berlin))
    }

    @Test fun `a second before midnight still targets the coming midnight`() {
        val next = nextRolloverMillis(at(berlin, 2026, 10, 5, 23, 59, 59))
        assertEquals(LocalDateTime.of(2026, 10, 6, 0, 1), local(next, berlin))
    }

    @Test fun `just after midnight targets the next midnight, not this one`() {
        val next = nextRolloverMillis(at(berlin, 2026, 10, 6, 0, 0, 30))
        assertEquals(LocalDateTime.of(2026, 10, 7, 0, 1), local(next, berlin))
    }

    @Test fun `the night before spring-forward (missing hour)`() {
        // Berlin: 2026-03-29 02:00 -> 03:00. Midnight itself exists; the day is 23 hours.
        val now = at(berlin, 2026, 3, 28, 22, 0)
        val next = nextRolloverMillis(now)
        assertEquals(LocalDateTime.of(2026, 3, 29, 0, 1), local(next, berlin))
        val onTheDay = nextRolloverMillis(at(berlin, 2026, 3, 29, 12, 0))
        assertEquals(LocalDateTime.of(2026, 3, 30, 0, 1), local(onTheDay, berlin))
        assertEquals(23L * 60 * 60 * 1000, onTheDay - next)
    }

    @Test fun `the day with a doubled hour is 25 hours long`() {
        // Berlin: 2026-10-25 03:00 -> 02:00.
        val start = nextRolloverMillis(at(berlin, 2026, 10, 24, 20, 0))
        val end = nextRolloverMillis(at(berlin, 2026, 10, 25, 20, 0))
        assertEquals(LocalDateTime.of(2026, 10, 26, 0, 1), local(end, berlin))
        assertEquals(25L * 60 * 60 * 1000, end - start)
    }

    @Test fun `a zone whose day starts at 01 00 because of DST`() {
        // America/Santiago skips 00:00-01:00 on its spring-forward day (2026-09-06).
        val santiago = ZoneId.of("America/Santiago")
        val next = nextRolloverMillis(at(santiago, 2026, 9, 5, 22, 0))
        assertEquals(LocalDateTime.of(2026, 9, 6, 1, 1), local(next, santiago))
    }
}
