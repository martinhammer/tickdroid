package com.martinhammer.tickdroid.data.auth

import com.martinhammer.tickdroid.data.remote.OcsHeadersInterceptor
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthProberTest {

    private lateinit var server: MockWebServer
    private lateinit var prober: AuthProber

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        prober = AuthProber(OcsHeadersInterceptor())
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun creds(): Credentials = Credentials(
        serverUrl = "http://${server.hostName}:${server.port}",
        login = "alice",
        appPassword = "pw",
    )

    private val nextcloudStatus =
        """{"installed":true,"productname":"Nextcloud","version":"30.0.0"}"""

    @Test fun `invalid url short-circuits`() = runTest {
        val result = prober.probe(Credentials("not a url", "u", "p"))
        assertEquals(AuthProbeResult.InvalidUrl, result)
    }

    @Test fun `status non-200 maps to NotNextcloud`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = prober.probe(creds())
        assertEquals(AuthProbeResult.NotNextcloud, result)
    }

    @Test fun `status 200 without Nextcloud payload maps to NotNextcloud`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"product":"other"}"""))
        val result = prober.probe(creds())
        assertEquals(AuthProbeResult.NotNextcloud, result)
    }

    @Test fun `auth 401 maps to Unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(nextcloudStatus))
        server.enqueue(MockResponse().setResponseCode(401))
        val result = prober.probe(creds())
        assertEquals(AuthProbeResult.Unauthorized, result)
    }

    @Test fun `tickbuddy 404 maps to TickbuddyNotInstalled`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(nextcloudStatus))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(404))
        val result = prober.probe(creds())
        assertEquals(AuthProbeResult.TickbuddyNotInstalled, result)
    }

    @Test fun `all stages green maps to Success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(nextcloudStatus))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val result = prober.probe(creds())
        assertEquals(AuthProbeResult.Success, result)
    }

    @Test fun `unexpected auth status surfaces stage and code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(nextcloudStatus))
        server.enqueue(MockResponse().setResponseCode(503))
        val result = prober.probe(creds())
        assertTrue(result is AuthProbeResult.UnexpectedStatus)
        result as AuthProbeResult.UnexpectedStatus
        assertEquals(AuthProbeResult.Stage.Auth, result.stage)
        assertEquals(503, result.code)
    }

    @Test fun `unreachable host maps to Unreachable`() = runTest {
        server.shutdown() // force connection refused
        val result = prober.probe(creds())
        assertTrue(result is AuthProbeResult.Unreachable)
    }
}
