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

    // --- reason ---

    private fun reasonBody(data: String) =
        """{"ocs":{"meta":{"status":"failure","statuscode":423,"message":""},"data":$data}}"""

    @Test fun `reason is read from ocs data`() {
        assertEquals(
            "archived",
            ocsErrorReason(reasonBody("""{"message":"Exercise is archived","reason":"archived"}"""), json),
        )
    }

    @Test fun `missing, blank or non-string reason gives null`() {
        assertNull(ocsErrorReason(reasonBody("[]"), json))
        assertNull(ocsErrorReason(reasonBody("""{"message":"Track not found"}"""), json))
        assertNull(ocsErrorReason(reasonBody("""{"reason":"  "}"""), json))
        assertNull(ocsErrorReason(reasonBody("""{"reason":404}"""), json))
        assertNull(ocsErrorReason(reasonBody("""{"reason":null}"""), json))
        assertNull(ocsErrorReason(reasonBody("""{"reason":{"code":"archived"}}"""), json))
    }

    @Test fun `reason in meta is not a Tickbuddy reason`() {
        assertNull(
            ocsErrorReason("""{"ocs":{"meta":{"status":"failure","reason":"x"},"data":[]}}""", json),
        )
    }

    @Test fun `html and empty bodies give no reason`() {
        assertNull(ocsErrorReason("<html><body>404 Not Found</body></html>", json))
        assertNull(ocsErrorReason("", json))
        assertNull(ocsErrorReason(null, json))
    }
}
