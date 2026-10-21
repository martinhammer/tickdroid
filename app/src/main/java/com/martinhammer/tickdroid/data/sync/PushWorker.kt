package com.martinhammer.tickdroid.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.auth.AuthState
import com.martinhammer.tickdroid.data.local.TickDao
import com.martinhammer.tickdroid.data.local.TickEntity
import com.martinhammer.tickdroid.data.local.TickdroidDatabase
import com.martinhammer.tickdroid.data.local.TrackDao
import com.martinhammer.tickdroid.data.local.TrackEntity
import com.martinhammer.tickdroid.data.remote.TickbuddyApi
import com.martinhammer.tickdroid.data.remote.ocsErrorMessage
import com.martinhammer.tickdroid.data.remote.ocsErrorReason
import com.martinhammer.tickdroid.data.time.Clock
import com.martinhammer.tickdroid.domain.TrackType
import androidx.room.withTransaction
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drains every dirty tick row to the server. Boolean ticks use the spec's replay-safe
 * pattern (fetch current server state for the day; only `/toggle` if it differs).
 * Counter ticks use the idempotent `/set`.
 *
 * After an HTTP error on one row ([pushFailureFor]): 401 signs the user out and stops for good;
 * 423 (a read-only, i.e. archived, track) drops that tick, carries on and tells the user once per
 * track ([PushNotices], after the pull); 404 / 400 carrying a Tickbuddy `reason` (track deleted
 * elsewhere, or a bug) drops it silently and carries on; anything else, and IO / TLS failures,
 * stop the drain and retry with exponential backoff. Track changes are online only, so we never
 * push tracks.
 */
