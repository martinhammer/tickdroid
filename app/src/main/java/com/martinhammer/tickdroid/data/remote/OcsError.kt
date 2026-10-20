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
    val ocs = ocsObject(body, json) ?: return null
    return ocs.string("data", "message") ?: ocs.string("meta", "message")
}

/**
 * Tickbuddy's machine-readable reason in a failed reply, `ocs.data.reason` (`archived`,
 * `trackNotFound`, …), else null. Blank counts as absent. Nextcloud's own errors, a proxy's and
 * an older Tickbuddy's never carry one, which is what lets the push tell them apart.
 */
internal fun ocsErrorReason(body: String?, json: Json): String? =
    ocsObject(body, json)?.string("data", "reason")

private fun ocsObject(body: String?, json: Json): JsonObject? {
    if (body.isNullOrBlank()) return null
    val root = try {
        json.parseToJsonElement(body)
    } catch (_: Exception) {
        return null
    }
    return (root as? JsonObject)?.get("ocs") as? JsonObject
}

private fun JsonObject.string(child: String, field: String): String? {
    val value = ((get(child) as? JsonObject)?.get(field) as? JsonPrimitive) ?: return null
    if (!value.isString) return null
    return value.content.takeIf { it.isNotBlank() }
}
