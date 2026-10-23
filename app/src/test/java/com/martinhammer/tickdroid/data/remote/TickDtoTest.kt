package com.martinhammer.tickdroid.data.remote

import com.martinhammer.tickdroid.data.remote.dto.TickDto
import com.martinhammer.tickdroid.data.remote.dto.TickYesNoResponse
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class TickDtoTest {

    // As NetworkModule's shared Json.
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun valueOf(raw: String): Int =
        json.decodeFromString(TickDto.serializer(), """{"id":1,"trackId":2,"date":"2026-10-21","value":$raw}""").value

    @Test fun `integers come through as they are`() {
        assertEquals(7, valueOf("7"))
        assertEquals(0, valueOf("0"))
    }

    @Test fun `anything else is 1 when non-zero or non-empty, else 0`() {
        val expected = mapOf(
            "7.5" to 1, "0.0" to 0, "-2.5" to 1,
            "\"red\"" to 1, "\"\"" to 0,
            "true" to 1, "false" to 0,
            "null" to 0, "{}" to 1, "[1]" to 1,
        )
        expected.forEach { (raw, value) -> assertEquals("value $raw", value, valueOf(raw)) }
    }

    @Test fun `one odd value doesn't fail the rest of the list`() {
        val ticks = json.decodeFromString(
            ListSerializer(TickDto.serializer()),
            """[{"id":1,"trackId":2,"date":"2026-10-20","value":3},
                {"id":2,"trackId":9,"date":"2026-10-20","value":"red"},
                {"id":3,"trackId":2,"date":"2026-10-21","value":1}]""",
        )
        assertEquals(listOf(3, 1, 1), ticks.map { it.value })
    }

    @Test fun `set's yes-no answer reads 1 and 0 as well as true and false`() {
        val expected = mapOf("1" to 1, "0" to 0, "true" to 1, "false" to 0)
        expected.forEach { (raw, value) ->
            assertEquals("value $raw", value, json.decodeFromString(TickYesNoResponse.serializer(), """{"value":$raw}""").value)
        }
    }
}
