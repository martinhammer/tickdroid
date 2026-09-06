package com.martinhammer.tickdroid.data.sync

import com.martinhammer.tickdroid.data.local.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Regression tests for the first-sign-in crash: a pull launched in `viewModelScope` was
 * cancelled when navigation destroyed the journal's ViewModel, Room closed the pooled
 * connection while the transaction block was still running on its own executor thread, and the
 * resulting `SQLException: connection is closed` (SQLITE_MISUSE) escaped there as an uncaught
 * fatal rather than a `CancellationException`.
 *
 * Two defences are asserted here: [SyncManager.schedulePull] does not run on the caller's job
 * at all, and the reconcile itself is `NonCancellable` so no future caller can reintroduce it.
 */
class SyncCancellationTest {

    private lateinit var rig: SyncTestRig
    private val from = LocalDate.parse("2026-04-01")
    private val to = LocalDate.parse("2026-04-30")

    @Before fun setUp() {
        rig = SyncTestRig().also { it.assertSignedIn() }
    }

    @After fun tearDown() {
        rig.shutdown()
    }

    @Test fun cancellingTheCaller_doesNotTearDownTheTransaction() = runTest {
        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueAsset("ocs/ticks_ok.json")

        // Stand in for viewModelScope: a scope the UI can cancel out from under a pull.
        val uiScope = CoroutineScope(Dispatchers.IO)
        val job = uiScope.launch { rig.syncManager.pull(from, to) }

        // Cancel while the pull is in flight. Before the fix this is the window in which Room
        // closed the connection under a running transaction and killed the process.
        yield()
        job.cancel()
        job.join()

        // The connection must still be alive: this write-then-read throws SQLITE_MISUSE if
        // cancellation tore it down, which is the exact failure the crash reported.
        rig.db.trackDao().insert(
            TrackEntity(
                serverId = 999,
                name = "after-cancel",
                type = "boolean",
                sortOrder = 0,
                private = false,
            )
        )
        val tracks = rig.db.trackDao().getAll()
        assertTrue(
            "database unusable after cancelling the pull",
            tracks.any { it.serverId == 999L },
        )

        // And whatever the reconcile committed is all-or-nothing, never half-applied.
        val reconciled = tracks.mapNotNull { it.serverId }.toSet() - 999L
        assertTrue(
            "partially applied reconcile: $reconciled",
            reconciled.isEmpty() || reconciled == setOf(10L, 11L, 12L),
        )
    }

    // runBlocking, not runTest: this waits on work running on SyncManager's own scope, and
    // runTest's virtual clock would expire the timeout without any real time passing.
    @Test fun schedulePull_completesEvenWhenTheCallerScopeDies() = runBlocking {
        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueAsset("ocs/ticks_ok.json")

        // schedulePull hands the work to SyncManager's own application-lifetime scope, so
        // cancelling the caller immediately afterwards must not affect it.
        val uiScope = CoroutineScope(Dispatchers.IO)
        uiScope.launch { rig.syncManager.schedulePull(from, to) }.also {
            it.join()
            it.cancel()
        }
        uiScope.cancel()

        withTimeout(10_000) {
            while (rig.db.trackDao().getAll().isEmpty()) delay(25)
        }
        assertEquals(
            setOf(10L, 11L, 12L),
            rig.db.trackDao().getAll().mapNotNull { it.serverId }.toSet(),
        )
        assertEquals(SyncStatus.Idle, rig.syncManager.status.value)
    }
}
