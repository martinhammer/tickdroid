package com.martinhammer.tickdroid.data.repository

import com.martinhammer.tickdroid.data.local.TrackRunDao
import com.martinhammer.tickdroid.domain.TrackRun
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Streaks as last read from `GET /api/stats` (written only by the pull). */
@Singleton
class TrackRunRepository @Inject constructor(private val trackRunDao: TrackRunDao) {

    /** Keyed by track local id. A track the stats answer left out has no entry. */
    fun observeRuns(): Flow<Map<Long, TrackRun>> =
        trackRunDao.observeAll().map { rows ->
            rows.associate {
                it.trackLocalId to TrackRun(
                    isStreak = it.isStreak,
                    length = it.length,
                    upToYesterday = it.upToYesterday,
                    maxValue = it.maxValue,
                )
            }
        }
}
