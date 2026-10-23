package com.martinhammer.tickdroid.widget

import android.appwidget.AppWidgetManager
import androidx.lifecycle.SavedStateHandle
import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.auth.AuthState
import com.martinhammer.tickdroid.data.repository.TrackPrefsRepository
import com.martinhammer.tickdroid.data.repository.TrackRepository
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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

/**
 * The setup button reads "Save" for a placed widget and "Add widget" only for a new one; Single
 * track picks, then saves; the background starts from the stored setup and goes into the saved one.
 */
class WidgetConfigViewModelTest {

    private val store = mockk<WidgetConfigStore>()
    private val authRepository = mockk<AuthRepository> {
        every { state } returns MutableStateFlow<AuthState>(AuthState.SignedOut)
    }
    private val tracks = MutableStateFlow<List<Track>>(emptyList())
    private val trackRepository = mockk<TrackRepository> { every { observeTracks() } returns tracks }
    private val prefsRepository = mockk<TrackPrefsRepository> { every { observeAll() } returns flowOf(emptyMap()) }

    private fun track(serverId: Long) = Track(
        localId = serverId, serverId = serverId, name = "Track $serverId",
        type = TrackType.YesNo, sortOrder = serverId.toInt(), private = false,
    )

    /** Keeps [WidgetConfigViewModel.state] subscribed, as the screen does, while [block] runs. */
    private fun WidgetConfigViewModel.collecting(block: WidgetConfigViewModel.() -> Unit) = runTest {
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { state.collect {} }
        block()
        job.cancel()
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(stored: WidgetConfig?, fromWidget: Boolean?): WidgetConfigViewModel {
        every { store.get(5) } returns stored
        val args = buildMap<String, Any> {
            put(AppWidgetManager.EXTRA_APPWIDGET_ID, 5)
            if (fromWidget != null) put(EXTRA_FROM_WIDGET, fromWidget)
        }
        return WidgetConfigViewModel(SavedStateHandle(args), authRepository, trackRepository, prefsRepository, store)
    }

    @Test fun `a new placement from the launcher reads Add widget`() {
        assertFalse(viewModel(stored = null, fromWidget = null).isReconfigure)
    }

    @Test fun `a placed widget with no stored setup still reads Save`() {
        // e.g. after sign-out cleared every widget's choices: its first edit used to read "Add widget".
        assertTrue(viewModel(stored = null, fromWidget = true).isReconfigure)
    }

    @Test fun `a stored setup reads Save, also via the launcher's own reconfigure`() {
        assertTrue(viewModel(stored = WidgetConfig.TrackSelection(), fromWidget = true).isReconfigure)
        assertTrue(viewModel(stored = WidgetConfig.TrackSelection(), fromWidget = null).isReconfigure)
    }

    @Test fun `single track - choose picks without saving`() {
        tracks.value = listOf(track(1), track(2))
        viewModel(stored = WidgetConfig.Button(1), fromWidget = true).collecting {
            assertEquals(1L, state.value.chosenServerId)
            choose(2)
            assertEquals(2L, state.value.chosenServerId)
            assertEquals(WidgetConfig.Button(trackServerId = 2), buttonConfig())
        }
        verify(exactly = 0) { store.put(any(), any()) }
    }

    @Test fun `single track - no config without a pick`() {
        tracks.value = listOf(track(1))
        viewModel(stored = null, fromWidget = null).collecting {
            assertNull(state.value.chosenServerId)
            assertNull(buttonConfig())
        }
    }

    @Test fun `single track - a stored track no longer offered is no pick`() {
        // Deleted, archived or made private since: Save must stay disabled.
        tracks.value = listOf(track(1))
        viewModel(stored = WidgetConfig.Button(9), fromWidget = true).collecting {
            assertNull(state.value.chosenServerId)
            assertNull(buttonConfig())
        }
    }

    @Test fun `single track - the saved config carries the background`() {
        tracks.value = listOf(track(1))
        viewModel(stored = WidgetConfig.Button(1, background = 0), fromWidget = true).collecting {
            assertEquals(0, state.value.background)
            setBackground(50)
            assertEquals(50, state.value.background)
            assertEquals(WidgetConfig.Button(trackServerId = 1, background = 50), buttonConfig())
        }
    }

    @Test fun `the background starts from the stored setup, Solid with none`() {
        viewModel(stored = WidgetConfig.TrackSelection(background = 0), fromWidget = true).collecting {
            assertEquals(0, state.value.background)
        }
        viewModel(stored = null, fromWidget = null).collecting {
            assertEquals(100, state.value.background)
        }
    }

    @Test fun `selection config carries the background`() {
        tracks.value = listOf(track(1))
        viewModel(stored = WidgetConfig.TrackSelection(labels = TrackLabels.Short), fromWidget = true).collecting {
            setBackground(50)
            assertEquals(
                WidgetConfig.TrackSelection(shown = mapOf(1L to true), labels = TrackLabels.Short, background = 50),
                selectionConfig(),
            )
        }
    }
}
