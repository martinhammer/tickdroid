package com.martinhammer.tickdroid.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.auth.AuthState
import com.martinhammer.tickdroid.data.local.NoteDao
import com.martinhammer.tickdroid.data.local.NoteEntity
import com.martinhammer.tickdroid.data.local.TickDao
import com.martinhammer.tickdroid.data.local.TickEntity
import com.martinhammer.tickdroid.data.local.TickdroidDatabase
import com.martinhammer.tickdroid.data.local.TrackDao
import com.martinhammer.tickdroid.data.local.TrackEntity
import com.martinhammer.tickdroid.data.local.TrackOptionDao
import com.martinhammer.tickdroid.data.local.TrackOptionEntity
import com.martinhammer.tickdroid.data.local.TrackPrefsDao
import com.martinhammer.tickdroid.data.local.TrackRunDao
import com.martinhammer.tickdroid.data.local.TrackRunEntity
import com.martinhammer.tickdroid.data.remote.TickbuddyApi
import com.martinhammer.tickdroid.data.remote.dto.NoteDto
import com.martinhammer.tickdroid.data.remote.dto.StatsDto
import com.martinhammer.tickdroid.data.remote.dto.TickDto
import com.martinhammer.tickdroid.data.remote.dto.TrackDto
import com.martinhammer.tickdroid.data.remote.dto.lenientTickValue
import com.martinhammer.tickdroid.data.time.Clock
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.domain.numberFromWire
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
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
    private val noteDao: NoteDao,
    private val trackRunDao: TrackRunDao,
    private val clock: Clock,
    private val trackOptionDao: TrackOptionDao,
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
                // Without the flag nothing is read and the notes table is left as it is (a
                // server rolled back keeps the phone's notes, and unsent ones stay queued).
                val notes = if (serverFeatures.notes.value) {
                    api.getNotes(from.toString(), to.toString(), includeAllTypes = true).ocs.data
                } else {
                    null
                }
                // Never abandon a half-applied reconcile: see schedulePull's note on what
                // Room does to the connection when a transaction's caller is cancelled.
                withContext(NonCancellable) {
                    db.withTransaction {
                        // Order matters: ticks dropped by applyTracks get the server's value
                        // back from reconcileTicks.
                        applyTracks(tracks)
                        reconcileTicks(ticks, from.toString(), to.toString())
                        if (notes != null) reconcileNotes(notes, from.toString(), to.toString())
                        // The first pull after the update has brought these tracks' values, so
                        // they can be written again (MIGRATION_5_6). Only here: changeTracks'
                        // re-read reads no ticks.
                        trackDao.clearAwaitingSync(tracks.map { it.id })
                    }
                }
                if (serverFeatures.stats.value) refreshRuns()
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

    /**
     * Reads `GET /api/stats` as of the phone's today into `track_runs`, one row per track the
     * phone knows. On any failure but cancellation the stored runs stand and the pull carries on,
     * as in [refreshServerFeatures]: a broken stats answer must never stop ticks syncing.
     */
    private suspend fun refreshRuns() {
        val today = clock.today().toString()
        val stats = try {
            api.getStats(today, includeAllTypes = true).ocs.data
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        withContext(NonCancellable) {
            db.withTransaction {
                val tracksByServerId = trackDao.getAll().associateBy { it.serverId }
                val rows = stats.mapNotNull { dto ->
                    val track = tracksByServerId[dto.trackId] ?: return@mapNotNull null
                    val (max, min) = statsRange(TrackType.fromWire(track.type), dto)
                    TrackRunEntity(
                        trackLocalId = track.localId,
                        asOf = today,
                        isStreak = dto.current?.isStreak,
                        length = dto.current?.length,
                        upToYesterday = dto.upToYesterday,
                        maxValue = max,
                        minValue = min,
                    )
                }.distinctBy { it.trackLocalId }
                trackRunDao.replaceAll(rows)
            }
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
            // Never sets awaitingSync: a track first seen here arrives with its ticks in the same
            // pull (or, from a track change's re-read, was just created and has none).
            val localId = if (existing == null) {
                trackDao.insert(
                    TrackEntity(
                        serverId = dto.id,
                        name = dto.name,
                        type = dto.type,
                        sortOrder = dto.sortOrder,
                        private = dto.private,
                        archivedAt = dto.archivedAt,
                        unit = dto.unit,
                        decimalPlaces = dto.decimalPlaces,
                        allowNegative = dto.allowNegative,
                        orderedScale = dto.orderedScale,
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
                        unit = dto.unit,
                        decimalPlaces = dto.decimalPlaces,
                        allowNegative = dto.allowNegative,
                        orderedScale = dto.orderedScale,
                    )
                )
                existing.localId
            } else {
                continue
            }
            reconcileOptions(localId, dto)
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

    /** Replaces a track's options when the server's list differs (a rename changes one label). */
    private suspend fun reconcileOptions(trackLocalId: Long, dto: TrackDto) {
        val wanted = dto.options.orEmpty().map { TrackOptionEntity(trackLocalId, it.value, it.label) }.sortedBy { it.place }
        val current = trackOptionDao.getForTrack(trackLocalId)
        if (current == wanted) return
        trackOptionDao.deleteForTrack(trackLocalId)
        if (wanted.isNotEmpty()) trackOptionDao.insertAll(wanted.distinctBy { it.place })
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
            // Per type: a Number's 10.4 is 10400 thousandths, not a marker. A value that can't be
            // read is skipped (the local row, if any, left as it is) rather than stored wrong.
            val value = tickValueFor(TrackType.fromWire(track.type), dto.value)
            if (value == null) {
                Log.w(TAG, "Skipping a ${track.type} tick whose value can't be read")
                continue
            }
            val existing = localInRange[key]
            if (existing == null) {
                tickDao.upsert(
                    TickEntity(
                        serverId = dto.id,
                        trackLocalId = track.localId,
                        date = dto.date,
                        value = value,
                    )
                )
            } else if (!existing.dirty) {
                tickDao.update(
                    existing.copy(
                        serverId = dto.id,
                        value = value,
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

    /**
     * Mirrors [reconcileTicks]: a server note with no local row is inserted clean, a clean row
     * takes the server's text, a dirty row is left alone (its `baseText` too), and a clean row in
     * the range the server didn't return is deleted. Notes of a track not in Room are skipped.
     */
    private suspend fun reconcileNotes(remote: List<NoteDto>, from: String, to: String) {
        val tracksByServerId = trackDao.getAll().associateBy { it.serverId }
        // Includes dirty "" rows, so a queued deletion is seen and skipped as dirty.
        val localInRange = noteDao.getRange(from, to).associateBy { it.trackLocalId to it.date }
        val seenKeys = mutableSetOf<Pair<Long, String>>()

        for (dto in remote) {
            val track = tracksByServerId[dto.trackId] ?: continue
            if (dto.text.isEmpty()) continue
            val key = track.localId to dto.date
            seenKeys += key
            val existing = localInRange[key]
            if (existing == null) {
                noteDao.upsert(NoteEntity(trackLocalId = track.localId, date = dto.date, text = dto.text))
            } else if (!existing.dirty && existing.text != dto.text) {
                noteDao.update(existing.copy(text = dto.text, baseText = null))
            }
        }

        for ((key, entity) in localInRange) {
            if (key !in seenKeys && !entity.dirty) {
                noteDao.deleteById(entity.localId)
            }
        }
    }
}

private const val TAG = "SyncManager"

/**
 * A tick's value as Room stores it for a track of [type]: yes/no and counter as an integer, a
 * Number in whole thousandths ([numberFromWire], from the number's own text), a Choice as its
 * place, and a type this Tickdroid doesn't know as the lenient 1/0 marker. Null when a Number or
 * Choice value can't be read; the caller skips it. Shared by the pull and the push's settle.
 */
internal fun tickValueFor(type: TrackType, raw: JsonElement?): Int? = when (type) {
    TrackType.Number -> (raw as? JsonPrimitive)?.takeUnless { it is JsonNull || it.isString }?.let(::numberFromWire)
    TrackType.Choice -> (raw as? JsonPrimitive)?.takeUnless { it is JsonNull || it.isString }?.intOrNull?.takeIf { it > 0 }
    TrackType.YesNo, TrackType.Counter, is TrackType.Unknown -> lenientTickValue(raw ?: JsonNull)
}

/**
 * The stats answer's `maxValue` and `minValue` as `track_runs` stores them, per type: a counter's
 * maximum as an integer; a Number's both in thousandths (0 and null with no entries); an ordered
 * scale's as places (null, so 0, on a plain list).
 */
internal fun statsRange(type: TrackType, dto: StatsDto): Pair<Int, Int?> = when (type) {
    TrackType.Number -> (tickValueFor(type, dto.maxValue) ?: 0) to tickValueFor(type, dto.minValue)
    TrackType.Choice -> (tickValueFor(type, dto.maxValue) ?: 0) to tickValueFor(type, dto.minValue)
    else -> ((dto.maxValue as? JsonPrimitive)?.takeUnless { it is JsonNull }?.intOrNull ?: 0) to null
}
