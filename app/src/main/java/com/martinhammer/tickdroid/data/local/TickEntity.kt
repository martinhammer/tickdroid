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
)
