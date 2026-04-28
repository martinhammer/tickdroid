package com.martinhammer.tickdroid.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TickDao {

    @Query("SELECT * FROM ticks WHERE deleted = 0 AND date BETWEEN :from AND :to")
    fun observeRange(from: String, to: String): Flow<List<TickEntity>>

    @Query("SELECT * FROM ticks WHERE deleted = 0 AND date BETWEEN :from AND :to")
    suspend fun getRange(from: String, to: String): List<TickEntity>

    @Query("SELECT * FROM ticks WHERE trackLocalId = :trackLocalId AND date = :date LIMIT 1")
    suspend fun find(trackLocalId: Long, date: String): TickEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tick: TickEntity): Long

    @Update
    suspend fun update(tick: TickEntity)

    @Query("DELETE FROM ticks WHERE localId = :localId")
    suspend fun deleteById(localId: Long)

    @Query("DELETE FROM ticks WHERE trackLocalId = :trackLocalId AND date = :date")
    suspend fun deleteByKey(trackLocalId: Long, date: String)

    @Query("SELECT * FROM ticks WHERE dirty = 1")
    suspend fun getDirty(): List<TickEntity>
}
