package com.martinhammer.tickdroid.data.repository

import com.martinhammer.tickdroid.data.local.TrackPrefsDao
import com.martinhammer.tickdroid.data.local.TrackPrefsEntity
import com.martinhammer.tickdroid.domain.TrackPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackPrefsRepository @Inject constructor(
    private val dao: TrackPrefsDao,
) {
    /** Map of serverId → user-set prefs. Tracks without an entry use defaults. */
    fun observeAll(): Flow<Map<Long, TrackPrefs>> =
        dao.observeAll().map { rows ->
            rows.associate { it.serverId to TrackPrefs(colorKey = it.colorKey, emoji = it.emoji) }
        }

    suspend fun setColorKey(serverId: Long, colorKey: String?) {
        val current = dao.findByServerId(serverId)
        val merged = (current ?: TrackPrefsEntity(serverId = serverId)).copy(colorKey = colorKey)
        if (merged.colorKey == null && merged.emoji == null) {
            dao.deleteByServerId(serverId)
        } else {
            dao.upsert(merged)
        }
    }

    suspend fun setEmoji(serverId: Long, emoji: String?) {
        val current = dao.findByServerId(serverId)
        val merged = (current ?: TrackPrefsEntity(serverId = serverId)).copy(emoji = emoji)
        if (merged.colorKey == null && merged.emoji == null) {
            dao.deleteByServerId(serverId)
        } else {
            dao.upsert(merged)
        }
    }

    suspend fun reset(serverId: Long) {
        dao.deleteByServerId(serverId)
    }
}
