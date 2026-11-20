package com.martinhammer.tickdroid.data.local

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * One option of a Choice track: its [place] (1 to n, which is what a day stores) and [label].
 * One row per option, as on the server, so a rename is a one-row write. Replaced from every
 * track read; options go with their track (`ON DELETE CASCADE`).
 */
@Entity(
    tableName = "track_options",
    primaryKeys = ["trackLocalId", "place"],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["localId"],
            childColumns = ["trackLocalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TrackOptionEntity(
    val trackLocalId: Long,
    val place: Int,
    val label: String,
)
