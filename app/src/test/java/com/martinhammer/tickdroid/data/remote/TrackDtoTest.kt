package com.martinhammer.tickdroid.data.remote

import com.martinhammer.tickdroid.data.remote.dto.TrackDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackDtoTest {

    // As NetworkModule's shared Json.
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun `decodes archivedAt from a server with archiving`() {
        val dto = json.decodeFromString(
            TrackDto.serializer(),
            """{"id":1,"name":"Smoking","type":"boolean","sortOrder":2,"private":false,"archivedAt":"2026-08-14"}""",
        )
        assertEquals("2026-08-14", dto.archivedAt)
    }

    @Test fun `null archivedAt means active`() {
        val dto = json.decodeFromString(
            TrackDto.serializer(),
            """{"id":1,"name":"Gym","type":"boolean","sortOrder":2,"private":false,"archivedAt":null}""",
        )
        assertNull(dto.archivedAt)
    }

    @Test fun `a server before archiving still parses`() {
        val dto = json.decodeFromString(
            TrackDto.serializer(),
            """{"id":1,"name":"Gym","type":"counter","sortOrder":2,"private":true}""",
        )
        assertNull(dto.archivedAt)
        assertEquals("Gym", dto.name)
    }
}
