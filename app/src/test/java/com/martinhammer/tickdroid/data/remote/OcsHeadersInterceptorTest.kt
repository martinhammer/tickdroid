package com.martinhammer.tickdroid.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
