package com.martinhammer.tickdroid.data.sync

import androidx.room.withTransaction
import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.auth.AuthState
import com.martinhammer.tickdroid.data.local.TickDao
import com.martinhammer.tickdroid.data.local.TickEntity
import com.martinhammer.tickdroid.data.local.TickdroidDatabase
import com.martinhammer.tickdroid.data.local.TrackDao
import com.martinhammer.tickdroid.data.local.TrackEntity
import com.martinhammer.tickdroid.data.local.TrackPrefsDao
import com.martinhammer.tickdroid.data.remote.TickbuddyApi
import com.martinhammer.tickdroid.data.remote.dto.TickDto
import com.martinhammer.tickdroid.data.remote.dto.TrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Categorization of sync failures. The UI combines this with the device's online state. */
enum class SyncErrorKind {
    /** IOException — the request never completed (DNS, timeout, refused, no network). */
    ServerUnreachable,

    /** HttpException — the server responded with a non-2xx status. */
    ServerError,
}

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Error(val kind: SyncErrorKind, val message: String? = null) : SyncStatus
}

sealed interface PushStatus {
    data object Idle : PushStatus
    data object Pushing : PushStatus
    data class Error(val kind: SyncErrorKind, val message: String? = null) : PushStatus
}

/**
 * Read-only pull for v1: fetches tracks and the requested tick window from the server,
 * and reconciles into Room. Server-authoritative when local rows aren't dirty.
 *
 * Phase 3 will add the push side and conflict logic for dirty rows.
 */
@Singleton
class SyncManager @Inject constructor(
    private val api: TickbuddyApi,
    private val db: TickdroidDatabase,
    private val trackDao: TrackDao,
    private val tickDao: TickDao,
    private val trackPrefsDao: TrackPrefsDao,
    private val authRepository: AuthRepository,
) {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _pushStatus = MutableStateFlow<PushStatus>(PushStatus.Idle)
    val pushStatus: StateFlow<PushStatus> = _pushStatus.asStateFlow()

    /** Worker entry point: PushWorker reports its lifecycle here. */
    fun reportPushStatus(status: PushStatus) {
        _pushStatus.value = status
    }

    /**
     * Serializes pull and push so reconcile snapshots and dirty-row drains never interleave.
     * Held by [pull] here and by `PushWorker` via [runExclusive].
     */
    private val mutex = Mutex()

    /** Run [block] under the same lock that pull uses. PushWorker calls this. */
    suspend fun <T> runExclusive(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun pull(from: LocalDate, to: LocalDate) = withContext(Dispatchers.IO) {
        if (authRepository.state.value !is AuthState.SignedIn) return@withContext
        _status.value = SyncStatus.Syncing
        try {
            mutex.withLock {
                val tracks = api.getTracks().ocs.data
                val ticks = api.getTicks(from.toString(), to.toString()).ocs.data
                db.withTransaction {
                    reconcileTracks(tracks)
                    reconcileTicks(ticks, from.toString(), to.toString())
                }
            }
            _status.value = SyncStatus.Idle
        } catch (e: IOException) {
            _status.value = SyncStatus.Error(SyncErrorKind.ServerUnreachable, e.message)
        } catch (e: retrofit2.HttpException) {
            _status.value = SyncStatus.Error(SyncErrorKind.ServerError, "HTTP ${e.code()}")
        }
    }

    private suspend fun reconcileTracks(remote: List<TrackDto>) {
        val local = trackDao.getAll().associateBy { it.serverId }
        val remoteIds = remote.map { it.id }.toSet()

        for (dto in remote) {
            val existing = local[dto.id] ?: trackDao.findUnsyncedByName(dto.name)
            if (existing == null) {
                trackDao.insert(
                    TrackEntity(
                        serverId = dto.id,
                        name = dto.name,
                        type = dto.type,
                        sortOrder = dto.sortOrder,
                        private = dto.private,
                    )
                )
            } else if (!existing.dirty) {
                trackDao.update(
                    existing.copy(
                        serverId = dto.id,
                        name = dto.name,
                        type = dto.type,
                        sortOrder = dto.sortOrder,
                        private = dto.private,
                    )
                )
            }
        }

        // Tracks present locally but missing from server → drop unless dirty (Phase 3 handles push).
        for (entity in local.values) {
            val sid = entity.serverId ?: continue
            if (sid !in remoteIds && !entity.dirty) {
                trackDao.deleteById(entity.localId)
            }
        }

        // Sweep orphan track_prefs rows: any pref keyed on a serverId the server no longer
        // returns (track was deleted in Tickbuddy) is dead weight. track_prefs is local-only
        // so there's nothing to push — just delete.
        for (prefs in trackPrefsDao.getAll()) {
            if (prefs.serverId !in remoteIds) {
                trackPrefsDao.deleteByServerId(prefs.serverId)
            }
        }
    }

    private suspend fun reconcileTicks(remote: List<TickDto>, from: String, to: String) {
        val tracksByServerId = trackDao.getAll().associateBy { it.serverId }
        val localInRange = tickDao.getRange(from, to).associateBy { it.trackLocalId to it.date }
        val seenKeys = mutableSetOf<Pair<Long, String>>()

        for (dto in remote) {
            val track = tracksByServerId[dto.trackId] ?: continue
            val key = track.localId to dto.date
            seenKeys += key
            val existing = localInRange[key]
            if (existing == null) {
                tickDao.upsert(
                    TickEntity(
                        serverId = dto.id,
                        trackLocalId = track.localId,
                        date = dto.date,
                        value = dto.value,
                    )
                )
            } else if (!existing.dirty) {
                tickDao.update(
                    existing.copy(
                        serverId = dto.id,
                        value = dto.value,
                    )
                )
            }
        }

        // Local ticks in range that the server didn't return → server says "no tick here".
        // Drop unless dirty (Phase 3 will push them).
        for ((key, entity) in localInRange) {
            if (key !in seenKeys && !entity.dirty) {
                tickDao.deleteById(entity.localId)
            }
        }
    }
}
