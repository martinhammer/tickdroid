package com.martinhammer.tickdroid.data.remote.dto

import kotlinx.serialization.Serializable

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
