package com.martinhammer.tickdroid.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class TrackDto(
    val id: Long,
    val name: String,
    val type: String,
    val sortOrder: Int,
    val private: Boolean,
    /** YYYY-MM-DD or null; absent on servers before archiving (Tickbuddy step 5a). */
    val archivedAt: String? = null,
)
