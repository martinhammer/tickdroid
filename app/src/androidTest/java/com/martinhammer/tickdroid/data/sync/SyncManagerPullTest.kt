package com.martinhammer.tickdroid.data.sync

import com.martinhammer.tickdroid.data.local.TickEntity
import com.martinhammer.tickdroid.data.local.TrackEntity
import com.martinhammer.tickdroid.data.local.TrackPrefsEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class SyncManagerPullTest {

    private lateinit var rig: SyncTestRig
    private val from = LocalDate.parse("2026-04-01")
    private val to = LocalDate.parse("2026-04-30")

    @Before fun setUp() {
        rig = SyncTestRig().also { it.assertSignedIn() }
    }

    @After fun tearDown() {
        rig.shutdown()
    }

    @Test fun cleanPull_populatesTracksAndTicks() = runTest {
        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueAsset("ocs/ticks_ok.json")

        rig.syncManager.pull(from, to)

        val tracks = rig.db.trackDao().getAll()
        assertEquals(setOf(10L, 11L, 12L), tracks.mapNotNull { it.serverId }.toSet())
        val ticks = rig.db.tickDao().getRange(from.toString(), to.toString())
        assertEquals(3, ticks.size)
        assertEquals(SyncStatus.Idle, rig.syncManager.status.value)
    }

    @Test fun pull_overwritesNonDirtyLocalTick() = runTest {
        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueAsset("ocs/ticks_ok.json")

        // First pull seeds local rows.
        rig.syncManager.pull(from, to)

        // Server now reports a different value for the counter on 2026-04-30.
        val updated = """
            {"ocs":{"meta":{"status":"ok","statuscode":200},"data":[
              {"id":102,"trackId":11,"date":"2026-04-30","value":99}
            ]}}
        """.trimIndent()
        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueJson(updated)

        rig.syncManager.pull(from, to)

        val track = rig.db.trackDao().getAll().first { it.serverId == 11L }
        val tick = rig.db.tickDao().find(track.localId, "2026-04-30")!!
        assertEquals(99, tick.value)
        assertEquals(false, tick.dirty)
    }

    @Test fun pull_doesNotOverwriteDirtyLocalTick() = runTest {
        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueAsset("ocs/ticks_ok.json")
        rig.syncManager.pull(from, to)

        // User taps the counter locally before next pull → row is dirty=1, value=42.
        val track = rig.db.trackDao().getAll().first { it.serverId == 11L }
        val baseline = rig.db.tickDao().find(track.localId, "2026-04-30")!!
        rig.db.tickDao().update(baseline.copy(value = 42, dirty = true))

        // Server returns its own (different) value — should be ignored for dirty rows.
        val server = """
            {"ocs":{"meta":{"status":"ok","statuscode":200},"data":[
              {"id":102,"trackId":11,"date":"2026-04-30","value":99}
            ]}}
        """.trimIndent()
        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueJson(server)
        rig.syncManager.pull(from, to)

        val tick = rig.db.tickDao().find(track.localId, "2026-04-30")!!
        assertEquals(42, tick.value)
        assertTrue(tick.dirty)
    }

    @Test fun pull_dropsLocalTicksMissingFromServer() = runTest {
        // Seed a track + tick locally that the server will not return.
        val trackId = rig.db.trackDao().insert(
            TrackEntity(serverId = 10L, name = "Meditate", type = "boolean", sortOrder = 0, private = false),
        )
        rig.db.tickDao().upsert(
            TickEntity(serverId = 999L, trackLocalId = trackId, date = "2026-04-15", value = 1),
        )

        rig.enqueueAsset("ocs/tracks_ok.json")
        // Empty ticks payload — server says "nothing in range".
        rig.enqueueAsset("ocs/empty.json")

        rig.syncManager.pull(from, to)

        assertNull(rig.db.tickDao().find(trackId, "2026-04-15"))
    }

    @Test fun pull_keepsDirtyLocalTickWhenServerEmpty() = runTest {
        val trackId = rig.db.trackDao().insert(
            TrackEntity(serverId = 10L, name = "Meditate", type = "boolean", sortOrder = 0, private = false),
        )
        rig.db.tickDao().upsert(
            TickEntity(
                serverId = null, trackLocalId = trackId, date = "2026-04-15",
                value = 1, dirty = true,
            ),
        )

        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueAsset("ocs/empty.json")

        rig.syncManager.pull(from, to)

        val tick = rig.db.tickDao().find(trackId, "2026-04-15")
        assertNotNull(tick)
        assertTrue(tick!!.dirty)
    }

    @Test fun pull_sweepsOrphanTrackPrefs_whenTrackDeletedServerSide() = runTest {
        // Seed orphan prefs for a serverId no longer in the server's response.
        rig.db.trackPrefsDao().upsert(TrackPrefsEntity(serverId = 999L, colorKey = "blue", emoji = "✨"))

        rig.enqueueAsset("ocs/tracks_ok.json") // ids 10, 11, 12 — no 999
        rig.enqueueAsset("ocs/empty.json")

        rig.syncManager.pull(from, to)

        assertNull(rig.db.trackPrefsDao().findByServerId(999L))
    }

    @Test fun pull_keepsTrackPrefs_whenTrackStillPresent() = runTest {
        rig.db.trackPrefsDao().upsert(TrackPrefsEntity(serverId = 10L, colorKey = "blue"))

        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueAsset("ocs/empty.json")

        rig.syncManager.pull(from, to)

        val prefs = rig.db.trackPrefsDao().findByServerId(10L)
        assertNotNull(prefs)
        assertEquals("blue", prefs!!.colorKey)
    }

    @Test fun pull_outsideRange_doesNotTouchUnrelatedRows() = runTest {
        // Seed a row well outside the pull window — server only returns ticks for the window.
        val trackId = rig.db.trackDao().insert(
            TrackEntity(serverId = 10L, name = "Meditate", type = "boolean", sortOrder = 0, private = false),
        )
        rig.db.tickDao().upsert(
            TickEntity(serverId = 7L, trackLocalId = trackId, date = "2024-01-01", value = 1),
        )

        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueAsset("ocs/empty.json")
        rig.syncManager.pull(from, to)

        // Out-of-range row must remain untouched.
        assertNotNull(rig.db.tickDao().find(trackId, "2024-01-01"))
    }

    @Test fun pull_serverError_setsStatusError() = runTest {
        rig.enqueueStatus(500)

        rig.syncManager.pull(from, to)

        val status = rig.syncManager.status.value
        assertTrue("expected Error, was $status", status is SyncStatus.Error)
        assertEquals(SyncErrorKind.ServerError, (status as SyncStatus.Error).kind)
    }
}
