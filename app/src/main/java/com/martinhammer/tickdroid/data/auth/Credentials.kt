package com.martinhammer.tickdroid.data.auth

import java.util.Base64

data class Credentials(
    val serverUrl: String,
    val login: String,
    val appPassword: String,
) {
    val basicAuthHeader: String
        get() = "Basic " + Base64.getEncoder().encodeToString(
            "$login:$appPassword".toByteArray(Charsets.UTF_8),
        )
}
