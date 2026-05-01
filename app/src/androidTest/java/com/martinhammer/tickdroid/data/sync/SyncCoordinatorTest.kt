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
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        db = Room.inMemoryDatabaseBuilder(context, TickdroidDatabase::class.java).build()
        prefs = UiPreferences(context).also { it.clear() }
        store = CredentialStore(context).also { it.clear() }
        auth = AuthRepository(store)
        scheduler = RecordingScheduler(context)
        coordinator = SyncCoordinator(auth, scheduler, db, prefs)
        coordinator.start()
    }

    @After fun tearDown() {
        coordinator.stop()
        db.close()
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

        // Wait for the SignedOut transition's full handler to run: cancelAll → clearAllTables
        // → prefs.clear all execute sequentially. We watch the last side-effect (prefs reset)
        // because cancelAll alone fires before clearAllTables/clear and would race the assertions.
        waitFor {
            scheduler.cancelAllCalls > cancelBaseline &&
                prefs.gridDensity.value == GridDensity.Default
        }

        assertEquals(0, db.trackDao().getAll().size)
        assertNull(db.tickDao().find(trackId, "2026-04-30"))
        assertEquals(GridDensity.Default, prefs.gridDensity.value)
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