@HiltWorker
class PushWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val api: TickbuddyApi,
    private val database: TickdroidDatabase,
    private val tickDao: TickDao,
    private val trackDao: TrackDao,
    private val authRepository: AuthRepository,
    private val syncManager: SyncManager,
    private val clock: Clock,
    private val json: Json,
    private val pushNotices: PushNotices,
) : CoroutineWorker(appContext, params) {

    /**
     * What to tell the user about ticks the server refused this run, one entry per track (keyed
     * by its local id) in queue order. WorkManager makes a new worker per run, so "once" is once
     * per sync.
     */
    private val refusals = LinkedHashMap<Long, String>()

    override suspend fun doWork(): Result {
        val drainResult = syncManager.runExclusive { drain() }
        // Pull only after a drain that finished (dropped rows count as finished). Compare
        // against the Result.success() factory rather than `is Result.Success`: the
        // Result.Success subclass type is @RestrictTo(LIBRARY_GROUP), so referencing it fails
        // lint's RestrictedApi check.
        if (drainResult == Result.success()) {
            // Pull a recent window so periodic work also surfaces server-side changes from other
            // devices, not just our own pushes. The journal's own pull-on-resume / PTR still
            // covers wider history; this is the periodic background refresh.
            val today = clock.today()
            syncManager.pull(today.minusDays(PULL_WINDOW_DAYS), today)
        }
        // After the pull, so the journal and widgets already show what the server kept. Also
        // after a retry: the refused rows are gone, so a later run would never mention them.
        // Not after a 401: the app is signing out.
        if (drainResult != Result.failure()) refusals.values.forEach { pushNotices.show(it) }
        return drainResult
    }

    private companion object {
        const val PULL_WINDOW_DAYS = 30L
        const val TAG = "PushWorker"
    }

    private suspend fun drain(): Result {
        if (authRepository.state.value !is AuthState.SignedIn) {
            syncManager.reportPushStatus(PushStatus.Idle)
            return Result.success()
        }

        val dirty = tickDao.getDirty()
        if (dirty.isEmpty()) {
            syncManager.reportPushStatus(PushStatus.Idle)
            return Result.success()
        }

        val before = syncManager.pushStatus.value
        syncManager.reportPushStatus(PushStatus.Pushing)
        try {
            val tracks = trackDao.getAll().associateBy { it.localId }
            for (entity in dirty) {
                val track = tracks[entity.trackLocalId] ?: continue
                try {
                    pushOne(track, entity)
                } catch (e: HttpException) {
                    val body = e.response()?.errorBody()?.string() // can only be read once
                    when (pushFailureFor(e.code(), ocsErrorReason(body, json))) {
                        PushFailure.SignOut -> {
                            authRepository.signOut()
                            syncManager.reportPushStatus(PushStatus.Error(SyncErrorKind.ServerError, "Session expired"))
                            return Result.failure()
                        }
                        PushFailure.Refused -> {
                            tickDao.deleteById(entity.localId)
                            noteRefusal(track, body)
                        }
                        PushFailure.Drop -> tickDao.deleteById(entity.localId)
                        PushFailure.Retry -> {
                            syncManager.reportPushStatus(PushStatus.Error(SyncErrorKind.ServerError, "HTTP ${e.code()}"))
                            return Result.retry()
                        }
                    }
                } catch (e: SSLException) {
                    syncManager.reportPushStatus(PushStatus.Error(SyncErrorKind.UntrustedCertificate, e.message))
                    return Result.retry()
                } catch (e: IOException) {
                    syncManager.reportPushStatus(PushStatus.Error(SyncErrorKind.ServerUnreachable, e.message))
                    return Result.retry()
                }
            }
        } catch (e: CancellationException) {
            // Stopped mid-push: WorkManager cancels the worker when JobScheduler drops it, e.g.
            // because the network lost validation. None of the catches above see that, so the
            // status stayed Pushing for good: the journal's chip vanished and the widgets showed
            // a dead "syncing" icon instead of the offline symbol. An interrupted attempt says
            // nothing new about the server, so put back what we knew before it.
            syncManager.reportPushStatus(before.takeUnless { it is PushStatus.Pushing } ?: PushStatus.Idle)
            throw e
        }
        syncManager.reportPushStatus(PushStatus.Idle)
        return Result.success()
    }

    /**
     * Once per track: Tickbuddy refuses a tick with 423 because the track is archived. Another
     * reason (a future kind of read-only track) shows the server's own message, or nothing.
     */
    private fun noteRefusal(track: TrackEntity, body: String?) {
        if (track.localId in refusals) return
        val text = if (ocsErrorReason(body, json) == "archived") {
            "${track.name} is archived, so the change wasn't saved."
        } else {
            ocsErrorMessage(body, json)
        }
        if (text != null) refusals[track.localId] = text
    }

    private suspend fun pushOne(track: TrackEntity, entity: TickEntity) {
        val serverId = track.serverId ?: return // unsynced track — wait until pull picks it up

        when (val type = TrackType.fromWire(track.type)) {
            TrackType.Counter -> pushCounter(track, serverId, entity)
            TrackType.YesNo -> pushBoolean(track, serverId, entity)
            // Read-only, so only a bug can queue one. Drop it unsent rather than guess what the
            // server would make of it, or the "unsaved changes" chip would stay on for good.
            is TrackType.Unknown -> {
                Log.w(TAG, "Dropping a tick on a track of unknown type '${type.wireValue}'")
                tickDao.deleteById(entity.localId)
            }
        }
    }

    private suspend fun pushCounter(track: TrackEntity, serverId: Long, entity: TickEntity) {
        val desired = if (entity.deleted) 0 else entity.value
        val response = api.setTick(trackId = serverId, date = entity.date, value = desired).ocs.data
        finalize(entity, newValue = response.value, newServerId = entity.serverId)
    }

    private suspend fun pushBoolean(track: TrackEntity, serverId: Long, entity: TickEntity) {
        val desiredTicked = !entity.deleted && entity.value > 0

        // Fetch current server state for this single day to avoid replaying a stale toggle.
        val sameDay = api.getTicks(from = entity.date, to = entity.date, includeAllTypes = true).ocs.data
        val serverTicked = sameDay.any { it.trackId == serverId && it.date == entity.date }

        if (serverTicked != desiredTicked) {
            val response = api.toggleTick(trackId = serverId, date = entity.date).ocs.data
            // After the toggle, server's state is response.ticked. If it now matches desired, finalize;
            // otherwise something concurrent happened — wait for the next pull to reconcile.
            if (response.ticked != desiredTicked) return
        }
        finalize(entity, newValue = if (desiredTicked) 1 else 0, newServerId = entity.serverId)
    }

    private suspend fun finalize(entity: TickEntity, newValue: Int, newServerId: Long?) {
        database.withTransaction {
            if (newValue == 0) {
                tickDao.deleteById(entity.localId)
            } else {
                tickDao.update(
                    entity.copy(
                        value = newValue,
                        serverId = newServerId ?: entity.serverId,
                        dirty = false,
                        deleted = false,
                    ),
                )
            }
        }
    }
}

/** What the drain does with one dirty row after an HTTP error. */
internal enum class PushFailure { SignOut, Refused, Drop, Retry }

/**
 * [reason] is `ocs.data.reason` from the error body ([ocsErrorReason]), null when absent. 423
 * means "read-only track" only because every call the drain makes is to Tickbuddy's own API.
 */
internal fun pushFailureFor(code: Int, reason: String?): PushFailure = when {
    code == 401 -> PushFailure.SignOut
    code == 423 -> PushFailure.Refused // read-only track (archived): drop it and tell the user
    // Tickbuddy's own answer (it carries a reason): track deleted elsewhere, or a bug. Drop it,
    // say nothing. Without a reason it is Nextcloud's own 404 (Tickbuddy disabled, e.g. during
    // a Nextcloud upgrade), a proxy or an older Tickbuddy: retry, or queued ticks would be lost.
    (code == 404 || code == 400) && reason != null -> PushFailure.Drop
    else -> PushFailure.Retry // stop and retry with backoff
}
