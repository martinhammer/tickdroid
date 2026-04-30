package com.martinhammer.tickdroid.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackPrefsDao {

    @Query("SELECT * FROM track_prefs")
    fun observeAll(): Flow<List<TrackPrefsEntity>>

    @Query("SELECT * FROM track_prefs")
    suspend fun getAll(): List<TrackPrefsEntity>

    @Query("SELECT * FROM track_prefs WHERE serverId = :serverId LIMIT 1")
    suspend fun findByServerId(serverId: Long): TrackPrefsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrackPrefsEntity)

    @Query("DELETE FROM track_prefs WHERE serverId = :serverId")
    suspend fun deleteByServerId(serverId: Long)
}
