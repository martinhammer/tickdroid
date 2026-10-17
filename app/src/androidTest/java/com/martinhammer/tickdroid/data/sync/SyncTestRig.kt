package com.martinhammer.tickdroid.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.auth.AuthState
import com.martinhammer.tickdroid.data.auth.CredentialStore
import com.martinhammer.tickdroid.data.auth.Credentials
import com.martinhammer.tickdroid.data.local.TickdroidDatabase
import com.martinhammer.tickdroid.data.remote.OcsHeadersInterceptor
import com.martinhammer.tickdroid.data.remote.TickbuddyApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import retrofit2.Retrofit
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Shared scaffolding for sync-layer instrumentation tests. Wires real Room (in-memory),
 * a real Retrofit/OkHttp stack pointed at MockWebServer, and a real AuthRepository
 * pre-seeded with a signed-in session.
 */
class SyncTestRig {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext: Context = InstrumentationRegistry.getInstrumentation().context
    /**
     * What the rig's capabilities answer says. Every pull starts with
     * `GET /ocs/v2.php/cloud/capabilities`; the rig answers it itself, so it never takes a queued
     * response meant for the API calls that follow.
     */
    @Volatile var archivedTracks = false

    /** Replaces the capabilities answer (a 500, a broken reply), when set. */
    @Volatile var capabilitiesOverride: MockResponse? = null

    // The rig owns the response queue and answers capabilities in front of it. (okhttp's
    // QueueDispatcher is final here, and its bridged peek() throws, so it can't be wrapped.)
    // `server.enqueue` would fail, since it casts the dispatcher: enqueue through [enqueue].
    private val queue = LinkedBlockingQueue<MockResponse>()

    val server: MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.isCapabilities()) capabilitiesResponse() else queue.take()

            // MockWebServer reads a response's socket policy (a dropped connection) from here
            // before the request arrives.
            override fun peek(): MockResponse = queue.peek() ?: super.peek()

            // Unblocks a dispatch still waiting in take(), so shutdown can't hang.
            override fun shutdown() {
                queue.add(MockResponse().setResponseCode(503))
            }
        }
        start()
    }

    fun enqueue(response: MockResponse) {
        queue.add(response)
    }

    fun capabilitiesResponse(): MockResponse = capabilitiesOverride ?: MockResponse().setResponseCode(200).setBody(
        """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":{"capabilities":""" +
            """{"tickbuddy":{"version":"1.3.0","apiVersion":1,"features":{"archivedTracks":$archivedTracks}}}}}}""",
    )

    val db: TickdroidDatabase = Room
        .inMemoryDatabaseBuilder(context, TickdroidDatabase::class.java)
        .build()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(OcsHeadersInterceptor())
        .build()

    val api: TickbuddyApi = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(TickbuddyApi::class.java)

    private val credentialStore = CredentialStore(context).also { it.clear() }
    val authRepository: AuthRepository = run {
        // Pre-seed signed-in state. The repository reads the store on init.
        credentialStore.save(
            Credentials(
                serverUrl = server.url("/").toString().removeSuffix("/"),
                login = "test",
                appPassword = "pw",
            ),
        )
        AuthRepository(credentialStore)
    }

    val serverFeatures = ServerFeatures(context).also { it.clear() }

    val syncManager: SyncManager = SyncManager(
        api = api,
        db = db,
        trackDao = db.trackDao(),
        tickDao = db.tickDao(),
        trackPrefsDao = db.trackPrefsDao(),
        authRepository = authRepository,
        serverFeatures = serverFeatures,
    )

    /** The next request that isn't the capabilities call every pull starts with. */
    fun takeApiRequest(timeoutSeconds: Long = 5): RecordedRequest {
        while (true) {
            val request = checkNotNull(server.takeRequest(timeoutSeconds, TimeUnit.SECONDS)) { "no request arrived" }
            if (!request.isCapabilities()) return request
        }
    }

    fun assertSignedIn() {
        check(authRepository.state.value is AuthState.SignedIn) {
            "test rig failed to sign in"
        }
    }

    fun enqueueAsset(path: String, code: Int = 200) {
        val body = testContext.assets.open(path).bufferedReader().use { it.readText() }
        enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    fun enqueueJson(json: String, code: Int = 200) {
        enqueue(MockResponse().setResponseCode(code).setBody(json))
    }

    fun enqueueStatus(code: Int) {
        enqueue(MockResponse().setResponseCode(code))
    }

    fun shutdown() {
        try { server.shutdown() } catch (_: IOException) {}
        db.close()
        credentialStore.clear()
        serverFeatures.clear()
    }
}

internal fun RecordedRequest.isCapabilities(): Boolean =
    path?.startsWith("/ocs/v2.php/cloud/capabilities") == true
