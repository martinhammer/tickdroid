package com.martinhammer.tickdroid.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracks",
    indices = [Index(value = ["serverId"], unique = true)],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val serverId: Long?,
    val name: String,
    val type: String,
    val sortOrder: Int,
    val private: Boolean,
    val dirty: Boolean = false,
    val deleted: Boolean = false,
    val updatedAtLocal: Long = System.currentTimeMillis(),
)
