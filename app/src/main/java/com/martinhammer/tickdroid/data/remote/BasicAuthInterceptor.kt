package com.martinhammer.tickdroid.data.remote

import com.martinhammer.tickdroid.data.auth.AuthRepository
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rewrites the request URL with the user's stored Nextcloud server origin and adds the
 * Basic auth header. Endpoint paths are declared with a placeholder host (`http://localhost/`)
 * via Retrofit's baseUrl; this interceptor swaps in the real origin at request time.
 *
 * "Origin" includes any **subpath**: Nextcloud is commonly hosted under one
 * (`https://example.com/nextcloud`), the auth screen explicitly invites users to enter it, and
 * [com.martinhammer.tickdroid.data.remote.ServerUrl.normalize] accepts it. The [TickbuddyApi]
 * paths are absolute (`@GET("/ocs/v2.php/...")`), so they replace the path wholesale unless the
 * stored prefix is prepended here — which it previously wasn't. [AuthProber] never had the bug
 * (it builds URLs with `addPathSegments`), so a subpath server passed the connect probe and then
 * sent every real request to the wrong place.
 *
 * On 401 the interceptor clears credentials so the UI flips to re-auth on the next AuthState read.
 */
@Singleton
class BasicAuthInterceptor @Inject constructor(
    private val authRepository: AuthRepository,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val credentials = authRepository.currentCredentials()
            ?: return chain.proceed(chain.request())

        val originalUrl = chain.request().url
        val serverUrl = credentials.serverUrl.toHttpUrlOrNull()
            ?: return chain.proceed(chain.request())

        // encodedPath is "/" for a bare origin (trims to empty, leaving the path untouched) and
        // "/nextcloud" for a subpath one. Query parameters are carried over untouched.
        val prefix = serverUrl.encodedPath.trimEnd('/')
        val rewritten = originalUrl.newBuilder()
            .scheme(serverUrl.scheme)
            .host(serverUrl.host)
            .port(serverUrl.port)
            .encodedPath(prefix + originalUrl.encodedPath)
            .build()

        val request = chain.request().newBuilder()
            .url(rewritten)
            .header("Authorization", credentials.basicAuthHeader)
            .build()

        val response = chain.proceed(request)
        if (response.code == 401) {
            authRepository.signOut()
        }
        return response
    }
}
