package com.martinhammer.tickdroid.data.repository

import com.martinhammer.tickdroid.data.local.TrackPrefsDao
import com.martinhammer.tickdroid.data.local.TrackPrefsEntity
import com.martinhammer.tickdroid.domain.CellLabel
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
            rows.associate {
                it.serverId to TrackPrefs(
                    colorKey = it.colorKey,
                    emoji = it.emoji,
                    cellLabel = CellLabel.fromKey(it.cellLabel),
                )
            }
        }

    suspend fun setColorKey(serverId: Long, colorKey: String?) = update(serverId) { it.copy(colorKey = colorKey) }

    suspend fun setEmoji(serverId: Long, emoji: String?) = update(serverId) { it.copy(emoji = emoji) }

    /** "Journal cells" on a Choice track. [CellLabel.Short] is the default, stored as null. */
    suspend fun setCellLabel(serverId: Long, cellLabel: CellLabel) =
        update(serverId) { it.copy(cellLabel = cellLabel.takeUnless { c -> c == CellLabel.Short }?.key) }

    /** Applies [change]; a row whose every field ends up null is deleted rather than kept. */
    private suspend fun update(serverId: Long, change: (TrackPrefsEntity) -> TrackPrefsEntity) {
        val current = dao.findByServerId(serverId)
        val merged = change(current ?: TrackPrefsEntity(serverId = serverId))
        if (merged.colorKey == null && merged.emoji == null && merged.cellLabel == null) {
            dao.deleteByServerId(serverId)
        } else {
            dao.upsert(merged)
        }
    }
}
