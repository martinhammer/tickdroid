package com.martinhammer.tickdroid.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.martinhammer.tickdroid.data.remote.BasicAuthInterceptor
import com.martinhammer.tickdroid.data.remote.OcsHeadersInterceptor
import com.martinhammer.tickdroid.data.remote.TickbuddyApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttp(
        ocsHeaders: OcsHeadersInterceptor,
        basicAuth: BasicAuthInterceptor,
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        return OkHttpClient.Builder()
            .addInterceptor(basicAuth)
            .addInterceptor(ocsHeaders)
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit {
        // The host here is a placeholder; BasicAuthInterceptor rewrites it per-request
        // to whatever Nextcloud server URL the user configured.
        return Retrofit.Builder()
            .baseUrl("http://localhost/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideTickbuddyApi(retrofit: Retrofit): TickbuddyApi =
        retrofit.create(TickbuddyApi::class.java)
}
