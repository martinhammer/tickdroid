package com.martinhammer.tickdroid.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.auth.AuthState
import com.martinhammer.tickdroid.data.local.NoteDao
import com.martinhammer.tickdroid.data.local.NoteEntity
import com.martinhammer.tickdroid.data.local.TickDao
import com.martinhammer.tickdroid.data.local.TickEntity
import com.martinhammer.tickdroid.data.local.TickdroidDatabase
import com.martinhammer.tickdroid.data.local.TrackDao
import com.martinhammer.tickdroid.data.local.TrackEntity
import com.martinhammer.tickdroid.data.remote.TickbuddyApi
import com.martinhammer.tickdroid.data.remote.ocsErrorMessage
import com.martinhammer.tickdroid.data.remote.ocsErrorReason
import com.martinhammer.tickdroid.data.time.Clock
import com.martinhammer.tickdroid.domain.MAX_NOTE_CHARS
import com.martinhammer.tickdroid.domain.NotePushPlan
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.domain.planNotePush
import androidx.room.withTransaction
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drains every dirty tick row to the server, by kind of day ([ServerFeatures], as last seen):
 * - **Yes/no**, with `booleanSet`: `set` with 1 or 0, safe to repeat. Without it, the spec's
 *   replay-safe pattern: read the server's day, and `toggle` only if it differs.
 * - **Counter** whose change is known (`pendingDelta`), with `counterIncrement` and
 *   `idempotencyKey`: `increment` by that change, so other devices' and scripts' changes are kept.
 *   An increment isn't safe to repeat, so its key and delta are written to Room before it goes
 *   out and resent unchanged until an answer arrives; the server answers a repeat as it answered
 *   the first. Otherwise, and for a change queued before v4 (`pendingDelta == null`): `set` with
 *   the absolute value.
 *
 * Then every dirty note (capability `notes`; without it they stay queued): read the server's note
 * for the day and send what [planNotePush] says. The phone's version when the server's note is
 * unchanged since the change began (or the server has no `notesAppend`); when it changed
 * elsewhere, only the phone's new lines, with `append`, so nothing on the server is removed.
 *
 * Each row is re-read just before it is sent (taps don't take the sync lock), and settled
 * against what it holds by the time the answer arrives, so a tap made during a request is kept
 * and sent by the next run. Only the push deletes rows: once the server says the day is empty.
 *
 * After an HTTP error on one row ([pushFailureFor]): 401 signs the user out and stops for good;
 * 423 (a read-only, i.e. archived, track) drops that tick, carries on and tells the user once per
 * track ([PushNotices], after the pull); 404 / 400 / 422 carrying a Tickbuddy `reason` (track
 * deleted elsewhere, or a bug) drops it silently and carries on; ticks and notes alike. Anything else, and IO / TLS failures,
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
    private val noteDao: NoteDao,
    private val trackDao: TrackDao,
    private val authRepository: AuthRepository,
    private val syncManager: SyncManager,
    private val clock: Clock,
    private val json: Json,
    private val pushNotices: PushNotices,
    private val serverFeatures: ServerFeatures,
) : CoroutineWorker(appContext, params) {

    /**
     * What to tell the user about ticks the server refused this run, one entry per track (keyed
     * by its local id) in queue order. WorkManager makes a new worker per run, so "once" is once
     * per sync.
     */
    private val refusals = LinkedHashMap<Long, String>()

    /** Notes whose merge would have been too long, one entry per track, shown after [refusals]. */
    private val tooLong = LinkedHashMap<Long, String>()

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
        if (drainResult != Result.failure()) (refusals.values + tooLong.values).forEach { pushNotices.show(it) }
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

        val dirtyTicks = tickDao.getDirty()
        // Without the flag notes stay queued, and go up once the server has it again.
        val dirtyNotes = if (serverFeatures.notes.value) noteDao.getDirty() else emptyList()
        if (dirtyTicks.isEmpty() && dirtyNotes.isEmpty()) {
            syncManager.reportPushStatus(PushStatus.Idle)
            return Result.success()
        }

        val before = syncManager.pushStatus.value
        syncManager.reportPushStatus(PushStatus.Pushing)
        try {
            val tracks = trackDao.getAll().associateBy { it.localId }
            for (queued in dirtyTicks) {
                val track = tracks[queued.trackLocalId] ?: continue
                // The snapshot only gives the order: a tap may have changed the row since.
                val entity = tickDao.findById(queued.localId)?.takeIf { it.dirty } ?: continue
                send(track, "tick", drop = { tickDao.deleteById(entity.localId) }) { pushOne(track, entity) }
                    ?.let { return it }
            }
            for (queued in dirtyNotes) {
                val track = tracks[queued.trackLocalId] ?: continue
                val note = noteDao.findById(queued.localId)?.takeIf { it.dirty } ?: continue
                send(track, "note", drop = { noteDao.deleteById(note.localId) }) { pushNote(track, note) }
                    ?.let { return it }
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
     * Runs one row's request ([block]) with the drain's error handling. Null: carry on with the
     * queue (also after [drop]ping a refused row). Otherwise the drain stops with that result.
     */
    private suspend fun send(
        track: TrackEntity,
        kind: String,
        drop: suspend () -> Unit,
        block: suspend () -> Unit,
    ): Result? {
        try {
            block()
        } catch (e: HttpException) {
            val body = e.response()?.errorBody()?.string() // can only be read once
            when (pushFailureFor(e.code(), ocsErrorReason(body, json))) {
                PushFailure.SignOut -> {
                    authRepository.signOut()
                    syncManager.reportPushStatus(PushStatus.Error(SyncErrorKind.ServerError, "Session expired"))
                    return Result.failure()
                }
                PushFailure.Refused -> {
                    drop()
                    noteRefusal(track, body)
                }
                PushFailure.Drop -> {
                    // 422 is a reused Idempotency-Key and 400 a value the server refused: the
                    // phone checks what the server checks, so only a bug here can cause either.
                    if (e.code() != 404) Log.w(TAG, "Dropping a $kind refused with HTTP ${e.code()}")
                    drop()
                }
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
        return null
    }

    /**
     * Once per track: Tickbuddy refuses a tick or a note with 423 because the track is archived. Another
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
            TrackType.Counter -> pushCounter(serverId, entity)
            TrackType.YesNo -> pushBoolean(serverId, entity)
            // Read-only, so only a bug can queue one. Drop it unsent rather than guess what the
            // server would make of it, or the "unsaved changes" chip would stay on for good.
            is TrackType.Unknown -> {
                Log.w(TAG, "Dropping a tick on a track of unknown type '${type.wireValue}'")
                tickDao.deleteById(entity.localId)
            }
        }
    }

    private suspend fun pushCounter(serverId: Long, entity: TickEntity) {
        val pending = entity.pendingDelta
        val absolute = if (entity.deleted) 0 else entity.value
        when {
            pending == null -> {
                val response = api.setTick(trackId = serverId, date = entity.date, value = absolute).ocs.data
                settleAbsolute(entity, response.value)
            }
            // Taps that cancelled out: nothing to send (never an increment by 0, which the
            // server refuses). Settles, i.e. deletes, a row at 0 that never reached the server.
            pending == 0 && entity.sentKey == null -> settleDelta(entity.localId, sent = 0, serverValue = entity.value)
            serverFeatures.counterIncrement.value && serverFeatures.idempotencyKey.value -> pushIncrement(serverId, entity)
            else -> {
                // An older server: the absolute value. The bookkeeping carries on (and any key of
                // a server rolled back since is dropped), so nothing is lost when it's updated.
                val response = api.setTick(trackId = serverId, date = entity.date, value = absolute).ocs.data
                settleDelta(entity.localId, sent = pending, serverValue = response.value)
            }
        }
    }

    /**
     * Sends the row's increment that awaits an answer, or claims a new one first. With no answer
     * (IO, TLS, a retried HTTP error, a cancelled worker) nothing changes, so the next run resends
     * exactly the same request, whatever was tapped since: the draft standard forbids reusing a
     * key with a different body, and a new key could count a lost-but-applied increment twice.
     * Taps made meanwhile go up in the request after it. The server keeps keys for 30 days.
     */
    private suspend fun pushIncrement(serverId: Long, entity: TickEntity) {
        val (key, delta) = claimIncrement(entity.localId) ?: return
        // A Structured Fields string (RFC 9651), so in double quotes.
        val response = api.incrementTick(
            trackId = serverId,
            date = entity.date,
            delta = delta,
            idempotencyKey = "\"$key\"",
        ).ocs.data
        settleDelta(entity.localId, sent = delta, serverValue = response.value)
    }

    /** The row's key and delta, written before the request leaves; null if there's nothing to send. */
    private suspend fun claimIncrement(localId: Long): Pair<String, Int>? = transaction {
        val current = tickDao.findById(localId)?.takeIf { it.dirty } ?: return@transaction null
        val sentKey = current.sentKey
        val sentDelta = current.sentDelta
        if (sentKey != null && sentDelta != null) return@transaction sentKey to sentDelta
        // Changed since the drain read it: the next run takes it from the top.
        val delta = current.pendingDelta?.takeIf { it != 0 } ?: return@transaction null
        val key = UUID.randomUUID().toString()
        tickDao.update(current.copy(sentKey = key, sentDelta = delta))
        key to delta
    }

    private suspend fun pushBoolean(serverId: Long, entity: TickEntity) {
        val desiredTicked = entity.isTicked()

        if (serverFeatures.booleanSet.value) {
            val response = api.setYesNo(trackId = serverId, date = entity.date, value = if (desiredTicked) 1 else 0).ocs.data
            // The server's answer wins (with Prefer: handling=strict it can only differ by a bug).
            settleYesNo(entity.localId, sent = desiredTicked, serverTicked = response.value > 0)
            return
        }

        // Fetch current server state for this single day to avoid replaying a stale toggle.
        val sameDay = api.getTicks(from = entity.date, to = entity.date, includeAllTypes = true).ocs.data
        val serverTicked = sameDay.any { it.trackId == serverId && it.date == entity.date }

        if (serverTicked != desiredTicked) {
            val response = api.toggleTick(trackId = serverId, date = entity.date).ocs.data
            // After the toggle, server's state is response.ticked. If it now matches desired, settle;
            // otherwise something concurrent happened — wait for the next pull to reconcile.
            if (response.ticked != desiredTicked) return
        }
        settleYesNo(entity.localId, sent = desiredTicked, serverTicked = desiredTicked)
    }

    /**
     * Reads the server's note for the day, then sends what [planNotePush] says. A lost `append` is
     * safe to repeat (the server skips text already there as whole lines). Between the read and a
     * `set` or delete, another writer's change is overwritten: the server has no conditional
     * write for notes, an accepted gap.
     */
    private suspend fun pushNote(track: TrackEntity, note: NoteEntity) {
        val serverId = track.serverId ?: return // unsynced track: wait until a pull picks it up
        val server = api.getNotes(from = note.date, to = note.date, includeAllTypes = true).ocs.data
            .firstOrNull { it.trackId == serverId && it.date == note.date }?.text ?: ""
        val plan = planNotePush(
            phone = note.text,
            base = note.baseText ?: "",
            server = server,
            canAppend = serverFeatures.notesAppend.value,
        )
        when (plan) {
            is NotePushPlan.Settle -> settleNote(note, plan.text)
            is NotePushPlan.Set -> settleNote(note, api.setNote(serverId, note.date, plan.text).ocs.data.text)
            is NotePushPlan.Append -> settleNote(note, api.appendNote(serverId, note.date, plan.text).ocs.data.text)
            NotePushPlan.Delete -> {
                val response = api.deleteNote(serverId, note.date)
                if (!response.isSuccessful) throw HttpException(response)
                settleNote(note, "")
            }
            NotePushPlan.TooLong -> {
                // Nothing sent: the server's note shows again at once.
                settleNote(note, server)
                tooLong.putIfAbsent(
                    track.localId,
                    "The note for ${track.name} would be longer than $MAX_NOTE_CHARS characters, so the change wasn't saved.",
                )
            }
        }
    }

    // The settle steps re-read the row: a tap may have changed it while the request was out.

    /**
     * The server now holds [answer] for the day ("" for none). A row still holding the text that
     * was sent settles clean at [answer] (or goes, when it's ""). A row edited during the request
     * stays dirty with its new text; its base becomes [answer] only when the server now holds
     * exactly what was sent, otherwise it stays, so the next run merges again.
     */
    private suspend fun settleNote(sent: NoteEntity, answer: String) = transaction {
        val current = noteDao.findById(sent.localId) ?: return@transaction
        when {
            current.text != sent.text -> if (answer == sent.text) noteDao.update(current.copy(baseText = answer))
            answer.isEmpty() -> noteDao.deleteById(current.localId)
            else -> noteDao.update(current.copy(text = answer, dirty = false, baseText = null))
        }
    }


    /** A tap that changed the day's desired state during the request leaves it for the next run. */
    private suspend fun settleYesNo(localId: Long, sent: Boolean, serverTicked: Boolean) = transaction {
        val current = tickDao.findById(localId) ?: return@transaction
        if (current.isTicked() != sent) return@transaction
        settleTo(current, if (serverTicked) 1 else 0)
    }

    /** An absolute `set`: settles only if no tap changed the row meanwhile (it stays absolute). */
    private suspend fun settleAbsolute(sent: TickEntity, serverValue: Int) = transaction {
        val current = tickDao.findById(sent.localId) ?: return@transaction
        val unchanged = current.value == sent.value && current.deleted == sent.deleted && current.pendingDelta == null
        if (unchanged) settleTo(current, serverValue)
    }

    /**
     * The server applied [sent] of the row's pending change and is now at [serverValue]. What
     * was tapped since stays pending, on top of the server's value; any increment key is done.
     */
    private suspend fun settleDelta(localId: Long, sent: Int, serverValue: Int) = transaction {
        val current = tickDao.findById(localId) ?: return@transaction
        val remaining = (current.pendingDelta ?: 0) - sent
        if (remaining == 0) {
            settleTo(current, serverValue)
        } else {
            val value = (serverValue + remaining).coerceAtLeast(0)
            tickDao.update(
                current.copy(
                    value = value,
                    deleted = value == 0,
                    dirty = true,
                    pendingDelta = remaining,
                    sentDelta = null,
                    sentKey = null,
                ),
            )
        }
    }

    /** Clean at the server's [value]; at 0 the row goes. */
    private suspend fun settleTo(current: TickEntity, value: Int) {
        if (value == 0) {
            tickDao.deleteById(current.localId)
        } else {
            tickDao.update(
                current.copy(
                    value = value,
                    dirty = false,
                    deleted = false,
                    pendingDelta = null,
                    sentDelta = null,
                    sentKey = null,
                ),
            )
        }
    }

    /**
     * An answer that arrived is recorded even if the worker is being stopped: a cancelled caller
     * would otherwise have Room close the connection mid-transaction (see `SyncManager.schedulePull`).
     */
    private suspend fun <T> transaction(block: suspend () -> T): T =
        withContext(NonCancellable) { database.withTransaction { block() } }

    private fun TickEntity.isTicked() = !deleted && value > 0
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
    // 422 with a reason: an Idempotency-Key reused for another request, which only a bug here
    // can cause (a key's request never changes); drop it like a 400.
    (code == 404 || code == 400 || code == 422) && reason != null -> PushFailure.Drop
    else -> PushFailure.Retry // stop and retry with backoff
}
