package com.martinhammer.tickdroid.data.repository

import com.martinhammer.tickdroid.data.local.TickDao
import com.martinhammer.tickdroid.domain.Tick
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TickRepository @Inject constructor(
    private val tickDao: TickDao,
) {
    fun observeRange(from: LocalDate, to: LocalDate): Flow<Map<TickKey, Tick>> =
        tickDao.observeRange(from.toString(), to.toString()).map { rows ->
            rows.associate { entity ->
                val tick = entity.toDomain()
                TickKey(tick.trackLocalId, tick.date) to tick
            }
        }
}

data class TickKey(val trackLocalId: Long, val date: LocalDate)
