package com.martinhammer.tickdroid.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local-only per-track UI preferences keyed by Tickbuddy's stable [serverId].
 * Never synced. Tracks without a serverId fall back to defaults.
 */
@Entity(tableName = "track_prefs")
data class TrackPrefsEntity(
    @PrimaryKey val serverId: Long,
    val colorKey: String? = null,
    val emoji: String? = null,
)
