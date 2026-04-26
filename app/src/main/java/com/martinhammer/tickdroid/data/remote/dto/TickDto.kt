package com.martinhammer.tickdroid.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class TickDto(
    val id: Long,
    val trackId: Long,
    val date: String,
    val value: Int,
)
