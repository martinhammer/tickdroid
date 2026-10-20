package com.martinhammer.tickdroid.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OcsHeadersInterceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
        // RFC 7240: ask Tickbuddy to refuse a write it can't apply (423) rather than answer a quiet
        // 200. Tickbuddy's API only; an older Tickbuddy ignores it.
        if (TICKBUDDY_API in original.url.encodedPath) builder.header("Prefer", "handling=strict")
        return chain.proceed(builder.build())
    }

    private companion object {
        // Matched anywhere in the path: BasicAuthInterceptor runs first and may add a subpath.
        const val TICKBUDDY_API = "/ocs/v2.php/apps/tickbuddy/api/"
    }
}
