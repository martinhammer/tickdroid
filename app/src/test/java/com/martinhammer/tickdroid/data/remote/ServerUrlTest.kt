package com.martinhammer.tickdroid.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlTest {

    @Test fun `accepts https url`() {
        assertEquals("https://cloud.example.com", ServerUrl.normalize("https://cloud.example.com"))
    }

    @Test fun `strips trailing slash`() {
        assertEquals("https://cloud.example.com", ServerUrl.normalize("https://cloud.example.com/"))
    }

    @Test fun `strips trailing whitespace`() {
        assertEquals("https://cloud.example.com", ServerUrl.normalize("  https://cloud.example.com  "))
    }

    @Test fun `preserves subpath`() {
        assertEquals(
            "https://cloud.example.com/nextcloud",
            ServerUrl.normalize("https://cloud.example.com/nextcloud/"),
        )
    }

    @Test fun `preserves nonstandard port`() {
        assertEquals("https://cloud.example.com:8443", ServerUrl.normalize("https://cloud.example.com:8443"))
    }

    @Test fun `rejects http scheme`() {
        assertNull(ServerUrl.normalize("http://cloud.example.com"))
    }

    @Test fun `rejects schemeless host`() {
        assertNull(ServerUrl.normalize("cloud.example.com"))
    }

    @Test fun `rejects empty input`() {
        assertNull(ServerUrl.normalize(""))
        assertNull(ServerUrl.normalize("   "))
    }

    @Test fun `rejects malformed input`() {
        assertNull(ServerUrl.normalize("https://"))
        assertNull(ServerUrl.normalize("not a url"))
    }
}
