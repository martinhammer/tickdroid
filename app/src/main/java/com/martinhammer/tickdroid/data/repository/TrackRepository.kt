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

    /** The live track with this server id, or null if there is none (or it is pending deletion). */
    suspend fun findByServerId(serverId: Long): Track? =
        trackDao.findByServerId(serverId)?.takeUnless { it.deleted }?.toDomain()
}
