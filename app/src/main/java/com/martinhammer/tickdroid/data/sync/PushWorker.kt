package com.martinhammer.tickdroid.data.sync

import android.content.Context
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
import androidx.room.withTransaction
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import retrofit2.HttpException
import java.io.IOException

/**
 * Drains every dirty tick row to the server. Boolean ticks use the spec's replay-safe
 * pattern (fetch current server state for the day; only `/toggle` if it differs).
 * Counter ticks use the idempotent `/set`.
 *
 * On 401 we sign the user out and stop retrying. Other failures (5xx, IO) → retry with
 * exponential backoff. v1 has no track CRUD on mobile, so we never push tracks.
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
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = syncManager.runExclusive { drain() }

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

        syncManager.reportPushStatus(PushStatus.Pushing)
        for (entity in dirty) {
            try {
                pushOne(entity)
            } catch (e: HttpException) {
                if (e.code() == 401) {
                    authRepository.signOut()
                    syncManager.reportPushStatus(PushStatus.Error("Session expired"))
                    return Result.failure()
                }
                syncManager.reportPushStatus(PushStatus.Error("HTTP ${e.code()}"))
                return Result.retry()
            } catch (e: IOException) {
                syncManager.reportPushStatus(PushStatus.Error(e.message ?: "Network error"))
                return Result.retry()
            }
        }
        syncManager.reportPushStatus(PushStatus.Idle)
        return Result.success()
    }

    private suspend fun pushOne(entity: TickEntity) {
        val track = trackDao.getAll().firstOrNull { it.localId == entity.trackLocalId } ?: return
        val serverId = track.serverId ?: return // unsynced track — wait until pull picks it up

        when (TrackKind.fromWire(track.type)) {
            TrackKind.COUNTER -> pushCounter(track, serverId, entity)
            TrackKind.BOOLEAN -> pushBoolean(track, serverId, entity)
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
        val sameDay = api.getTicks(from = entity.date, to = entity.date).ocs.data
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

    private enum class TrackKind {
        COUNTER, BOOLEAN;

        companion object {
            fun fromWire(wire: String): TrackKind =
                if (wire.equals("counter", ignoreCase = true)) COUNTER else BOOLEAN
        }
    }
}
