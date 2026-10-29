package com.martinhammer.tickdroid.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackRunDao {

    @Query("SELECT * FROM track_runs")
    fun observeAll(): Flow<List<TrackRunEntity>>

    @Query("SELECT * FROM track_runs")
    suspend fun getAll(): List<TrackRunEntity>

    /** Every stats read replaces the whole table: a track the answer left out loses its run. */
    @Transaction
    suspend fun replaceAll(rows: List<TrackRunEntity>) {
        deleteAll()
        insertAll(rows)
    }

    @Query("DELETE FROM track_runs")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(rows: List<TrackRunEntity>)
}
