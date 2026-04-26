package com.martinhammer.tickdroid.data.repository

import com.martinhammer.tickdroid.data.local.TrackDao
import com.martinhammer.tickdroid.domain.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackRepository @Inject constructor(
    private val trackDao: TrackDao,
) {
    fun observeTracks(): Flow<List<Track>> =
        trackDao.observeAll().map { list -> list.map { it.toDomain() } }
}
