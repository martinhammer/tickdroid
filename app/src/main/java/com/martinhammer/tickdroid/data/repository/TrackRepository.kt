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
    /**
     * Active tracks only, in journal order: what the journal, the widgets and widget setup show.
     * Archived tracks stay in Room but never reach them.
     */
    fun observeTracks(): Flow<List<Track>> =
        trackDao.observeActive().map { list -> list.map { it.toDomain() } }

    /** Every track, archived included, in Room's order. For Tracks settings and the track screen. */
    fun observeAllTracks(): Flow<List<Track>> =
        trackDao.observeAll().map { list -> list.map { it.toDomain() } }

    /**
     * The live track with this server id, or null if there is none (or it is pending deletion).
     * Archived tracks are returned too; callers that tick must check [Track.archived].
     */
    suspend fun findByServerId(serverId: Long): Track? =
        trackDao.findByServerId(serverId)?.takeUnless { it.deleted }?.toDomain()
}
