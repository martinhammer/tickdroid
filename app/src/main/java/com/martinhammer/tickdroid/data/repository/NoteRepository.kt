package com.martinhammer.tickdroid.data.repository

import com.martinhammer.tickdroid.data.local.NoteDao
import com.martinhammer.tickdroid.data.local.NoteEntity
import com.martinhammer.tickdroid.data.local.TickdroidDatabase
import com.martinhammer.tickdroid.data.sync.SyncScheduler
import com.martinhammer.tickdroid.domain.normaliseNote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoteRepository @Inject constructor(
    private val noteDao: NoteDao,
    private val database: TickdroidDatabase,
    private val syncScheduler: SyncScheduler,
) {
    /** The notes to show in the range (never ""), by track and day. */
    fun observeRange(from: LocalDate, to: LocalDate): Flow<Map<TickKey, String>> =
        noteDao.observeRange(from.toString(), to.toString()).map { rows ->
            rows.associate { TickKey(it.trackLocalId, LocalDate.parse(it.date)) to it.text }
        }

    /**
     * Saves the day's note as [raw] (normalised; "" deletes it) and queues a push. Nothing is
     * written when it equals what the phone shows.
     *
     * The first change since the last sync records the server's note as `baseText` (a clean row
     * holds it); later edits keep that base, so the push can tell the phone's new lines from
     * another writer's. A save never makes a row clean and never deletes one: only the push
     * settles a row, as only the push deletes ticks.
     */
    suspend fun saveNote(trackLocalId: Long, date: LocalDate, raw: String) {
        val text = normaliseNote(raw)
        val key = date.toString()
        val changed = database.withTransactionUncancellable {
            val row = noteDao.find(trackLocalId, key)
            val shown = row?.text ?: ""
            if (text == shown) return@withTransactionUncancellable false
            val base = if (row?.dirty == true) row.baseText ?: "" else shown
            if (row == null) {
                noteDao.upsert(
                    NoteEntity(trackLocalId = trackLocalId, date = key, text = text, dirty = true, baseText = base),
                )
            } else {
                noteDao.update(row.copy(text = text, dirty = true, baseText = base))
            }
            true
        }
        if (changed) syncScheduler.schedulePushNow()
    }
}
