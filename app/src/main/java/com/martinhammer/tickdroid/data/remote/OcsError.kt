package com.martinhammer.tickdroid.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The reason in a failed OCS reply: `ocs.data.message`, else `ocs.meta.message`, else null.
 * Blank counts as absent. Mirrors Tickbuddy's own `src/ocsError.ts`.
 *
 * Parsed leniently as a [JsonElement]: `ocs.data` is an object for Tickbuddy's own errors but
 * `[]` for a thrown `OCSException`, and `meta.message` is often `""`. A body that isn't JSON at
 * all (a reverse proxy's HTML error page) gives null, so the caller's fallback applies.
 */
internal fun ocsErrorMessage(body: String?, json: Json): String? {
    if (body.isNullOrBlank()) return null
    val root = try {
        json.parseToJsonElement(body)
    } catch (_: Exception) {
        return null
    }
    val ocs = (root as? JsonObject)?.get("ocs") as? JsonObject ?: return null
    return ocs.message("data") ?: ocs.message("meta")
}

private fun JsonObject.message(child: String): String? {
    val value = ((get(child) as? JsonObject)?.get("message") as? JsonPrimitive) ?: return null
    if (!value.isString) return null
    return value.content.takeIf { it.isNotBlank() }
}
