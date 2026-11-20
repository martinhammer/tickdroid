package com.martinhammer.tickdroid.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class TickToggleResponse(val ticked: Boolean)

@Serializable
data class TickValueResponse(val value: Int)

/**
 * `set`'s answer for a yes/no track, read leniently: Tickbuddy answered `true` / `false` before
 * settling on 1 / 0 for 2.3.0, and either may come from a dev server.
 */
@Serializable
data class TickYesNoResponse(
    @Serializable(with = LenientTickValueSerializer::class)
    val value: Int,
)

/**
 * `set`'s answer for a Number or Choice day: `{"value": 10.4}`, `{"value": 2}`, or
 * `{"value": null}` once it is cleared. Raw, so a Number's decimal is read exactly
 * (`numberFromWire`); null and [kotlinx.serialization.json.JsonNull] both mean "no value".
 */
@Serializable
data class TickRawValueResponse(val value: JsonPrimitive? = null)
