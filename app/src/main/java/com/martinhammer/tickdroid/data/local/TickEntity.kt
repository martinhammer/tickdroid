package com.martinhammer.tickdroid.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ticks",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["localId"],
            childColumns = ["trackLocalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["trackLocalId", "date"], unique = true),
        Index(value = ["serverId"], unique = true),
    ],
)
data class TickEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val serverId: Long?,
    val trackLocalId: Long,
    val date: String, // ISO YYYY-MM-DD
    val value: Int,
    val dirty: Boolean = false,
    val deleted: Boolean = false,
    val updatedAtLocal: Long = System.currentTimeMillis(),
    /**
     * Counter rows only (null on yes/no rows). Null on a clean row. On a dirty row: the net change
     * made on this phone since the last value the server confirmed, so [value] is that value plus
     * this (possibly 0); null there means [value] is absolute and goes up with `set` (a change
     * queued before v4, whose delta can't be worked out).
     */
    val pendingDelta: Int? = null,
    /**
     * An increment sent (or about to be) whose answer hasn't arrived: its exact delta and its
     * `Idempotency-Key`. Both set or both null; [pendingDelta] includes [sentDelta]. Written before
     * the request goes out and resent unchanged until the server answers, so a lost answer can't
     * count twice.
     */
    val sentDelta: Int? = null,
    val sentKey: String? = null,
)
