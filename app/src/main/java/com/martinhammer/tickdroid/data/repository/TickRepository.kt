package com.martinhammer.tickdroid.data.repository

import com.martinhammer.tickdroid.data.local.TickDao
import com.martinhammer.tickdroid.data.local.TickEntity
import com.martinhammer.tickdroid.data.sync.SyncScheduler
import com.martinhammer.tickdroid.domain.Tick
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TickRepository @Inject constructor(
    private val tickDao: TickDao,
    private val syncScheduler: SyncScheduler,
) {
    fun observeRange(from: LocalDate, to: LocalDate): Flow<Map<TickKey, Tick>> =
        tickDao.observeRange(from.toString(), to.toString()).map { rows ->
            rows.associate { entity ->
                val tick = entity.toDomain()
                TickKey(tick.trackLocalId, tick.date) to tick
            }
        }

    /**
     * Flip a boolean tick's desired state for [date]. Writes to Room with `dirty=1`
     * and queues a push.
     */
    suspend fun toggleBoolean(trackLocalId: Long, date: LocalDate) {
        val key = date.toString()
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
            existing.serverId == null -> {
                // Was a local-only insert that hasn't synced yet; just drop it.
                tickDao.deleteById(existing.localId)
            }
            else -> {
                // Synced "on" row → mark for removal.
                tickDao.update(
                    existing.copy(value = 0, deleted = true, dirty = true)
                )
            }
        }
        syncScheduler.schedulePushNow()
    }

    /**
     * Adjust a counter tick by [delta], clamped to [0, ∞). 0 deletes the row server-side.
     * No-op if the resulting value is unchanged (e.g. decrement at 0).
     */
    suspend fun adjustCounter(trackLocalId: Long, date: LocalDate, delta: Int) {
        val key = date.toString()
        val existing = tickDao.find(trackLocalId, key)
        val current = if (existing == null || existing.deleted) 0 else existing.value
        val next = (current + delta).coerceAtLeast(0)
        if (next == current && existing?.dirty != true) return

        when {
            next > 0 && existing == null -> {
                tickDao.upsert(
                    TickEntity(
                        serverId = null,
                        trackLocalId = trackLocalId,
                        date = key,
                        value = next,
                        dirty = true,
                    )
                )
            }
            next > 0 -> {
                tickDao.update(
                    existing!!.copy(value = next, deleted = false, dirty = true)
                )
            }
            existing == null -> return // nothing to do
            existing.serverId == null -> tickDao.deleteById(existing.localId)
            else -> tickDao.update(existing.copy(value = 0, deleted = true, dirty = true))
        }
        syncScheduler.schedulePushNow()
    }
}

data class TickKey(val trackLocalId: Long, val date: LocalDate)
