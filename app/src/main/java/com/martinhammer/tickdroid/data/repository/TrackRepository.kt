package com.martinhammer.tickdroid.data.repository

import com.martinhammer.tickdroid.data.local.TrackDao
import com.martinhammer.tickdroid.data.local.TrackEntity
import com.martinhammer.tickdroid.data.local.TrackOptionDao
import com.martinhammer.tickdroid.domain.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackRepository @Inject constructor(
    private val trackDao: TrackDao,
    private val trackOptionDao: TrackOptionDao,
) {
    /**
     * Active tracks only, in journal order: what the journal, the widgets and widget setup show.
     * Archived tracks stay in Room but never reach them.
     */
    fun observeTracks(): Flow<List<Track>> = withOptions(trackDao.observeActive())

    /** Every track, archived included, in Room's order. For Tracks settings and the track screen. */
    fun observeAllTracks(): Flow<List<Track>> = withOptions(trackDao.observeAll())

    /**
     * The live track with this server id, or null if there is none (or it is pending deletion).
     * Archived tracks are returned too; callers that tick must check [Track.archived].
     */
    suspend fun findByServerId(serverId: Long): Track? {
        val entity = trackDao.findByServerId(serverId)?.takeUnless { it.deleted } ?: return null
        return entity.toDomain(trackOptionDao.getForTrack(entity.localId).sortedBy { it.place }.map { it.label })
    }

    private fun withOptions(tracks: Flow<List<TrackEntity>>): Flow<List<Track>> =
        combine(tracks, trackOptionDao.observeAll()) { list, options ->
            val labels = options.labelsByTrack()
            list.map { it.toDomain(labels[it.localId].orEmpty()) }
        }
}
