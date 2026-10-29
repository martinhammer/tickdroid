package com.martinhammer.tickdroid.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** A track's streak or break as of [asOf], from `GET /api/stats`. Replaced wholesale on every read. */
@Entity(
    tableName = "track_runs",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["localId"],
            childColumns = ["trackLocalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TrackRunEntity(
    @PrimaryKey val trackLocalId: Long,
    /** The phone's today the stats were asked for, ISO YYYY-MM-DD. */
    val asOf: String,
    /** Null together with [length]: the track has no ticks. */
    val isStreak: Boolean?,
    val length: Int?,
    val upToYesterday: Boolean,
    /** The highest tick value over the track's period, for the Today squares' shading. */
    val maxValue: Int,
)
