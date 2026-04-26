package com.martinhammer.tickdroid.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerUrl {
    /**
     * Validate and normalize a user-entered Nextcloud base URL.
     * Returns the canonical form (no trailing slash) or null if invalid.
     */
    fun normalize(input: String, allowHttp: Boolean = false): String? {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val url = trimmed.toHttpUrlOrNull() ?: return null
        if (!allowHttp && url.scheme != "https") return null
        if (url.scheme != "https" && url.scheme != "http") return null
        return trimmed
    }
}
