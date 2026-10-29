package com.martinhammer.tickdroid.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One note per track per day. Clean rows hold the server's note as last read; only a dirty row
 * may hold "" (the phone deleted the note, and the push hasn't said so yet). No `serverId`: the
 * server has no note ids, track and date are the key.
 */
@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["localId"],
            childColumns = ["trackLocalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["trackLocalId", "date"], unique = true)],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val trackLocalId: Long,
    /** ISO YYYY-MM-DD. */
    val date: String,
    /** What the phone shows, normalised (`normaliseNote`). "" on a dirty row: the phone deleted the note. */
    val text: String,
    val dirty: Boolean = false,
    /**
     * Dirty rows only: the server's note as the phone last knew it when this change began ("" for
     * none). Kept through every later edit until the push settles the row, so the push can tell
     * the phone's new lines from another writer's. Null on a clean row.
     */
    val baseText: String? = null,
)
