package com.martinhammer.tickdroid.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class TrackDto(
    val id: Long,
    val name: String,
    val type: String,
    val sortOrder: Int,
    val private: Boolean,
)
