package com.martinhammer.tickdroid.data.remote

import com.martinhammer.tickdroid.data.remote.dto.NoteDto
import com.martinhammer.tickdroid.data.remote.dto.NoteTextResponse
import com.martinhammer.tickdroid.data.remote.dto.StatsDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteStatsDtoTest {

    // As NetworkModule's shared Json.
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun `notes decode, line breaks and all`() {
        val notes = json.decodeFromString(
            ListSerializer(NoteDto.serializer()),
            """[{"trackId":3,"date":"2026-10-28","text":"5k along the river\nnew shoes"},
                {"trackId":4,"date":"2026-10-27","text":"Guided","extra":1}]""",
        )
        assertEquals(NoteDto(3, "2026-10-28", "5k along the river\nnew shoes"), notes[0])
        assertEquals(NoteDto(4, "2026-10-27", "Guided"), notes[1])
    }

    @Test fun `set and append answer the stored text`() {
        assertEquals("a\nb", json.decodeFromString(NoteTextResponse.serializer(), """{"text":"a\nb"}""").text)
        assertEquals("", json.decodeFromString(NoteTextResponse.serializer(), """{"text":""}""").text)
    }

    @Test fun `a stats entry keeps only the run, upToYesterday and maxValue`() {
        val stats = json.decodeFromString(
            ListSerializer(StatsDto.serializer()),
            """[{"trackId":1,"total":40,"weeklyMean":2.5,"last7":3,"previous7":2,"peak7":7,
                 "current":{"isStreak":true,"length":3,"from":"2026-10-26","to":"2026-10-28"},
                 "upToYesterday":false,"longestStreak":null,"longestBreak":null,
                 "firstTick":"2025-01-01","lastTick":"2026-10-28","maxValue":7,"minValue":1},
                {"trackId":2,"total":0,"current":null,"upToYesterday":false,"maxValue":0,"minValue":0}]""",
        )
        val first = stats[0]
        assertEquals(1L, first.trackId)
        assertTrue(first.current!!.isStreak)
        assertEquals(3, first.current!!.length)
        assertFalse(first.upToYesterday)
        assertEquals(7, first.maxValue)
        assertNull(stats[1].current)
    }

    @Test fun `a missing maxValue reads 0`() {
        val entry = json.decodeFromString(StatsDto.serializer(), """{"trackId":5,"current":{"isStreak":false,"length":21}}""")
        assertEquals(0, entry.maxValue)
        assertFalse(entry.current!!.isStreak)
    }
}
