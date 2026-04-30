package com.martinhammer.tickdroid.data.remote

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class OcsEnvelopeTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun `decodes envelope with list payload`() {
        val payload = """
            {"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},"data":[{"k":1},{"k":2}]}}
        """.trimIndent()
        val parsed = json.decodeFromString(
            OcsEnvelope.serializer(ListSerializer(ItemDto.serializer())),
            payload,
        )
        assertEquals(200, parsed.ocs.meta.statusCode)
        assertEquals("ok", parsed.ocs.meta.status)
        assertEquals(listOf(ItemDto(1), ItemDto(2)), parsed.ocs.data)
    }

    @Test fun `decodes envelope with object payload`() {
        val payload = """
            {"ocs":{"meta":{"status":"ok","statuscode":200},"data":{"k":7}}}
        """.trimIndent()
        val parsed = json.decodeFromString(
            OcsEnvelope.serializer(ItemDto.serializer()),
            payload,
        )
        assertEquals(200, parsed.ocs.meta.statusCode)
        assertEquals(7, parsed.ocs.data.k)
    }

    @Test fun `decodes meta with no message`() {
        val payload = """{"ocs":{"meta":{"status":"failure","statuscode":401},"data":[]}}"""
        val parsed = json.decodeFromString(
            OcsEnvelope.serializer(ListSerializer(ItemDto.serializer())),
            payload,
        )
        assertEquals(401, parsed.ocs.meta.statusCode)
        assertEquals("failure", parsed.ocs.meta.status)
        assertEquals(null, parsed.ocs.meta.message)
    }

    @Test fun `ignores unknown keys`() {
        val payload = """
            {"ocs":{"meta":{"status":"ok","statuscode":200,"future":"thing"},"data":{"k":3,"extra":42}}}
        """.trimIndent()
        val parsed = json.decodeFromString(
            OcsEnvelope.serializer(ItemDto.serializer()),
            payload,
        )
        assertEquals(3, parsed.ocs.data.k)
    }

    @kotlinx.serialization.Serializable
    data class ItemDto(val k: Int)
}
