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
 * via Retrofit's baseUrl; this interceptor swaps in the real host at request time.
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

        val rewritten = originalUrl.newBuilder()
            .scheme(serverUrl.scheme)
            .host(serverUrl.host)
            .port(serverUrl.port)
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
