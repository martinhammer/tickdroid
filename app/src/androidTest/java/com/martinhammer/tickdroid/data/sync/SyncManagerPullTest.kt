package com.martinhammer.tickdroid.data.sync

import com.martinhammer.tickdroid.data.local.NoteEntity
import com.martinhammer.tickdroid.data.local.TickEntity
import com.martinhammer.tickdroid.data.local.TrackRunEntity
import com.martinhammer.tickdroid.data.local.TrackEntity
import com.martinhammer.tickdroid.data.local.TrackPrefsEntity
import com.martinhammer.tickdroid.data.repository.TrackRepository
import com.martinhammer.tickdroid.domain.TrackType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun queuedUntick_survivesAPullThatStillHasTheTick() = runTest {
        val trackId = rig.db.trackDao().insert(
            TrackEntity(serverId = 10L, name = "Meditate", type = "boolean", sortOrder = 0, private = false),
        )
        rig.db.tickDao().upsert(
            TickEntity(serverId = 100L, trackLocalId = trackId, date = "2026-04-29", value = 0, dirty = true, deleted = true),
        )
        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueAsset("ocs/ticks_ok.json") // the server still has the tick

        rig.syncManager.pull(from, to)

        val tick = rig.db.tickDao().find(trackId, "2026-04-29")!!
        assertTrue("the untick is still queued", tick.deleted)
        assertTrue(tick.dirty)
        assertEquals(0, tick.value)
    }

    @Test fun cleanRowMarkedDeleted_isReplacedByTheServersTick() = runTest {
        // None should exist now, but an older version could leave one.
        val trackId = rig.db.trackDao().insert(
            TrackEntity(serverId = 10L, name = "Meditate", type = "boolean", sortOrder = 0, private = false),
        )
        rig.db.tickDao().upsert(
            TickEntity(serverId = 100L, trackLocalId = trackId, date = "2026-04-29", value = 0, deleted = true),
        )
        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueAsset("ocs/ticks_ok.json")

        rig.syncManager.pull(from, to)

        val tick = rig.db.tickDao().find(trackId, "2026-04-29")!!
        assertEquals(1, tick.value)
        assertFalse(tick.deleted)
        assertFalse(tick.dirty)
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

    // --- Archived tracks ---

    private fun tracksJson(smokingArchivedAt: String?) = """
        {"ocs":{"meta":{"status":"ok","statuscode":200},"data":[
          {"id":10,"name":"Meditate","type":"boolean","sortOrder":0,"private":false,"archivedAt":null},
          {"id":11,"name":"Pages read","type":"counter","sortOrder":1,"private":false,"archivedAt":null},
          {"id":13,"name":"Smoking","type":"boolean","sortOrder":2,"private":false,"archivedAt":${smokingArchivedAt?.let { "\"$it\"" } ?: "null"}}
        ]}}
    """.trimIndent()

    private val smokingTicks = """
        {"ocs":{"meta":{"status":"ok","statuscode":200},"data":[
          {"id":200,"trackId":13,"date":"2026-04-10","value":1}
        ]}}
    """.trimIndent()

    @Test fun withFlag_readsArchived_keepsDatePrefsAndTicks() = runTest {
        rig.archivedTracks = true
        rig.db.trackPrefsDao().upsert(TrackPrefsEntity(serverId = 13, colorKey = "red"))
        rig.enqueueJson(tracksJson("2026-08-14"))
        rig.enqueueJson(smokingTicks)

        rig.syncManager.pull(from, to)

        assertEquals("/ocs/v2.php/cloud/capabilities", rig.server.takeRequest().path)
        assertEquals("/ocs/v2.php/apps/tickbuddy/api/tracks?includeArchived=true&includeAllTypes=true", rig.takeApiRequest().path)
        val smoking = rig.db.trackDao().findByServerId(13)!!
        assertEquals("2026-08-14", smoking.archivedAt)
        assertEquals("red", rig.db.trackPrefsDao().findByServerId(13)?.colorKey)
        assertNotNull(rig.db.tickDao().find(smoking.localId, "2026-04-10"))
    }

    @Test fun withoutFlag_noParameter_allActive() = runTest {
        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueAsset("ocs/empty.json")

        rig.syncManager.pull(from, to)

        assertEquals("/ocs/v2.php/apps/tickbuddy/api/tracks?includeAllTypes=true", rig.takeApiRequest().path)
        assertTrue(rig.db.trackDao().getAll().all { it.archivedAt == null })
        assertFalse(rig.serverFeatures.archivedTracks.value)
    }

    @Test fun flagFlipsBetweenPulls_secondPullAlreadyAsksForArchived() = runTest {
        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueAsset("ocs/empty.json")
        rig.syncManager.pull(from, to)
        assertEquals("/ocs/v2.php/apps/tickbuddy/api/tracks?includeAllTypes=true", rig.takeApiRequest().path)
        rig.takeApiRequest()

        rig.archivedTracks = true
        rig.enqueueJson(tracksJson("2026-08-14"))
        rig.enqueueAsset("ocs/empty.json")
        rig.syncManager.pull(from, to)

        assertEquals("/ocs/v2.php/apps/tickbuddy/api/tracks?includeArchived=true&includeAllTypes=true", rig.takeApiRequest().path)
        assertTrue(rig.serverFeatures.archivedTracks.value)
    }

    @Test fun brokenCapabilities_storedValueStands_pullSucceeds() = runTest {
        rig.archivedTracks = true
        rig.enqueueJson(tracksJson("2026-08-14"))
        rig.enqueueAsset("ocs/empty.json")
        rig.syncManager.pull(from, to)
        repeat(2) { rig.takeApiRequest() }

        for (broken in listOf(
            MockResponse().setResponseCode(500),
            MockResponse().setResponseCode(200).setBody("<html>not json</html>"),
        )) {
            rig.capabilitiesOverride = broken
            rig.enqueueJson(tracksJson("2026-08-14"))
            rig.enqueueAsset("ocs/empty.json")

            rig.syncManager.pull(from, to)

            assertEquals(SyncStatus.Idle, rig.syncManager.status.value)
            assertTrue(rig.serverFeatures.archivedTracks.value)
            assertEquals("/ocs/v2.php/apps/tickbuddy/api/tracks?includeArchived=true&includeAllTypes=true", rig.takeApiRequest().path)
            rig.takeApiRequest()
        }
    }

    @Test fun dirtyTickOnATrackThePullArchives_isDropped_andTheServerValueIsBack() = runTest {
        rig.archivedTracks = true
        rig.enqueueJson(tracksJson(null))
        rig.enqueueJson(smokingTicks)
        rig.syncManager.pull(from, to)
        val smoking = rig.db.trackDao().findByServerId(13)!!
        // Unticked offline, not yet pushed.
        rig.db.tickDao().update(rig.db.tickDao().find(smoking.localId, "2026-04-10")!!.copy(value = 0, dirty = true, deleted = true))

        rig.enqueueJson(tracksJson("2026-08-14"))
        rig.enqueueJson(smokingTicks)
        rig.syncManager.pull(from, to)

        val tick = rig.db.tickDao().find(smoking.localId, "2026-04-10")!!
        assertFalse(tick.dirty)
        assertFalse(tick.deleted)
        assertEquals(1, tick.value)
        assertTrue(rig.db.tickDao().getDirty().isEmpty())
    }

    // --- A track of a type this Tickdroid doesn't know ---

    @Test fun unknownType_isStored_readsAsUnknown_oddValuesBecomeMarkers_othersUnaffected() = runTest {
        rig.enqueueJson(
            """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":[
              {"id":10,"name":"Meditate","type":"boolean","sortOrder":0,"private":false},
              {"id":11,"name":"Pages","type":"counter","sortOrder":1,"private":false},
              {"id":14,"name":"Mood","type":"future","sortOrder":2,"private":false}
            ]}}""",
        )
        rig.enqueueJson(
            """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":[
              {"id":1,"trackId":10,"date":"2026-04-10","value":1},
              {"id":2,"trackId":11,"date":"2026-04-10","value":4},
              {"id":3,"trackId":14,"date":"2026-04-10","value":7},
              {"id":4,"trackId":14,"date":"2026-04-11","value":7.5},
              {"id":5,"trackId":14,"date":"2026-04-12","value":"red"}
            ]}}""",
        )

        rig.syncManager.pull(from, to)

        assertEquals(SyncStatus.Idle, rig.syncManager.status.value)
        assertEquals("/ocs/v2.php/apps/tickbuddy/api/tracks?includeAllTypes=true", rig.takeApiRequest().path)
        assertEquals(
            "/ocs/v2.php/apps/tickbuddy/api/ticks?from=$from&to=$to&includeAllTypes=true",
            rig.takeApiRequest().path,
        )
        val mood = rig.db.trackDao().findByServerId(14)!!
        assertEquals("future", mood.type)
        val moodTicks = listOf("2026-04-10", "2026-04-11", "2026-04-12").map { rig.db.tickDao().find(mood.localId, it)?.value }
        assertEquals(listOf(7, 1, 1), moodTicks)
        assertEquals(1, rig.db.tickDao().find(rig.db.trackDao().findByServerId(10)!!.localId, "2026-04-10")?.value)
        assertEquals(4, rig.db.tickDao().find(rig.db.trackDao().findByServerId(11)!!.localId, "2026-04-10")?.value)

        // Mapping no longer throws: the track reaches the app as Unknown.
        val tracks = TrackRepository(rig.db.trackDao()).observeTracks().first()
        assertEquals(
            listOf(TrackType.YesNo, TrackType.Counter, TrackType.Unknown("future")),
            tracks.map { it.type },
        )
    }
    // Notes (capability `notes`) and stats (capability `stats`).

    private fun notesJson(vararg notes: String) =
        """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":[${notes.joinToString(",")}]}}"""

    private fun note(trackId: Long, date: String, text: String) = """{"trackId":$trackId,"date":"$date","text":"$text"}"""

    private suspend fun localIdOf(serverId: Long) = rig.db.trackDao().getAll().single { it.serverId == serverId }.localId

    @Test fun withNotes_readsTheRange_notesLandClean_unknownTrackSkipped() = runTest {
        rig.notes = true
        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueAsset("ocs/empty.json")
        rig.enqueueJson(notesJson(note(10, "2026-04-30", "hello"), note(999, "2026-04-30", "nobody's")))

        rig.syncManager.pull(from, to)

        rig.takeApiRequest(); rig.takeApiRequest()
        val read = rig.takeApiRequest()
        assertTrue(read.path!!, read.path!!.contains("/notes?from=$from&to=$to&includeAllTypes=true"))
        val notes = rig.db.noteDao().getRange(from.toString(), to.toString())
        assertEquals(1, notes.size)
        assertEquals("hello", notes[0].text)
        assertEquals(localIdOf(10), notes[0].trackLocalId)
        assertFalse(notes[0].dirty)
        assertEquals(SyncStatus.Idle, rig.syncManager.status.value)
    }

    @Test fun withNotes_dirtyNoteSurvives_cleanNoteGoneFromServerIsDeleted() = runTest {
        rig.notes = true
        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueAsset("ocs/empty.json")
        rig.enqueueJson(notesJson())
        rig.syncManager.pull(from, to)
        val meditate = localIdOf(10)
        val pages = localIdOf(11)
        rig.db.noteDao().upsert(NoteEntity(trackLocalId = meditate, date = "2026-04-29", text = "mine", dirty = true, baseText = "old"))
        rig.db.noteDao().upsert(NoteEntity(trackLocalId = pages, date = "2026-04-29", text = "stale"))

        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueAsset("ocs/empty.json")
        rig.enqueueJson(notesJson(note(10, "2026-04-29", "theirs")))
        rig.syncManager.pull(from, to)

        val dirty = rig.db.noteDao().find(meditate, "2026-04-29")!!
        assertEquals("mine", dirty.text)
        assertTrue(dirty.dirty)
        assertEquals("old", dirty.baseText)
        assertNull(rig.db.noteDao().find(pages, "2026-04-29"))
    }

    @Test fun withoutNotes_noNotesRequest() = runTest {
        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueAsset("ocs/empty.json")

        rig.syncManager.pull(from, to)

        assertEquals("capabilities, tracks, ticks", 3, rig.server.requestCount)
        assertEquals(SyncStatus.Idle, rig.syncManager.status.value)
    }

    private val statsJson = """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":[
        {"trackId":10,"total":5,"current":{"isStreak":true,"length":3,"from":"2026-10-26","to":"2026-10-28"},
         "upToYesterday":true,"maxValue":1,"minValue":1},
        {"trackId":11,"total":0,"current":null,"upToYesterday":false,"maxValue":7,"minValue":0}]}}"""

    @Test fun withStats_asksAsOfThePhonesToday_runsLand() = runTest {
        rig.stats = true
        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueAsset("ocs/empty.json")
        rig.enqueueJson(statsJson)

        rig.syncManager.pull(from, to)

        rig.takeApiRequest(); rig.takeApiRequest()
        val stats = rig.takeApiRequest()
        assertTrue(stats.path!!, stats.path!!.contains("/stats?today=${rig.today}"))
        val runs = rig.db.trackRunDao().getAll().associateBy { it.trackLocalId }
        assertEquals(2, runs.size)
        val meditate = runs.getValue(localIdOf(10))
        assertEquals(true, meditate.isStreak)
        assertEquals(3, meditate.length)
        assertTrue(meditate.upToYesterday)
        assertEquals(rig.today.toString(), meditate.asOf)
        val pages = runs.getValue(localIdOf(11))
        assertNull(pages.isStreak)
        assertNull(pages.length)
        assertEquals(7, pages.maxValue)
    }

    @Test fun brokenStats_storedRunsStand_pullStillSucceeds() = runTest {
        rig.stats = true
        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueAsset("ocs/empty.json")
        rig.enqueueJson(statsJson)
        rig.syncManager.pull(from, to)
        val before = rig.db.trackRunDao().getAll().toSet()

        for (broken in listOf(MockResponse().setResponseCode(500), MockResponse().setResponseCode(200).setBody("<html>oops</html>"))) {
            rig.enqueueAsset("ocs/tracks_ok.json")
            rig.enqueueAsset("ocs/ticks_ok.json")
            rig.enqueue(broken)
            rig.syncManager.pull(from, to)

            assertEquals(SyncStatus.Idle, rig.syncManager.status.value)
            assertEquals(before, rig.db.trackRunDao().getAll().toSet())
            assertEquals("ticks still synced", 3, rig.db.tickDao().getRange(from.toString(), to.toString()).size)
        }
    }

    @Test fun deletingATrack_cascadesToItsNotesAndRun() = runTest {
        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueAsset("ocs/empty.json")
        rig.syncManager.pull(from, to)
        val meditate = localIdOf(10)
        rig.db.noteDao().upsert(NoteEntity(trackLocalId = meditate, date = "2026-04-29", text = "x"))
        rig.db.trackRunDao().replaceAll(
            listOf(TrackRunEntity(meditate, "2026-04-30", isStreak = true, length = 1, upToYesterday = false, maxValue = 1)),
        )
        rig.db.trackDao().deleteById(meditate)
        assertNull(rig.db.noteDao().find(meditate, "2026-04-29"))
        assertTrue(rig.db.trackRunDao().getAll().isEmpty())
    }
}
