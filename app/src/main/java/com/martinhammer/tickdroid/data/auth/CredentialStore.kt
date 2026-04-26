package com.martinhammer.tickdroid.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): Credentials? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val login = prefs.getString(KEY_LOGIN, null) ?: return null
        val pass = prefs.getString(KEY_APP_PASSWORD, null) ?: return null
        return Credentials(url, login, pass)
    }

    fun save(credentials: Credentials) {
        prefs.edit()
            .putString(KEY_URL, credentials.serverUrl)
            .putString(KEY_LOGIN, credentials.login)
            .putString(KEY_APP_PASSWORD, credentials.appPassword)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE_NAME = "tickdroid_credentials"
        const val KEY_URL = "server_url"
        const val KEY_LOGIN = "login"
        const val KEY_APP_PASSWORD = "app_password"
    }
}
