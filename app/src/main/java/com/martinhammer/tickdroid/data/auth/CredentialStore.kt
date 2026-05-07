package com.martinhammer.tickdroid.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences = openOrReset(context)

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
        const val TAG = "CredentialStore"
        const val FILE_NAME = "tickdroid_credentials"
        const val KEY_URL = "server_url"
        const val KEY_LOGIN = "login"
        const val KEY_APP_PASSWORD = "app_password"
        const val MASTER_KEY_ALIAS = MasterKey.DEFAULT_MASTER_KEY_ALIAS

        fun openOrReset(context: Context): SharedPreferences {
            return try {
                create(context)
            } catch (t: Throwable) {
                // Keystore / Tink state can become unreadable after OS updates,
                // biometric re-enrollment, or backup/restore on certain OEM devices.
                // Wipe and retry once; the user will be signed out and prompted to re-auth.
                Log.w(TAG, "EncryptedSharedPreferences.create failed; resetting", t)
                runCatching { context.deleteSharedPreferences(FILE_NAME) }
                runCatching {
                    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                        .deleteEntry(MASTER_KEY_ALIAS)
                }
                create(context)
            }
        }

        fun create(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
