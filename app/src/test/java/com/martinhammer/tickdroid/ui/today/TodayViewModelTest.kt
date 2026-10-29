package com.martinhammer.tickdroid.ui.today

import androidx.lifecycle.SavedStateHandle
import com.martinhammer.tickdroid.data.network.NetworkMonitor
import com.martinhammer.tickdroid.data.prefs.EditableDays
import com.martinhammer.tickdroid.data.prefs.UiPreferences
import com.martinhammer.tickdroid.data.repository.NoteRepository
import com.martinhammer.tickdroid.data.repository.TickKey
import com.martinhammer.tickdroid.data.repository.TickRepository
import com.martinhammer.tickdroid.data.repository.TrackPrefsRepository
import com.martinhammer.tickdroid.data.repository.TrackRepository
import com.martinhammer.tickdroid.data.repository.TrackRunRepository
import com.martinhammer.tickdroid.data.sync.PushStatus
import com.martinhammer.tickdroid.data.sync.ServerFeatures
import com.martinhammer.tickdroid.data.sync.SyncManager
import com.martinhammer.tickdroid.data.sync.SyncStatus
import com.martinhammer.tickdroid.data.time.Clock
import com.martinhammer.tickdroid.domain.Tick
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackRun
import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
class TodayViewModelTest {

    private var now = LocalDate.of(2026, 10, 28)
    private val clock = object : Clock {
        override fun today(): LocalDate = now
        override fun nowMillis(): Long = 0L
    }

    private val notesFlag = MutableStateFlow(true)
    private val serverFeatures = mockk<ServerFeatures> {
        every { notes } returns notesFlag
        every { stats } returns MutableStateFlow(true)
    }
    private val editableDays = MutableStateFlow(EditableDays.Default)
    private val uiPreferences = mockk<UiPreferences> {
        every { showPrivate } returns MutableStateFlow(false)
        every { editableDays } returns this@TodayViewModelTest.editableDays
    }
    private val syncManager = mockk<SyncManager> {
        every { status } returns MutableStateFlow<SyncStatus>(SyncStatus.Idle)
        every { pushStatus } returns MutableStateFlow<PushStatus>(PushStatus.Idle)
        every { schedulePull(any(), any()) } just runs
    }
    private val tickRepository = mockk<TickRepository>(relaxUnitFun = true) {
        every { observeRange(any(), any()) } returns flowOf(emptyMap<TickKey, Tick>())
        every { observeHasUnsent() } returns flowOf(false)
    }
    private val noteRepository = mockk<NoteRepository>(relaxUnitFun = true) {
        every { observeRange(any(), any()) } returns flowOf(emptyMap<TickKey, String>())
    }
    private val trackRepository = mockk<TrackRepository> {
        every { observeTracks() } returns flowOf(emptyList<Track>())
    }
    private val runRepository = mockk<TrackRunRepository> {
        every { observeRuns() } returns flowOf(emptyMap<Long, TrackRun>())
    }
    private val prefsRepository = mockk<TrackPrefsRepository> {
        every { observeAll() } returns flowOf(emptyMap<Long, TrackPrefs>())
    }
    private val networkMonitor = mockk<NetworkMonitor> { every { isOnline } returns MutableStateFlow(true) }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(handle: SavedStateHandle = SavedStateHandle()): TodayViewModel {
        val vm = TodayViewModel(
            handle, trackRepository, tickRepository, noteRepository, runRepository, prefsRepository,
            syncManager, serverFeatures, networkMonitor, uiPreferences, clock,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    private val TodayViewModel.day get() = state.value.day

    @Test fun `a fresh start shows today`() = runTest {
        val vm = viewModel()
        assertEquals(now, vm.day)
        assertEquals(now, vm.state.value.today)
    }

    @Test fun `previous and next move a day, next stops at today`() = runTest {
        val vm = viewModel()
        vm.previousDay()
        vm.previousDay()
        assertEquals(now.minusDays(2), vm.day)
        vm.nextDay()
        assertEquals(now.minusDays(1), vm.day)
        vm.nextDay()
        vm.nextDay()
        assertEquals(now, vm.day)
    }

    @Test fun `the day shown survives through the saved state`() = runTest {
        val handle = SavedStateHandle()
        viewModel(handle).showDay(now.minusDays(9))
        assertEquals(now.minusDays(9), viewModel(handle).day)
    }

    @Test fun `opened from a Journal day label - the day and Back to the Journal`() = runTest {
        val vm = viewModel()
        vm.openFromNavigation(now.minusDays(4), fromJournal = true)
        assertEquals(now.minusDays(4), vm.day)
        assertTrue(vm.state.value.fromJournal)
        vm.clearFromJournal()
        assertFalse(vm.state.value.fromJournal)
        // The bar's Today item.
        vm.openFromNavigation(now, fromJournal = false)
        assertEquals(now, vm.day)
        assertFalse(vm.state.value.fromJournal)
    }

    @Test fun `after midnight today moves to the new day, another day stays`() = runTest {
        val onToday = viewModel()
        val onOther = viewModel()
        onOther.showDay(now.minusDays(3))
        val before = now
        now = now.plusDays(1)
        onToday.refresh()
        onOther.refresh()
        assertEquals(now, onToday.day)
        assertEquals(before.minusDays(3), onOther.day)
        assertEquals(now, onOther.state.value.today)
    }

    @Test fun `a day 40 back pulls only the new range`() = runTest {
        val vm = viewModel()
        clearMocks(syncManager, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true)
        vm.showDay(now.minusDays(40))
        // The window started at today − 29; the oldest needed is day − 1 = today − 41.
        val newOldest = now.minusDays(41).minusDays(29)
        verify(exactly = 1) { syncManager.schedulePull(newOldest, now.minusDays(30)) }
        // Already loaded: nothing more.
        vm.showDay(now.minusDays(39))
        verify(exactly = 1) { syncManager.schedulePull(any(), any()) }
    }

    @Test fun `the card opens only on an editable day with notes`() = runTest {
        val vm = viewModel()
        vm.openNote(7)
        assertEquals(NoteTarget(7, now), vm.state.value.editing)
        vm.closeNote()
        assertNull(vm.state.value.editing)

        vm.showDay(now.minusDays(10)) // locked by the default Editable days
        vm.openNote(7)
        assertNull(vm.state.value.editing)

        vm.showToday()
        notesFlag.value = false
        vm.openNote(7)
        assertNull(vm.state.value.editing)
    }

    @Test fun `saveNote reaches the repository`() = runTest {
        val vm = viewModel()
        vm.saveNote(7, now, "hello")
        coVerify { noteRepository.saveNote(7, now, "hello") }
    }
}
