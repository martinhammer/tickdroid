package com.martinhammer.tickdroid.domain

import java.time.LocalDate

data class Track(
    val localId: Long,
    val serverId: Long?,
    val name: String,
    val type: TrackType,
    val sortOrder: Int,
    val private: Boolean,
    /** The day it was archived; null for an active track. */
    val archivedAt: LocalDate? = null,
) {
    val archived: Boolean get() = archivedAt != null
}
