package com.martinhammer.tickdroid.data.auth

import com.martinhammer.tickdroid.data.remote.OcsHeadersInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthProbeResult {
    data object Success : AuthProbeResult

    /** User-entered string couldn't be parsed as a URL. */
    data object InvalidUrl : AuthProbeResult

    /** URL parses but [okhttp3.OkHttpClient] couldn't reach the host (DNS/connection refused/etc). */
    data class Unreachable(val message: String) : AuthProbeResult

    /** Reached the host but `/status.php` didn't look like a Nextcloud server. */
    data object NotNextcloud : AuthProbeResult

    /** Nextcloud rejected the login + app password (401 on a generic OCS endpoint). */
    data object Unauthorized : AuthProbeResult

    /** Auth worked but the Tickbuddy app isn't enabled on this server (404 on the Tickbuddy route). */
    data object TickbuddyNotInstalled : AuthProbeResult

    /** Catch-all for unexpected HTTP statuses with the stage that produced them. */
    data class UnexpectedStatus(val stage: Stage, val code: Int) : AuthProbeResult

    enum class Stage { Server, Auth, Tickbuddy }
}

/**
 * Three-step credential validation. Bypasses [com.martinhammer.tickdroid.data.remote.BasicAuthInterceptor]
 * so we can probe before persisting anything.
 *
 * Stage 1 (`/status.php`, no auth): is this a reachable Nextcloud server?
 * Stage 2 (`/ocs/v2.php/cloud/user`, basic auth): are the credentials valid?
 * Stage 3 (`/ocs/v2.php/apps/tickbuddy/api/tracks`, basic auth): is Tickbuddy installed?
 *
 * Splitting them lets the UI tell the difference between a wrong server URL,
 * wrong credentials, and a Nextcloud server that simply doesn't have Tickbuddy.
 */
@Singleton
class AuthProber @Inject constructor(
    private val ocsHeaders: OcsHeadersInterceptor,
) {
    private val ocsClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(ocsHeaders)
        .build()

    private val plainClient: OkHttpClient = OkHttpClient.Builder().build()

    suspend fun probe(credentials: Credentials): AuthProbeResult = withContext(Dispatchers.IO) {
        val base = credentials.serverUrl.toHttpUrlOrNull() ?: return@withContext AuthProbeResult.InvalidUrl

        checkServer(base)?.let { return@withContext it }
        checkAuth(base, credentials)?.let { return@withContext it }
        checkTickbuddy(base, credentials)?.let { return@withContext it }
        AuthProbeResult.Success
    }

    private fun checkServer(base: HttpUrl): AuthProbeResult? {
        val url = base.newBuilder().addPathSegments("status.php").build()
        val request = Request.Builder().url(url).build()
        try {
            plainClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return AuthProbeResult.NotNextcloud
                }
                val body = response.body?.string().orEmpty()
                // status.php on Nextcloud returns JSON containing "productname":"Nextcloud" and "installed":true.
                val looksLikeNextcloud = body.contains("\"installed\"", ignoreCase = true) &&
                    body.contains("nextcloud", ignoreCase = true)
                return if (looksLikeNextcloud) null else AuthProbeResult.NotNextcloud
            }
        } catch (e: IOException) {
            return AuthProbeResult.Unreachable(e.message ?: "Could not reach server")
        }
    }

    private fun checkAuth(base: HttpUrl, credentials: Credentials): AuthProbeResult? {
        val url = base.newBuilder().addPathSegments("ocs/v2.php/cloud/user").build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", credentials.basicAuthHeader)
            .build()
        try {
            ocsClient.newCall(request).execute().use { response ->
                return when {
                    response.isSuccessful -> null
                    response.code == 401 -> AuthProbeResult.Unauthorized
                    else -> AuthProbeResult.UnexpectedStatus(AuthProbeResult.Stage.Auth, response.code)
                }
            }
        } catch (e: IOException) {
            return AuthProbeResult.Unreachable(e.message ?: "Network error during auth check")
        }
    }

    private fun checkTickbuddy(base: HttpUrl, credentials: Credentials): AuthProbeResult? {
        val url = base.newBuilder().addPathSegments("ocs/v2.php/apps/tickbuddy/api/tracks").build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", credentials.basicAuthHeader)
            .build()
        try {
            ocsClient.newCall(request).execute().use { response ->
                return when {
                    response.isSuccessful -> null
                    response.code == 404 -> AuthProbeResult.TickbuddyNotInstalled
                    response.code == 401 -> AuthProbeResult.Unauthorized
                    else -> AuthProbeResult.UnexpectedStatus(AuthProbeResult.Stage.Tickbuddy, response.code)
                }
            }
        } catch (e: IOException) {
            return AuthProbeResult.Unreachable(e.message ?: "Network error during Tickbuddy check")
        }
    }
}
