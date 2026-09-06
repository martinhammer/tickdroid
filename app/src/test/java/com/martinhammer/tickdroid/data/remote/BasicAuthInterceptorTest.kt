package com.martinhammer.tickdroid.data.remote

import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.auth.Credentials
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BasicAuthInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var authRepo: AuthRepository

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        authRepo = mockk(relaxed = true)
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun client() = OkHttpClient.Builder()
        .addInterceptor(BasicAuthInterceptor(authRepo))
        .build()

    @Test fun `signed-out requests pass through unmodified`() {
        every { authRepo.currentCredentials() } returns null
        server.enqueue(MockResponse().setResponseCode(200))

        client().newCall(Request.Builder().url(server.url("/p")).build()).execute().close()

        val recorded = server.takeRequest()
        assertEquals(null, recorded.getHeader("Authorization"))
    }

    @Test fun `rewrites host and adds basic auth header`() {
        val creds = Credentials(
            serverUrl = "http://${server.hostName}:${server.port}",
            login = "alice",
            appPassword = "wonderland",
        )
        every { authRepo.currentCredentials() } returns creds
        server.enqueue(MockResponse().setResponseCode(200))

        // Retrofit baseUrl uses a placeholder host; the interceptor must rewrite it.
        client().newCall(Request.Builder().url("http://localhost/api/tracks").build())
            .execute()
            .close()

        val recorded = server.takeRequest()
        assertEquals(creds.basicAuthHeader, recorded.getHeader("Authorization"))
        // MockWebServer reports the path it received — proves the rewrite worked.
        assertEquals("/api/tracks", recorded.path)
    }

    @Test fun `preserves a subpath origin on every request`() {
        // Regression: the interceptor used to set only scheme/host/port, so a Nextcloud hosted
        // under a subpath lost it on every API call. AuthProber never had the bug (it uses
        // addPathSegments), so such a server passed the connect probe and then failed on every
        // real request — in a server-dependent way, depending on what answers at the root.
        val creds = Credentials(
            serverUrl = "http://${server.hostName}:${server.port}/nextcloud",
            login = "alice",
            appPassword = "wonderland",
        )
        every { authRepo.currentCredentials() } returns creds
        server.enqueue(MockResponse().setResponseCode(200))

        client().newCall(Request.Builder().url("http://localhost/ocs/v2.php/apps/tickbuddy/api/tracks").build())
            .execute()
            .close()

        assertEquals("/nextcloud/ocs/v2.php/apps/tickbuddy/api/tracks", server.takeRequest().path)
    }

    @Test fun `subpath origin keeps query parameters`() {
        val creds = Credentials("http://${server.hostName}:${server.port}/nc", "alice", "pw")
        every { authRepo.currentCredentials() } returns creds
        server.enqueue(MockResponse().setResponseCode(200))

        client().newCall(
            Request.Builder().url("http://localhost/ocs/v2.php/apps/tickbuddy/api/ticks?from=2026-04-01&to=2026-04-30").build()
        ).execute().close()

        assertEquals(
            "/nc/ocs/v2.php/apps/tickbuddy/api/ticks?from=2026-04-01&to=2026-04-30",
            server.takeRequest().path,
        )
    }

    @Test fun `a trailing slash on the stored origin does not double up`() {
        // ServerUrl.normalize strips trailing slashes, but a stored value from an older install
        // could still carry one — it must not produce "/nextcloud//ocs/...".
        val creds = Credentials("http://${server.hostName}:${server.port}/nextcloud/", "alice", "pw")
        every { authRepo.currentCredentials() } returns creds
        server.enqueue(MockResponse().setResponseCode(200))

        client().newCall(Request.Builder().url("http://localhost/ocs/v2.php/cloud/user").build())
            .execute()
            .close()

        assertEquals("/nextcloud/ocs/v2.php/cloud/user", server.takeRequest().path)
    }

    @Test fun `401 triggers signOut`() {
        val creds = Credentials("http://${server.hostName}:${server.port}", "alice", "pw")
        every { authRepo.currentCredentials() } returns creds
        server.enqueue(MockResponse().setResponseCode(401))

        client().newCall(Request.Builder().url("http://localhost/api/tracks").build())
            .execute()
            .close()

        verify(exactly = 1) { authRepo.signOut() }
    }

    @Test fun `non-401 does not trigger signOut`() {
        val creds = Credentials("http://${server.hostName}:${server.port}", "alice", "pw")
        every { authRepo.currentCredentials() } returns creds
        server.enqueue(MockResponse().setResponseCode(500))

        client().newCall(Request.Builder().url("http://localhost/api/tracks").build())
            .execute()
            .close()

        verify(exactly = 0) { authRepo.signOut() }
    }
}
