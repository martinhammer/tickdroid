package com.martinhammer.tickdroid.ui.settings

import androidx.lifecycle.SavedStateHandle
import com.martinhammer.tickdroid.data.network.NetworkMonitor
import com.martinhammer.tickdroid.data.repository.TrackPrefsRepository
import com.martinhammer.tickdroid.data.repository.TrackRepository
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackDetailViewModelTest {

    private val track = Track(1, serverId = 10, name = "Exercise", type = TrackType.BOOLEAN, sortOrder = 1, private = false)
    private val tracks = MutableStateFlow(listOf(track))
    private val prefs = MutableStateFlow(mapOf(10L to TrackPrefs(colorKey = "blue", emoji = null)))
    private val online = MutableStateFlow(true)
    private val trackRepository = mockk<TrackRepository> { every { observeTracks() } returns tracks }
    private val prefsRepository = mockk<TrackPrefsRepository> { every { observeAll() } returns prefs }
    private val networkMonitor = mockk<NetworkMonitor> { every { isOnline } returns online }
    private val editor = mockk<TrackEditor>()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private class Harness(val vm: TrackDetailViewModel, val events: List<TrackDetailEvent>)

    private fun TestScope.harness(): Harness {
        val vm = TrackDetailViewModel(
            SavedStateHandle(mapOf("localId" to 1L)), trackRepository, prefsRepository, networkMonitor, editor,
        )
        val events = mutableListOf<TrackDetailEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.online.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        return Harness(vm, events)
    }

    @Test fun `rename success closes the dialog once Room has the name`() = runTest {
        coEvery { editor.rename(10, "Gym") } coAnswers {
            tracks.value = listOf(track.copy(name = "Gym"))
            TrackChange.Done
        }
        val h = harness()
        h.vm.rename("  Gym ")
        assertEquals(listOf(TrackDetailEvent.RenameDone), h.events)
        assertEquals("Gym", h.vm.state.value.track?.name)
        assertEquals(false, h.vm.state.value.renaming)
    }

    @Test fun `an unchanged name closes without a server call`() = runTest {
        val h = harness()
        h.vm.rename(" Exercise ")
        assertEquals(listOf(TrackDetailEvent.RenameDone), h.events)
        coVerify(exactly = 0) { editor.rename(any(), any()) }
    }

    @Test fun `a failed rename keeps the dialog and shows the message`() = runTest {
        coEvery { editor.rename(10, "Gym") } returns TrackChange.Failed("Track not found")
        val h = harness()
        h.vm.rename("Gym")
        assertEquals(listOf(TrackDetailEvent.Toast("Track not found")), h.events)
        assertEquals(false, h.vm.state.value.renaming)
    }

    @Test fun `private switch shows the new position while saving, and reverts on failure`() = runTest {
        val answer = CompletableDeferred<TrackChange>()
        coEvery { editor.setPrivate(10, true) } coAnswers { answer.await() }
        val h = harness()
        h.vm.setPrivate(true)
        assertEquals(true, h.vm.state.value.pendingPrivate)
        answer.complete(TrackChange.Failed("Couldn't save. Check your connection and try again."))
        assertNull(h.vm.state.value.pendingPrivate)
        assertEquals(false, h.vm.state.value.track?.private)
        assertEquals(listOf(TrackDetailEvent.Toast("Couldn't save. Check your connection and try again.")), h.events)
    }

    @Test fun `private success clears the pending position`() = runTest {
        coEvery { editor.setPrivate(10, true) } coAnswers {
            tracks.value = listOf(track.copy(private = true))
            TrackChange.Done
        }
        val h = harness()
        h.vm.setPrivate(true)
        assertNull(h.vm.state.value.pendingPrivate)
        assertEquals(true, h.vm.state.value.track?.private)
        assertTrue(h.events.isEmpty())
    }

    @Test fun `the delete keeps the last track in state`() = runTest {
        coEvery { editor.delete(10) } coAnswers {
            // The re-read removes the row and sweeps its prefs before the call returns.
            tracks.value = emptyList()
            prefs.value = emptyMap()
            TrackChange.Done
        }
        val h = harness()
        h.vm.delete()
        assertEquals(listOf(TrackDetailEvent.Deleted), h.events)
        assertEquals(track, h.vm.state.value.track)
        assertEquals("blue", h.vm.state.value.prefs.colorKey)
    }

    @Test fun `a failed delete closes the dialog with the message`() = runTest {
        coEvery { editor.delete(10) } returns TrackChange.Failed("Couldn't save. Tickbuddy returned an error (HTTP 500).")
        val h = harness()
        h.vm.delete()
        assertEquals(
            listOf(TrackDetailEvent.DeleteFailed("Couldn't save. Tickbuddy returned an error (HTTP 500).")),
            h.events,
        )
        assertEquals(false, h.vm.state.value.deleting)
    }

    @Test fun `a track deleted elsewhere shows as not found`() = runTest {
        val h = harness()
        tracks.value = emptyList()
        assertNull(h.vm.state.value.track)
    }

    @Test fun `offline calls never reach the editor`() = runTest {
        online.value = false
        val h = harness()
        h.vm.rename("Gym")
        h.vm.setPrivate(true)
        h.vm.delete()
        coVerify(exactly = 0) { editor.rename(any(), any()) }
        coVerify(exactly = 0) { editor.setPrivate(any(), any()) }
        coVerify(exactly = 0) { editor.delete(any()) }
        assertTrue(h.events.isEmpty())
    }
}
