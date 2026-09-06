package com.martinhammer.tickdroid.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.auth.CredentialStore
import com.martinhammer.tickdroid.data.auth.Credentials
import com.martinhammer.tickdroid.data.local.TickEntity
import com.martinhammer.tickdroid.data.local.TickdroidDatabase
import com.martinhammer.tickdroid.data.local.TrackEntity
import com.martinhammer.tickdroid.data.prefs.GridDensity
import com.martinhammer.tickdroid.data.prefs.UiPreferences
import com.martinhammer.tickdroid.data.remote.TickbuddyApi
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncCoordinatorTest {

    private lateinit var context: Context
    private lateinit var db: TickdroidDatabase
    private lateinit var prefs: UiPreferences
    private lateinit var store: CredentialStore
    private lateinit var auth: AuthRepository
    private lateinit var scheduler: RecordingScheduler
    private lateinit var coordinator: SyncCoordinator

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Use a file-based DB with the production name so SyncCoordinator's
        // deleteDatabase call hits the same file we're populating in the test.
        context.deleteDatabase(TickdroidDatabase.NAME)
        db = Room.databaseBuilder(context, TickdroidDatabase::class.java, TickdroidDatabase.NAME).build()
        prefs = UiPreferences(context).also { it.clear() }
        store = CredentialStore(context).also { it.clear() }
        auth = AuthRepository(store)
        scheduler = RecordingScheduler(context)
        // Only the mutex matters here — the coordinator takes it around the wipe. No pull runs,
        // so the api is never touched.
        val syncManager = SyncManager(
            api = mockk<TickbuddyApi>(relaxed = true),
            db = db,
            trackDao = db.trackDao(),
            tickDao = db.tickDao(),
            trackPrefsDao = db.trackPrefsDao(),
            authRepository = auth,
        )
        coordinator = SyncCoordinator(auth, scheduler, syncManager, db, prefs)
        coordinator.start()
    }

    @After fun tearDown() {
        coordinator.stop()
        runCatching { db.close() }
        context.deleteDatabase(TickdroidDatabase.NAME)
        store.clear()
        prefs.clear()
    }

    @Test fun signIn_schedulesPushAndPeriodic() = runTest {
        // Initial SignedOut may have already emitted; capture the baseline counts.
        val baselinePush = scheduler.pushNowCalls
        val baselinePeriodic = scheduler.periodicCalls

        auth.signIn(Credentials("https://srv.example", "u", "p"))

        waitFor {
            scheduler.periodicCalls > baselinePeriodic && scheduler.pushNowCalls > baselinePush
        }
        assertEquals(baselinePeriodic + 1, scheduler.periodicCalls)
        assertEquals(baselinePush + 1, scheduler.pushNowCalls)
    }

    @Test fun initialSignedOut_doesNotWipeOrCloseDb() = runTest {
        // Regression: a fresh install starts with AuthState.SignedOut. The coordinator must NOT
        // wipe the DB or close the singleton on this initial emission — otherwise the first
        // sign-in crashes with "connection pool has been closed" the moment Room is touched.
        // Give the coordinator a moment to process the initial emission.
        delay(100)

        // Scheduler's cancelAll should not have fired (no real sign-out happened).
        assertEquals(0, scheduler.cancelAllCalls)

        // Set a pref so we can detect an unwanted clear().
        prefs.setGridDensity(GridDensity.HIGH)

        // The DB must still be writable — the bug was that the singleton's connection pool
        // was closed by SyncCoordinator's initial-SignedOut handler.
        val trackId = db.trackDao().insert(
            TrackEntity(serverId = 1L, name = "T", type = "boolean", sortOrder = 0, private = false),
        )
        assertEquals(GridDensity.HIGH, prefs.gridDensity.value)
        assert(trackId > 0)
    }

    @Test fun signOut_cancelsWipesAndResetsPrefs() = runTest {
        // Sign in first so the SignedOut emission below is a true transition.
        auth.signIn(Credentials("https://srv.example", "u", "p"))
        waitFor { scheduler.pushNowCalls > 0 }
        val cancelBaseline = scheduler.cancelAllCalls

        val trackId = db.trackDao().insert(
            TrackEntity(serverId = 1L, name = "T", type = "boolean", sortOrder = 0, private = false),
        )
        db.tickDao().upsert(
            TickEntity(serverId = 1L, trackLocalId = trackId, date = "2026-04-30", value = 1),
        )
        prefs.setGridDensity(GridDensity.HIGH)
        assertEquals(GridDensity.HIGH, prefs.gridDensity.value)

        auth.signOut()

        // Wait for the SignedOut transition's full handler to run: cancelAll → close+delete DB
        // → prefs.clear all execute sequentially. Watch the last side-effect (prefs reset).
        waitFor {
            scheduler.cancelAllCalls > cancelBaseline &&
                prefs.gridDensity.value == GridDensity.Default
        }

        assertTrue("tracks should be wiped on sign-out", db.trackDao().getAll().isEmpty())
        assertTrue(
            "ticks should be wiped on sign-out",
            db.tickDao().getRange("2026-01-01", "2026-12-31").isEmpty(),
        )
        assertEquals(GridDensity.Default, prefs.gridDensity.value)
    }

    @Test fun signOut_thenSignInAgain_leavesDatabaseUsable() = runTest {
        // Regression for the crash on signing back in without restarting the app. The wipe used
        // to close() the Hilt @Singleton database and delete its file. Nothing recreates that
        // instance, so the next sign-in's pull hit a permanently dead database and the process
        // died with "SQLException: connection is closed" inside reconcileTracks.
        auth.signIn(Credentials("https://srv.example", "u", "p"))
        waitFor { scheduler.pushNowCalls > 0 }
        db.trackDao().insert(
            TrackEntity(serverId = 1L, name = "T", type = "boolean", sortOrder = 0, private = false),
        )

        // prefs.clear() is the last step of the wipe handler, so it is the signal that
        // clearAllTables() has already run. (waitFor's condition can't call suspend DAOs.)
        prefs.setGridDensity(GridDensity.HIGH)
        val cancelBaseline = scheduler.cancelAllCalls
        auth.signOut()
        waitFor {
            scheduler.cancelAllCalls > cancelBaseline &&
                prefs.gridDensity.value == GridDensity.Default
        }
        assertTrue("wipe did not clear tracks", db.trackDao().getAll().isEmpty())

        auth.signIn(Credentials("https://srv.example", "u2", "p2"))
        waitFor { scheduler.pushNowCalls > 1 }

        // The database must still serve reads and writes — this is what crashed before.
        val id = db.trackDao().insert(
            TrackEntity(serverId = 2L, name = "After", type = "boolean", sortOrder = 0, private = false),
        )
        assertTrue("insert after re-login failed", id > 0)
        assertEquals(setOf(2L), db.trackDao().getAll().mapNotNull { it.serverId }.toSet())
    }

    private suspend fun waitFor(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(20)
        }
        throw AssertionError("timed out waiting for condition")
    }

    private class RecordingScheduler(context: Context) : SyncScheduler(context) {
        var pushNowCalls = 0
        var periodicCalls = 0
        var cancelAllCalls = 0
        override fun schedulePushNow() { pushNowCalls++ }
        override fun schedulePeriodicPush() { periodicCalls++ }
        override fun cancelAll() { cancelAllCalls++ }
    }
}
