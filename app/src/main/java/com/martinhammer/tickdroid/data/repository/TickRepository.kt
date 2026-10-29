package com.martinhammer.tickdroid.data.repository

import androidx.room.withTransaction
import com.martinhammer.tickdroid.data.local.TickDao
import com.martinhammer.tickdroid.data.local.TickEntity
import com.martinhammer.tickdroid.data.local.TickdroidDatabase
import com.martinhammer.tickdroid.data.sync.SyncScheduler
import com.martinhammer.tickdroid.domain.Tick
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TickRepository @Inject constructor(
    private val tickDao: TickDao,
    private val database: TickdroidDatabase,
    private val syncScheduler: SyncScheduler,
) {
    fun observeRange(from: LocalDate, to: LocalDate): Flow<Map<TickKey, Tick>> =
        tickDao.observeRange(from.toString(), to.toString()).map { rows ->
            rows.associate { entity ->
                val tick = entity.toDomain()
                TickKey(tick.trackLocalId, tick.date) to tick
            }
        }

    /** Whether any tick or note is `dirty=1` and waiting to be pushed (see `TickDao.observeHasUnsent`). */
    fun observeHasUnsent(): Flow<Boolean> = tickDao.observeHasUnsent()

    /**
     * Flip a boolean tick's desired state for [date]. Writes to Room with `dirty=1`
     * and queues a push.
     *
     * Never deletes a row, only marks it for removal: a null `serverId` doesn't mean "never
     * reached the server" (a push doesn't fill it in, only a pull does), so dropping the row here
     * could leave the server's tick in place. Only the push deletes, once the server has answered.
     */
    suspend fun toggleBoolean(trackLocalId: Long, date: LocalDate) {
        val key = date.toString()
        withTransactionUncancellable {
            val existing = tickDao.find(trackLocalId, key)
            when {
                existing == null -> {
                    tickDao.upsert(
                        TickEntity(
                            serverId = null,
                            trackLocalId = trackLocalId,
                            date = key,
                            value = 1,
                            dirty = true,
                        )
                    )
                }
                existing.deleted -> {
                    // Pending-removal row — undo the removal, push the "on" state.
                    tickDao.update(
                        existing.copy(value = 1, deleted = false, dirty = true)
                    )
                }
                else -> {
                    // "On" row → mark for removal.
                    tickDao.update(
                        existing.copy(value = 0, deleted = true, dirty = true)
                    )
                }
            }
        }
        syncScheduler.schedulePushNow()
    }

    /**
     * Adjust a counter tick by [delta], clamped to [0, ∞), recording the change in `pendingDelta`
     * ([counterTap]). 0 deletes the row server-side; like [toggleBoolean], only the push deletes
     * it locally. No-op if the resulting value is unchanged (e.g. decrement at 0).
     */
    suspend fun adjustCounter(trackLocalId: Long, date: LocalDate, delta: Int) {
        val key = date.toString()
        val changed = withTransactionUncancellable {
            val existing = tickDao.find(trackLocalId, key)
            val row = counterTap(existing, trackLocalId, key, delta) ?: return@withTransactionUncancellable false
            if (existing == null) tickDao.upsert(row) else tickDao.update(row)
            true
        }
        if (changed) syncScheduler.schedulePushNow()
    }

    private suspend fun <T> withTransactionUncancellable(block: suspend () -> T): T =
        database.withTransactionUncancellable(block)
}

/**
 * Run [block] in a Room transaction that a cancelled caller cannot tear down.
 *
 * These writes are launched from `viewModelScope`, so navigating away mid-tap cancels the
 * caller. Room reacts to that by closing the pooled connection while the transaction block
 * is still executing on its own executor thread; the next statement then throws
 * `SQLITE_MISUSE` ("connection is closed") on a thread with no cancellation handling, which
 * surfaces as an uncaught fatal. NonCancellable also means a tap the user already made is
 * never silently dropped because the screen went away.
 */
internal suspend fun <T> TickdroidDatabase.withTransactionUncancellable(block: suspend () -> T): T =
    withContext(NonCancellable) { withTransaction { block() } }

data class TickKey(val trackLocalId: Long, val date: LocalDate)

/**
 * What a counter tap of [delta] writes over [existing] (null: no row yet), or null when the value
 * doesn't change. Records the change the user saw, not the taps: −1 twice on 1 records −1, as the
 * value stops at 0 (and so does the server's).
 *
 * A clean row starts counting from 0; a dirty row with `pendingDelta == null` is absolute (queued
 * before v4) and stays so. Taps that cancel out leave the row clean again, so the "unsaved
 * changes" chip doesn't show for nothing, unless an increment awaits its answer (`sentKey`): the
 * server may already have that delta, which the cancelling taps now have to undo. A row at 0 stays
 * dirty either way, for the push to settle (delete).
 */
internal fun counterTap(existing: TickEntity?, trackLocalId: Long, date: String, delta: Int): TickEntity? {
    val current = if (existing == null || existing.deleted) 0 else existing.value
    val next = (current + delta).coerceAtLeast(0)
    val change = next - current
    if (change == 0) return null
    if (existing == null) {
        return TickEntity(
            serverId = null,
            trackLocalId = trackLocalId,
            date = date,
            value = next,
            dirty = true,
            pendingDelta = change,
        )
    }
    val absolute = existing.dirty && existing.pendingDelta == null
    val pending = if (absolute) null else (existing.pendingDelta ?: 0) + change
    val cancelledOut = pending == 0 && next > 0 && existing.sentKey == null
    return existing.copy(
        value = next,
        deleted = next == 0,
        dirty = !cancelledOut,
        pendingDelta = if (cancelledOut) null else pending,
    )
}
