package com.martinhammer.tickdroid.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks WHERE deleted = 0 ORDER BY sortOrder ASC, localId ASC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE deleted = 0 ORDER BY sortOrder ASC, localId ASC")
    suspend fun getAll(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE serverId = :serverId LIMIT 1")
    suspend fun findByServerId(serverId: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE serverId IS NULL AND name = :name LIMIT 1")
    suspend fun findUnsyncedByName(name: String): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(track: TrackEntity): Long

    @Update
    suspend fun update(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE localId = :localId")
    suspend fun deleteById(localId: Long)
}
