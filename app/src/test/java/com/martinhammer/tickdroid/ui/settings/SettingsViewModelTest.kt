package com.martinhammer.tickdroid.ui.settings

import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.auth.Credentials
import com.martinhammer.tickdroid.data.network.NetworkMonitor
import com.martinhammer.tickdroid.data.prefs.UiPreferences
import com.martinhammer.tickdroid.data.remote.OcsBody
import com.martinhammer.tickdroid.data.remote.OcsEnvelope
import com.martinhammer.tickdroid.data.remote.OcsMeta
import com.martinhammer.tickdroid.data.remote.TickbuddyApi
import com.martinhammer.tickdroid.data.remote.dto.Capabilities
import com.martinhammer.tickdroid.data.remote.dto.CapabilitiesData
import com.martinhammer.tickdroid.data.remote.dto.TickbuddyCapability
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException

class SettingsViewModelTest {

    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val uiPreferences = mockk<UiPreferences>(relaxed = true)
    private val api = mockk<TickbuddyApi>()
    private val networkMonitor = mockk<NetworkMonitor>()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { authRepository.currentCredentials() } returns
            Credentials(serverUrl = "https://cloud.example.com", login = "alice", appPassword = "pw")
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun capabilitiesEnvelope(tickbuddy: TickbuddyCapability?) =
        OcsEnvelope(
            OcsBody(
                meta = OcsMeta(status = "ok", statusCode = 200),
                data = CapabilitiesData(capabilities = Capabilities(tickbuddy = tickbuddy)),
            ),
        )

    private fun viewModel() = SettingsViewModel(authRepository, uiPreferences, api, networkMonitor)

    @Test fun `version present maps to Known`() {
        coEvery { api.getCapabilities() } returns
            capabilitiesEnvelope(TickbuddyCapability(version = "1.0.6"))

        val state = viewModel().serverVersion.value

        assertEquals(ServerVersionState.Known("1.0.6"), state)
    }

    @Test fun `absent tickbuddy capability maps to Legacy`() {
        coEvery { api.getCapabilities() } returns capabilitiesEnvelope(tickbuddy = null)

        val state = viewModel().serverVersion.value

        assertEquals(ServerVersionState.Legacy, state)
    }

    @Test fun `blank version maps to Legacy`() {
        coEvery { api.getCapabilities() } returns
            capabilitiesEnvelope(TickbuddyCapability(version = ""))

        val state = viewModel().serverVersion.value

        assertEquals(ServerVersionState.Legacy, state)
    }

    @Test fun `fetch failure while offline maps to Unavailable offline`() {
        coEvery { api.getCapabilities() } throws IOException("no route to host")
        every { networkMonitor.isOnline } returns flowOf(false)

        val state = viewModel().serverVersion.value

        assertEquals(ServerVersionState.Unavailable(offline = true), state)
    }

    @Test fun `fetch failure while online maps to Unavailable unreachable`() {
        coEvery { api.getCapabilities() } throws IOException("500")
        every { networkMonitor.isOnline } returns flowOf(true)

        val state = viewModel().serverVersion.value

        assertEquals(ServerVersionState.Unavailable(offline = false), state)
    }
}
