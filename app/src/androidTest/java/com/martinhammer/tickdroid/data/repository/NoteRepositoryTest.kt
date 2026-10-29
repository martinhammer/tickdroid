package com.martinhammer.tickdroid.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.martinhammer.tickdroid.data.local.NoteEntity
import com.martinhammer.tickdroid.data.local.TickdroidDatabase
import com.martinhammer.tickdroid.data.local.TrackEntity
import com.martinhammer.tickdroid.data.sync.SyncScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class NoteRepositoryTest {

    private lateinit var db: TickdroidDatabase
    private lateinit var scheduler: RecordingScheduler
    private lateinit var repo: NoteRepository
    private val date = LocalDate.parse("2026-10-28")
    private var track = 0L

    private class RecordingScheduler(context: Context) : SyncScheduler(context) {
        var pushNowCalls = 0
        override fun schedulePushNow() { pushNowCalls++ }
        override fun schedulePeriodicPush() {}
        override fun cancelAll() {}
    }

    @Before fun setUp() = runTest {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, TickdroidDatabase::class.java).build()
        scheduler = RecordingScheduler(context)
        repo = NoteRepository(db.noteDao(), db, scheduler)
        track = db.trackDao().insert(TrackEntity(serverId = 1L, name = "T", type = "boolean", sortOrder = 0, private = false))
    }

    @After fun tearDown() {
        db.close()
    }

    private suspend fun row() = db.noteDao().find(track, date.toString())

    private suspend fun seedClean(text: String) {
        db.noteDao().upsert(NoteEntity(trackLocalId = track, date = date.toString(), text = text))
    }

    @Test fun firstSave_onACleanNote_recordsItAsTheBase() = runTest {
        seedClean("server's")
        repo.saveNote(track, date, "  mine \n")
        val row = row()!!
        assertEquals("mine", row.text)
        assertTrue(row.dirty)
        assertEquals("server's", row.baseText)
        assertEquals(1, scheduler.pushNowCalls)
    }

    @Test fun secondSave_keepsTheFirstBase() = runTest {
        seedClean("server's")
        repo.saveNote(track, date, "one")
        repo.saveNote(track, date, "two")
        assertEquals("two", row()!!.text)
        assertEquals("server's", row()!!.baseText)
    }

    @Test fun newNote_baseIsEmpty() = runTest {
        repo.saveNote(track, date, "new")
        assertEquals("", row()!!.baseText)
    }

    @Test fun savingTheShownText_writesAndSchedulesNothing() = runTest {
        seedClean("same")
        repo.saveNote(track, date, " same ")
        assertEquals(false, row()!!.dirty)
        assertNull(row()!!.baseText)
        assertEquals(0, scheduler.pushNowCalls)
    }

    @Test fun emptyOnACleanRow_leavesADirtyEmptyRow() = runTest {
        seedClean("server's")
        repo.saveNote(track, date, "")
        val row = row()!!
        assertEquals("", row.text)
        assertTrue(row.dirty)
        assertEquals("server's", row.baseText)
        assertEquals(1, scheduler.pushNowCalls)
    }
}
