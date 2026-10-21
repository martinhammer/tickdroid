package com.martinhammer.tickdroid.ui.settings

import com.martinhammer.tickdroid.data.network.NetworkMonitor
import com.martinhammer.tickdroid.data.repository.TrackPrefsRepository
import com.martinhammer.tickdroid.data.repository.TrackRepository
import com.martinhammer.tickdroid.data.sync.ServerFeatures
import com.martinhammer.tickdroid.data.sync.TrackChange
import com.martinhammer.tickdroid.data.sync.TrackEditor
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class TracksSettingsViewModelTest {

    private fun track(localId: Long, sortOrder: Int = localId.toInt()) =
        Track(localId, serverId = localId * 10, name = "T$localId", type = TrackType.YesNo, sortOrder = sortOrder, private = false)

    private val tracks = MutableStateFlow(listOf(track(1), track(2), track(3)))
    private val online = MutableStateFlow(true)
    private val trackRepository = mockk<TrackRepository> { every { observeAllTracks() } returns tracks }
    private val archiveFlag = MutableStateFlow(true)
    private val serverFeatures = mockk<ServerFeatures> { every { archivedTracks } returns archiveFlag }
    private val prefsRepository = mockk<TrackPrefsRepository> {
        every { observeAll() } returns MutableStateFlow(emptyMap<Long, TrackPrefs>())
    }
    private val networkMonitor = mockk<NetworkMonitor> { every { isOnline } returns online }
    private val editor = mockk<TrackEditor>()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private class Harness(val vm: TracksSettingsViewModel, val events: List<TracksSettingsEvent>)

    private fun TestScope.harness(): Harness {
        val vm = TracksSettingsViewModel(trackRepository, prefsRepository, networkMonitor, editor, serverFeatures)
        val events = mutableListOf<TracksSettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.online.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        return Harness(vm, events)
    }

    private fun TracksSettingsViewModel.order() = state.value.rows.map { it.track.localId }

    @Test fun `create success sends Created`() = runTest {
        coEvery { editor.create("Water", TrackType.Counter, false) } returns TrackChange.Created(70)
        val h = harness()
        h.vm.create("Water", TrackType.Counter, false)
        assertEquals(listOf(TracksSettingsEvent.Created(70)), h.events)
        assertFalse(h.vm.state.value.adding)
    }

    @Test fun `created but not private sends Created and the toast`() = runTest {
        coEvery { editor.create("Water", TrackType.YesNo, true) } returns TrackChange.CreatedNotPrivate(70)
        val h = harness()
        h.vm.create("Water", TrackType.YesNo, true)
        assertEquals(
            listOf(
                TracksSettingsEvent.Created(70),
                TracksSettingsEvent.Toast("Track created, but it couldn't be made private. Open it to try again."),
            ),
            h.events,
        )
    }

    @Test fun `failed create shows the message and re-enables the button`() = runTest {
        val answer = CompletableDeferred<TrackChange>()
        coEvery { editor.create(any(), any(), any()) } coAnswers { answer.await() }
        val h = harness()
        h.vm.create("Water", TrackType.YesNo, false)
        assertTrue(h.vm.state.value.adding)
        answer.complete(TrackChange.Failed("Maximum of 99 tracks reached"))
        assertEquals(listOf(TracksSettingsEvent.Toast("Maximum of 99 tracks reached")), h.events)
        assertFalse(h.vm.state.value.adding)
    }

    @Test fun `signed out shows nothing`() = runTest {
        coEvery { editor.create(any(), any(), any()) } returns TrackChange.SignedOut
        val h = harness()
        h.vm.create("Water", TrackType.YesNo, false)
        assertEquals(emptyList<TracksSettingsEvent>(), h.events)
    }

    @Test fun `offline calls never reach the editor`() = runTest {
        online.value = false
        val h = harness()
        h.vm.create("Water", TrackType.YesNo, false)
        h.vm.moveUp(2)
        h.vm.move(1, 3)
        h.vm.commitOrder()
        coVerify(exactly = 0) { editor.create(any(), any(), any()) }
        coVerify(exactly = 0) { editor.reorder(any()) }
        assertEquals(listOf(1L, 2L, 3L), h.vm.order())
    }

    @Test fun `move reorders the rows and commit sends server ids`() = runTest {
        coEvery { editor.reorder(any()) } coAnswers {
            tracks.value = listOf(track(2, 1), track(3, 2), track(1, 3))
            TrackChange.Done
        }
        val h = harness()
        h.vm.move(1, 2)
        h.vm.move(1, 3)
        assertEquals(listOf(2L, 3L, 1L), h.vm.order())
        h.vm.commitOrder()
        coVerify { editor.reorder(listOf(20L, 30L, 10L)) }
        assertEquals(listOf(2L, 3L, 1L), h.vm.order())
    }

    @Test fun `a drop back to the same order sends nothing`() = runTest {
        val h = harness()
        h.vm.move(1, 2)
        h.vm.move(1, 2)
        h.vm.commitOrder()
        coVerify(exactly = 0) { editor.reorder(any()) }
    }

    @Test fun `pending order survives a second drop until the last call returns`() = runTest {
        val first = CompletableDeferred<TrackChange>()
        val second = CompletableDeferred<TrackChange>()
        val answers = ArrayDeque(listOf(first, second))
        coEvery { editor.reorder(any()) } coAnswers { answers.removeFirst().await() }
        val h = harness()

        h.vm.move(1, 2) // 2 1 3
        h.vm.commitOrder()
        h.vm.move(3, 1) // 2 3 1
        h.vm.commitOrder()

        first.complete(TrackChange.Done)
        assertEquals(listOf(2L, 3L, 1L), h.vm.order())

        tracks.value = listOf(track(2, 1), track(3, 2), track(1, 3))
        second.complete(TrackChange.Done)
        assertEquals(listOf(2L, 3L, 1L), h.vm.order())
        // Pending is cleared: a Room change now shows straight through.
        tracks.value = listOf(track(1, 1), track(2, 2), track(3, 3))
        assertEquals(listOf(1L, 2L, 3L), h.vm.order())
    }

    @Test fun `a failed reorder snaps back at once and shows the toast`() = runTest {
        coEvery { editor.reorder(any()) } returns TrackChange.Failed("Couldn't save. Check your connection and try again.")
        val h = harness()
        h.vm.move(3, 1)
        h.vm.commitOrder()
        assertEquals(listOf(1L, 2L, 3L), h.vm.order())
        assertEquals(listOf(TracksSettingsEvent.Toast("Couldn't save. Check your connection and try again.")), h.events)
    }

    @Test fun `move up and down save at once`() = runTest {
        coEvery { editor.reorder(any()) } returns TrackChange.Done
        val h = harness()
        h.vm.moveDown(1)
        coVerify { editor.reorder(listOf(20L, 10L, 30L)) }
        h.vm.moveUp(1) // pending was cleared after the timeout; back on Room's order (1 2 3)
        h.vm.moveUp(1) // first row: no-op
        coVerify(exactly = 1) { editor.reorder(any()) }
    }

    // --- Archived tracks ---

    private fun archived(localId: Long, name: String, sortOrder: Int = localId.toInt()) =
        track(localId, sortOrder).copy(name = name, archivedAt = LocalDate.of(2026, 8, 14))

    @Test fun `rows split into active in Room order and archived sorted by name`() = runTest {
        tracks.value = listOf(track(1), archived(4, "smoking"), track(2), archived(5, "Couch to 5k"), track(3))
        val h = harness()
        assertEquals(listOf(1L, 2L, 3L), h.vm.order())
        assertEquals(listOf(5L, 4L), h.vm.state.value.archived.map { it.track.localId })
        assertTrue(h.vm.state.value.archiveSupported)
    }

    @Test fun `without the flag nothing archived is shown`() = runTest {
        archiveFlag.value = false
        tracks.value = listOf(track(1), archived(4, "Smoking"))
        val h = harness()
        assertEquals(emptyList<TrackRowState>(), h.vm.state.value.archived)
        assertEquals(listOf(1L), h.vm.order())
        assertFalse(h.vm.state.value.archiveSupported)
    }

    @Test fun `reordering ignores archived ids and sends active ids only`() = runTest {
        tracks.value = listOf(track(1), track(2), archived(4, "Smoking", sortOrder = 3), track(3, 4))
        coEvery { editor.reorder(any()) } returns TrackChange.Done
        val h = harness()
        h.vm.move(1, 4) // onto an archived row: nothing moves
        assertEquals(listOf(1L, 2L, 3L), h.vm.order())
        h.vm.move(1, 3)
        h.vm.commitOrder()
        coVerify { editor.reorder(listOf(20L, 30L, 10L)) }
    }

    @Test fun `restore success waits for Room and sends Restored`() = runTest {
        tracks.value = listOf(track(1), archived(4, "Smoking"))
        coEvery { editor.restore(40) } coAnswers {
            tracks.value = listOf(track(1), track(4, sortOrder = 9))
            TrackChange.Done
        }
        val h = harness()
        h.vm.restore(40)
        assertEquals(listOf(TracksSettingsEvent.Restored(40)), h.events)
        assertEquals(listOf(1L, 4L), h.vm.order())
        assertNull(h.vm.state.value.restoringServerId)
    }

    @Test fun `restore failure sends RestoreFailed, signed out sends nothing`() = runTest {
        tracks.value = listOf(archived(4, "Smoking"), archived(5, "Gym"))
        coEvery { editor.restore(40) } returns TrackChange.Failed("Track not found")
        coEvery { editor.restore(50) } returns TrackChange.SignedOut
        val h = harness()
        h.vm.restore(40)
        h.vm.restore(50)
        assertEquals(listOf(TracksSettingsEvent.RestoreFailed("Track not found")), h.events)
        assertNull(h.vm.state.value.restoringServerId)
    }

    @Test fun `delete maps every outcome`() = runTest {
        tracks.value = listOf(archived(4, "Smoking"), archived(5, "Gym"), archived(6, "Run"))
        coEvery { editor.delete(40) } coAnswers {
            tracks.value = listOf(archived(5, "Gym"), archived(6, "Run"))
            TrackChange.Done
        }
        coEvery { editor.delete(50) } returns TrackChange.Failed("Couldn't save. Check your connection and try again.")
        coEvery { editor.delete(60) } returns TrackChange.SignedOut
        val h = harness()
        h.vm.delete(40)
        h.vm.delete(50)
        h.vm.delete(60)
        assertEquals(
            listOf(
                TracksSettingsEvent.Deleted,
                TracksSettingsEvent.DeleteFailed("Couldn't save. Check your connection and try again."),
            ),
            h.events,
        )
        assertNull(h.vm.state.value.deletingServerId)
    }

    @Test fun `offline restore and delete never reach the editor`() = runTest {
        tracks.value = listOf(archived(4, "Smoking"))
        online.value = false
        val h = harness()
        h.vm.restore(40)
        h.vm.delete(40)
        coVerify(exactly = 0) { editor.restore(any()) }
        coVerify(exactly = 0) { editor.delete(any()) }
    }

    @Test fun `one restore at a time`() = runTest {
        tracks.value = listOf(archived(4, "Smoking"), archived(5, "Gym"))
        val answer = CompletableDeferred<TrackChange>()
        coEvery { editor.restore(any()) } coAnswers { answer.await() }
        val h = harness()
        h.vm.restore(40)
        assertEquals(40L, h.vm.state.value.restoringServerId)
        h.vm.restore(50)
        h.vm.delete(50)
        coVerify(exactly = 1) { editor.restore(any()) }
        coVerify(exactly = 0) { editor.delete(any()) }
        answer.complete(TrackChange.Failed("x"))
        assertNull(h.vm.state.value.restoringServerId)
    }
}
