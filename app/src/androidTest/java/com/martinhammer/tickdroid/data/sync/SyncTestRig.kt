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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import java.io.IOException

/**
 * Shared scaffolding for sync-layer instrumentation tests. Wires real Room (in-memory),
 * a real Retrofit/OkHttp stack pointed at MockWebServer, and a real AuthRepository
 * pre-seeded with a signed-in session.
 */
class SyncTestRig {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext: Context = InstrumentationRegistry.getInstrumentation().context
    val server: MockWebServer = MockWebServer().apply { start() }

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

    val syncManager: SyncManager = SyncManager(
        api = api,
        db = db,
        trackDao = db.trackDao(),
        tickDao = db.tickDao(),
        trackPrefsDao = db.trackPrefsDao(),
        authRepository = authRepository,
    )

    fun assertSignedIn() {
        check(authRepository.state.value is AuthState.SignedIn) {
            "test rig failed to sign in"
        }
    }

    fun enqueueAsset(path: String, code: Int = 200) {
        val body = testContext.assets.open(path).bufferedReader().use { it.readText() }
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    fun enqueueJson(json: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(json))
    }

    fun enqueueStatus(code: Int) {
        server.enqueue(MockResponse().setResponseCode(code))
    }

    fun shutdown() {
        try { server.shutdown() } catch (_: IOException) {}
        db.close()
        credentialStore.clear()
    }
}
