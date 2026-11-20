package com.martinhammer.tickdroid.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackOptionDao {

    @Query("SELECT * FROM track_options ORDER BY trackLocalId, place")
    fun observeAll(): Flow<List<TrackOptionEntity>>

    @Query("SELECT * FROM track_options WHERE trackLocalId = :trackLocalId ORDER BY place")
    suspend fun getForTrack(trackLocalId: Long): List<TrackOptionEntity>

    @Query("DELETE FROM track_options WHERE trackLocalId = :trackLocalId")
    suspend fun deleteForTrack(trackLocalId: Long)

    @Insert
    suspend fun insertAll(rows: List<TrackOptionEntity>)
}
