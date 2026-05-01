package com.martinhammer.tickdroid.data.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.turbine.test
import com.martinhammer.tickdroid.data.local.TickdroidDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TrackPrefsRepositoryTest {

    private lateinit var db: TickdroidDatabase
    private lateinit var repo: TrackPrefsRepository

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, TickdroidDatabase::class.java).build()
        repo = TrackPrefsRepository(db.trackPrefsDao())
    }

    @After fun tearDown() {
        db.close()
    }

    @Test fun setColorKey_thenSetEmoji_mergesIntoSingleRow() = runTest {
        repo.setColorKey(serverId = 7L, colorKey = "blue")
        repo.setEmoji(serverId = 7L, emoji = "🎯")

        val prefs = db.trackPrefsDao().findByServerId(7L)!!
        assertEquals("blue", prefs.colorKey)
        assertEquals("🎯", prefs.emoji)
    }

    @Test fun nullingBothFields_deletesRow() = runTest {
        repo.setColorKey(7L, "blue")
        repo.setEmoji(7L, "🎯")

        repo.setColorKey(7L, null)
        repo.setEmoji(7L, null)

        assertNull(db.trackPrefsDao().findByServerId(7L))
    }

    @Test fun reset_deletesRow() = runTest {
        repo.setColorKey(7L, "blue")

        repo.reset(7L)

        assertNull(db.trackPrefsDao().findByServerId(7L))
    }

    @Test fun observeAll_emitsKeyedMap() = runTest {
        repo.observeAll().test {
            assertEquals(emptyMap<Long, Any>(), awaitItem())
            repo.setColorKey(1L, "red")
            val withRed = awaitItem()
            assertEquals(1, withRed.size)
            assertEquals("red", withRed[1L]?.colorKey)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
