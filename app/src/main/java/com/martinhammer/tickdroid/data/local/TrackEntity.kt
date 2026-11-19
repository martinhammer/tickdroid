package com.martinhammer.tickdroid.data.local

import androidx.room.ColumnInfo
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
    /** ISO YYYY-MM-DD; null = active. Stored like `TickEntity.date`. */
    val archivedAt: String? = null,
    /** Number only: the unit, "" for none. Null on other types (and from servers before 3.2.0). */
    val unit: String? = null,
    /** Number only: 0 to 3. */
    val decimalPlaces: Int? = null,
    /** Number only. */
    val allowNegative: Boolean? = null,
    /** Choice only. Its options are in `track_options`. */
    val orderedScale: Boolean? = null,
    /**
     * A Number or Choice track whose ticks were 1.5.0's 1/0 markers: set (and the markers deleted)
     * by MIGRATION_5_6, cleared only by a successful pull. Read-only while set. NOT NULL with a
     * default, unlike the earlier added columns, so the migration can add it to existing rows.
     */
    @ColumnInfo(defaultValue = "0")
    val awaitingSync: Boolean = false,
    val dirty: Boolean = false,
    val deleted: Boolean = false,
    val updatedAtLocal: Long = System.currentTimeMillis(),
)
