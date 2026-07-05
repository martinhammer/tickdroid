package com.martinhammer.tickdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.network.NetworkMonitor
import com.martinhammer.tickdroid.data.prefs.EditableDays
import com.martinhammer.tickdroid.data.prefs.GridDensity
import com.martinhammer.tickdroid.data.prefs.ThemeMode
import com.martinhammer.tickdroid.data.prefs.UiPreferences
import com.martinhammer.tickdroid.data.remote.TickbuddyApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountInfo(
    val serverUrl: String,
    val login: String,
)

/** Display state for the installed Tickbuddy backend version on the Account screen. */
sealed interface ServerVersionState {
    /** Fetch in flight. */
    data object Loading : ServerVersionState

    /** Server advertised a version via its capability (Tickbuddy 1.0.6+). */
    data class Known(val version: String) : ServerVersionState

    /** Fetch succeeded but the Tickbuddy capability is absent, i.e. an older backend. */
    data object Legacy : ServerVersionState

    /** Couldn't reach/parse capabilities; [offline] distinguishes no-network from server error. */
    data class Unavailable(val offline: Boolean) : ServerVersionState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val uiPreferences: UiPreferences,
    private val api: TickbuddyApi,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    val account: AccountInfo =
        authRepository.currentCredentials()?.let {
            AccountInfo(serverUrl = it.serverUrl, login = it.login)
        } ?: AccountInfo(serverUrl = "", login = "")

    private val _serverVersion = MutableStateFlow<ServerVersionState>(ServerVersionState.Loading)
    val serverVersion: StateFlow<ServerVersionState> = _serverVersion.asStateFlow()

    init {
        fetchServerVersion()
    }

    /**
     * Fetches capabilities live each time the Account screen is created, rather than caching at
     * sign-in, so a backend upgrade between login and now is reflected. On failure we report
     * "cannot check" (offline vs. unreachable) instead of guessing a version.
     */
    private fun fetchServerVersion() {
        viewModelScope.launch {
            _serverVersion.value = ServerVersionState.Loading
            _serverVersion.value = try {
                val version = api.getCapabilities().ocs.data.capabilities?.tickbuddy?.version
                if (version.isNullOrBlank()) ServerVersionState.Legacy
                else ServerVersionState.Known(version)
            } catch (e: Exception) {
                val offline = !networkMonitor.isOnline.first()
                ServerVersionState.Unavailable(offline = offline)
            }
        }
    }

    val showPrivate: StateFlow<Boolean> = uiPreferences.showPrivate

    val gridDensity: StateFlow<GridDensity> = uiPreferences.gridDensity

    val themeMode: StateFlow<ThemeMode> = uiPreferences.themeMode

    val editableDays: StateFlow<EditableDays> = uiPreferences.editableDays

    fun setShowPrivate(value: Boolean) = uiPreferences.setShowPrivate(value)

    fun setGridDensity(value: GridDensity) = uiPreferences.setGridDensity(value)

    fun setThemeMode(value: ThemeMode) = uiPreferences.setThemeMode(value)

    fun setEditableDays(value: EditableDays) = uiPreferences.setEditableDays(value)

    fun signOut() = authRepository.signOut()
}
