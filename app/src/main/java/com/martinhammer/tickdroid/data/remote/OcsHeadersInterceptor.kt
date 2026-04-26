package com.martinhammer.tickdroid.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OcsHeadersInterceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .build()
        return chain.proceed(request)
    }
}
