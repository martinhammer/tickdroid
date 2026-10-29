package com.martinhammer.tickdroid.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    /** Notes to show: a dirty "" row is a deletion waiting to go up, so it is left out. */
    @Query("SELECT * FROM notes WHERE text != '' AND date BETWEEN :from AND :to")
    fun observeRange(from: String, to: String): Flow<List<NoteEntity>>

    /** Every row, dirty "" ones too: the pull must see a queued deletion, as `TickDao.getRange`. */
    @Query("SELECT * FROM notes WHERE date BETWEEN :from AND :to")
    suspend fun getRange(from: String, to: String): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE trackLocalId = :trackLocalId AND date = :date LIMIT 1")
    suspend fun find(trackLocalId: Long, date: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE localId = :localId")
    suspend fun findById(localId: Long): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Query("DELETE FROM notes WHERE localId = :localId")
    suspend fun deleteById(localId: Long)

    @Query("SELECT * FROM notes WHERE dirty = 1 ORDER BY localId")
    suspend fun getDirty(): List<NoteEntity>
}
