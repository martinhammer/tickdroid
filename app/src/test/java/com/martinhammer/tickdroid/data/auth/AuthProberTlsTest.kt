package com.martinhammer.tickdroid.data.auth

import com.martinhammer.tickdroid.data.remote.OcsHeadersInterceptor
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * The TLS half of [AuthProber]'s error classification.
 *
 * A handshake failure is an [java.io.IOException] subtype, so before the SSLException catch
 * clauses it was swallowed by the generic "could not reach the server" branch and the user was
 * told to check their URL and their network — neither of which is the problem. See issue #37.
 *
 * These run against a real TLS socket with a certificate signed by a CA the client doesn't know,
 * which is exactly the private-CA situation reported in #37.
 *
 * What this file deliberately does not test: the `<certificates src="user"/>` trust anchor in
 * `network_security_config.xml`. That is platform behaviour keyed on the device's user CA store,
 * which no JVM test can populate — verify it manually on an emulator with a CA installed.
 */
class AuthProberTlsTest {

    private lateinit var server: MockWebServer
    private lateinit var prober: AuthProber

    @Before fun setUp() {
        // Bind and connect by literal IP, never by name. On GitHub's runners "localhost"
        // resolves to ::1 first while MockWebServer binds IPv4, so the connection is refused
        // before any handshake — a plain ConnectException that correctly maps to Unreachable,
        // making these tests fail on CI while passing locally.
        val cert = HeldCertificate.Builder()
            .addSubjectAlternativeName(LOOPBACK)
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(cert)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start(InetAddress.getByName(LOOPBACK), 0)
        }
        prober = AuthProber(OcsHeadersInterceptor())
    }

    @After fun tearDown() {
        server.shutdown()
    }

    // The SAN matches the host we connect to, so the *only* reason the handshake can fail is
    // the untrusted chain — which is exactly what these tests are about.
    private fun creds(): Credentials = Credentials(
        serverUrl = "https://$LOOPBACK:${server.port}",
        login = "alice",
        appPassword = "pw",
    )

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }

    @Test fun `untrusted certificate chain maps to UntrustedCertificate, not Unreachable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val result = prober.probe(creds())
        assertTrue("expected UntrustedCertificate but got $result", result is AuthProbeResult.UntrustedCertificate)
    }

    @Test fun `handshake failure carries the underlying detail through`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val result = prober.probe(creds())
        result as AuthProbeResult.UntrustedCertificate
        // Only that something was passed through. The wording comes from the platform TLS
        // provider — SunJSSE here, Conscrypt on device — so asserting on its text would be
        // asserting on the JDK, not on us.
        assertNotNull(result.detail)
    }
}
