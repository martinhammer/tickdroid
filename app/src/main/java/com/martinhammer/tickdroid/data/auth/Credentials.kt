package com.martinhammer.tickdroid.data.auth

import android.util.Base64

data class Credentials(
    val serverUrl: String,
    val login: String,
    val appPassword: String,
) {
    val basicAuthHeader: String
        get() = "Basic " + Base64.encodeToString(
            "$login:$appPassword".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
}
