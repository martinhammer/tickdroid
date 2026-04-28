package com.martinhammer.tickdroid.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class TickToggleResponse(val ticked: Boolean)

@Serializable
data class TickValueResponse(val value: Int)
