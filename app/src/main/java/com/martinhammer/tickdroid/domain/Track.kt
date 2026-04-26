package com.martinhammer.tickdroid.domain

data class Track(
    val localId: Long,
    val serverId: Long?,
    val name: String,
    val type: TrackType,
    val sortOrder: Int,
    val private: Boolean,
)
