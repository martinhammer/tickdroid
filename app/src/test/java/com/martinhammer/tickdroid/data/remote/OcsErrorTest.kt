package com.martinhammer.tickdroid.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcsErrorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun body(dataMessage: String?, metaMessage: String?, data: String? = null): String {
        val d = data ?: (dataMessage?.let { """{"message":"$it"}""" } ?: "{}")
        val m = metaMessage?.let { ""","message":"$it"""" } ?: ""
        return """{"ocs":{"meta":{"status":"failure","statuscode":403$m},"data":$d}}"""
    }

    @Test fun `data message wins`() {
        assertEquals(
            "Maximum of 99 tracks reached",
            ocsErrorMessage(body("Maximum of 99 tracks reached", "Forbidden"), json),
        )
    }

    @Test fun `empty data message falls back to meta message`() {
        assertEquals("Track not found", ocsErrorMessage(body("", "Track not found"), json))
    }

    @Test fun `array data with a meta message`() {
        assertEquals("Not allowed", ocsErrorMessage(body(null, "Not allowed", data = "[]"), json))
    }

    @Test fun `both blank gives null`() {
        assertNull(ocsErrorMessage(body("  ", ""), json))
        assertNull(ocsErrorMessage(body(null, null), json))
    }

    @Test fun `html and empty bodies give null`() {
        assertNull(ocsErrorMessage("<html><body>502 Bad Gateway</body></html>", json))
        assertNull(ocsErrorMessage("", json))
        assertNull(ocsErrorMessage(null, json))
        assertNull(ocsErrorMessage("""{"error":"x"}""", json))
    }
}
