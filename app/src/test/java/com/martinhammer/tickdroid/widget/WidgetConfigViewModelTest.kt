package com.martinhammer.tickdroid.widget

import android.appwidget.AppWidgetManager
import androidx.lifecycle.SavedStateHandle
import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.auth.AuthState
import com.martinhammer.tickdroid.data.repository.TrackPrefsRepository
import com.martinhammer.tickdroid.data.repository.TrackRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The setup button reads "Save" for a placed widget and "Add widget" only for a new one. */
class WidgetConfigViewModelTest {

    private val store = mockk<WidgetConfigStore>()
    private val authRepository = mockk<AuthRepository> {
        every { state } returns MutableStateFlow<AuthState>(AuthState.SignedOut)
    }
    private val trackRepository = mockk<TrackRepository> { every { observeTracks() } returns flowOf(emptyList()) }
    private val prefsRepository = mockk<TrackPrefsRepository> { every { observeAll() } returns flowOf(emptyMap()) }

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
}
