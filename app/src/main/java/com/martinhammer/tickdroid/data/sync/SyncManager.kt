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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import javax.net.ssl.SSLException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** Categorization of sync failures. The UI combines this with the device's online state. */
enum class SyncErrorKind {
    /** IOException — the request never completed (DNS, timeout, refused, no network). */
    ServerUnreachable,

    /**
     * SSLException — TLS handshake failed (untrusted chain, expired cert, hostname mismatch).
     * Distinct from [ServerUnreachable]: the network is fine, so telling the user to check it
     * sends them the wrong way.
     */
    UntrustedCertificate,

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
    private val serverFeatures: ServerFeatures,
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

    /** Application-lifetime scope for fire-and-forget syncs. Outlives any ViewModel. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fire-and-forget pull. **UI callers must use this rather than launching [pull] in a
     * `viewModelScope`.**
     *
     * A sync is application work, not UI work. Tying it to a ViewModel means navigation can
     * cancel it mid-transaction, and Room tears the connection down on cancellation while the
     * transaction block is still executing on its own executor thread — the resulting
     * `SQLITE_MISUSE` ("connection is closed") escapes there as an uncaught fatal rather than
     * being absorbed as a `CancellationException`. That crashed the app on every first sign-in.
     *
     * `PushWorker` still awaits [pull] directly: it has to, to report its `Result`.
     */
    fun schedulePull(from: LocalDate, to: LocalDate) {
        scope.launch { pull(from, to) }
    }

    suspend fun pull(from: LocalDate, to: LocalDate) = withContext(Dispatchers.IO) {
        if (authRepository.state.value !is AuthState.SignedIn) return@withContext
        val before = _status.value
        _status.value = SyncStatus.Syncing
        try {
            mutex.withLock {
                // First, so the track read below matches what the server offers.
                refreshServerFeatures()
                val tracks = fetchTracks()
                val ticks = api.getTicks(from.toString(), to.toString(), includeAllTypes = true).ocs.data
                // Never abandon a half-applied reconcile: see schedulePull's note on what
                // Room does to the connection when a transaction's caller is cancelled.
                withContext(NonCancellable) {
                    db.withTransaction {
                        // Order matters: ticks dropped by applyTracks get the server's value
                        // back from reconcileTicks.
                        applyTracks(tracks)
                        reconcileTicks(ticks, from.toString(), to.toString())
                    }
                }
            }
            _status.value = SyncStatus.Idle
        } catch (e: SSLException) {
            _status.value = SyncStatus.Error(SyncErrorKind.UntrustedCertificate, e.message)
        } catch (e: IOException) {
            _status.value = SyncStatus.Error(SyncErrorKind.ServerUnreachable, e.message)
        } catch (e: retrofit2.HttpException) {
            _status.value = SyncStatus.Error(SyncErrorKind.ServerError, "HTTP ${e.code()}")
        } catch (e: CancellationException) {
            // A cancelled pull (PushWorker stopped by WorkManager, say) must not leave the
            // status at Syncing for good: the journal's spinner and the widgets' faded refresh
            // icon would never clear. It learned nothing, so put back what we knew before it.
            _status.value = before.takeUnless { it is SyncStatus.Syncing } ?: SyncStatus.Idle
            throw e
        }
    }

    /**
     * Runs a track change (create, rename, privacy, reorder, delete) against the server under the
     * sync lock, then re-reads the track list into Room under the same hold, so a pull can never
     * reconcile half-way through a change. Track changes are online only: there is no queue.
     *
     * Runs on this class's application scope, like [schedulePull]: a ViewModel cancelled
     * mid-change (Back pressed while saving) must not cancel a Room transaction (see
     * schedulePull's note). The caller just awaits the result.
     *
     * Doesn't call [pull] for the re-read: it takes [mutex] itself and `Mutex` isn't reentrant.
     * Only tracks are re-read; a track change never changes ticks on the server, except delete,
     * whose local ticks go with the track through the `ON DELETE CASCADE` foreign key.
     *
     * Leaves [status] / [pushStatus] alone: a track change is not a sync, and the journal's chip
     * and spinner shouldn't react to it.
     */
    suspend fun <T> changeTracks(change: suspend (TickbuddyApi) -> T): T = scope.async {
        mutex.withLock {
            val outcome = try {
                Result.success(change(api))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            // The server's answer decides: re-read even after a failure (a timed-out create may
            // have landed). A failed re-read never hides the change's own outcome; Room is then
            // stale until the next pull (journal resume, pull-to-refresh, the 15-minute worker).
            // Skipped after a 401 sign-out: there are no credentials left to read with.
            if (authRepository.state.value is AuthState.SignedIn) {
                try {
                    // No capabilities call here: the flag is whatever the last pull saw, and
                    // Tracks settings is always reached after one.
                    val tracks = fetchTracks()
                    withContext(NonCancellable) { db.withTransaction { applyTracks(tracks) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
            outcome.getOrThrow()
        }
    }.await()

    /**
     * Reads the capabilities into [ServerFeatures]. On any failure but cancellation the stored
     * value stands and the pull carries on: an IO failure fails the track read straight after
     * anyway, and a broken capabilities reply (another Nextcloud app can cause one) must not stop
     * the pull.
     */
    private suspend fun refreshServerFeatures() {
        try {
            serverFeatures.update(api.getCapabilities().ocs.data.capabilities?.tickbuddy)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Every track the phone should keep: archived ones too when the server offers them. */
    private suspend fun fetchTracks(): List<TrackDto> =
        api.getTracks(
            includeArchived = if (serverFeatures.archivedTracks.value) true else null,
            includeAllTypes = true,
        ).ocs.data

    /**
     * Applies a track list, then drops unsent ticks of archived tracks. Inside a transaction.
     *
     * The drop is not optional. An older server (or one sent no `Prefer: handling=strict`)
     * answers a tick on an archived track with a quiet 200 and the unchanged state, so the toggle
     * fallback in `PushWorker.pushBoolean` sees a mismatch and leaves a yes/no tick dirty. Archived tracks stay in Room, so without this the row would stay dirty for
     * good: re-sent on every push, never overwritten by a pull (dirty rows are skipped), the
     * "unsaved changes" chip and the widgets' offline symbol stuck on while offline, and the old
     * tick finally applied if the track is ever restored.
     */
    private suspend fun applyTracks(tracks: List<TrackDto>) {
        reconcileTracks(tracks)
        tickDao.deleteDirtyOfArchivedTracks()
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
                        archivedAt = dto.archivedAt,
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
                        archivedAt = dto.archivedAt,
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
        // Includes rows marked deleted, so a queued untick is seen and skipped as dirty.
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
                        deleted = false,
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
