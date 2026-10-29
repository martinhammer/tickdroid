package com.martinhammer.tickdroid.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class NoteDaoTest {

    private lateinit var db: TickdroidDatabase

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, TickdroidDatabase::class.java).build()
    }

    @After fun tearDown() {
        db.close()
    }

    private suspend fun seedTrack(): Long = db.trackDao().insert(
        TrackEntity(serverId = 1L, name = "T", type = "boolean", sortOrder = 0, private = false),
    )

    @Test fun observeRange_leavesOutDirtyEmptyRows_getRangeHasThem() = runTest {
        val track = seedTrack()
        db.noteDao().upsert(NoteEntity(trackLocalId = track, date = "2026-10-27", text = "kept"))
        db.noteDao().upsert(NoteEntity(trackLocalId = track, date = "2026-10-28", text = "", dirty = true, baseText = "old"))

        assertEquals(listOf("kept"), db.noteDao().observeRange("2026-10-01", "2026-10-31").first().map { it.text })
        assertEquals(2, db.noteDao().getRange("2026-10-01", "2026-10-31").size)
    }

    @Test fun oneNotePerTrackAndDay() = runTest {
        val track = seedTrack()
        db.noteDao().upsert(NoteEntity(trackLocalId = track, date = "2026-10-28", text = "a"))
        // REPLACE on the unique index: the second row takes the day.
        db.noteDao().upsert(NoteEntity(trackLocalId = track, date = "2026-10-28", text = "b"))
        assertEquals(listOf("b"), db.noteDao().getRange("2026-10-28", "2026-10-28").map { it.text })

        val first = db.noteDao().find(track, "2026-10-28")!!
        val other = db.noteDao().upsert(NoteEntity(trackLocalId = track, date = "2026-10-27", text = "c"))
        try {
            db.noteDao().update(db.noteDao().findById(other)!!.copy(date = first.date))
            fail("the unique index allowed two notes on one day")
        } catch (_: SQLiteConstraintException) {
        }
    }
}
