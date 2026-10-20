package com.martinhammer.tickdroid.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class OcsHeadersInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder()
            .addInterceptor(OcsHeadersInterceptor())
            .build()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `adds OCS-APIRequest and Accept headers`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.newCall(Request.Builder().url(server.url("/anything")).build())
            .execute()
            .close()

        val recorded = server.takeRequest()
        assertEquals("true", recorded.getHeader("OCS-APIRequest"))
        assertEquals("application/json", recorded.getHeader("Accept"))
    }

    private fun get(path: String) = run {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.newCall(Request.Builder().url(server.url(path)).build()).execute().close()
        server.takeRequest()
    }

    @Test fun `asks Tickbuddy's API for strict handling`() {
        for (path in listOf(
            "/ocs/v2.php/apps/tickbuddy/api/ticks/set",
            "/nextcloud/ocs/v2.php/apps/tickbuddy/api/tracks",
        )) {
            val recorded = get(path)
            assertEquals(path, "handling=strict", recorded.getHeader("Prefer"))
            assertEquals(path, "true", recorded.getHeader("OCS-APIRequest"))
            assertEquals(path, "application/json", recorded.getHeader("Accept"))
        }
    }

    @Test fun `no Prefer outside Tickbuddy's API`() {
        for (path in listOf("/ocs/v2.php/cloud/capabilities", "/ocs/v2.php/cloud/user", "/anything")) {
            val recorded = get(path)
            assertNull(path, recorded.getHeader("Prefer"))
            assertEquals(path, "true", recorded.getHeader("OCS-APIRequest"))
            assertEquals(path, "application/json", recorded.getHeader("Accept"))
        }
    }

    @Test fun `does not strip caller-provided headers`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.newCall(
            Request.Builder()
                .url(server.url("/anything"))
                .header("Authorization", "Basic abc")
                .build(),
        ).execute().close()

        val recorded = server.takeRequest()
        assertEquals("Basic abc", recorded.getHeader("Authorization"))
        assertEquals("true", recorded.getHeader("OCS-APIRequest"))
    }
}
