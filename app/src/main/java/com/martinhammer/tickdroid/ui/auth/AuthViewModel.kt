package com.martinhammer.tickdroid.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.martinhammer.tickdroid.data.auth.AuthProbeResult
import com.martinhammer.tickdroid.data.auth.AuthProber
import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.auth.Credentials
import com.martinhammer.tickdroid.data.remote.ServerUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val serverUrl: String = "",
    val login: String = "",
    val appPassword: String = "",
    val allowHttp: Boolean = false,
    val probing: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val prober: AuthProber,
) : ViewModel() {

    private val _ui = MutableStateFlow(AuthUiState())
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()

    fun onServerUrlChange(value: String) = _ui.update { it.copy(serverUrl = value, error = null) }
    fun onLoginChange(value: String) = _ui.update { it.copy(login = value, error = null) }
    fun onAppPasswordChange(value: String) = _ui.update { it.copy(appPassword = value, error = null) }
    fun onAllowHttpChange(value: Boolean) = _ui.update { it.copy(allowHttp = value, error = null) }

    fun signIn() {
        val state = _ui.value
        val normalizedUrl = ServerUrl.normalize(state.serverUrl, allowHttp = state.allowHttp)
        if (normalizedUrl == null) {
            _ui.update { it.copy(error = "Enter a valid https:// URL") }
            return
        }
        if (state.login.isBlank() || state.appPassword.isBlank()) {
            _ui.update { it.copy(error = "Login and app password are required") }
            return
        }
        val credentials = Credentials(normalizedUrl, state.login.trim(), state.appPassword)
        _ui.update { it.copy(probing = true, error = null) }
        viewModelScope.launch {
            val result = prober.probe(credentials)
            val message = when (result) {
                AuthProbeResult.Success -> {
                    authRepository.signIn(credentials)
                    null
                }
                AuthProbeResult.InvalidUrl ->
                    "Server URL is invalid. Make sure it includes the scheme (e.g. https://cloud.example.com)."
                is AuthProbeResult.Unreachable ->
                    "Could not reach the server: ${result.message}. Check the URL, your network, and that the server is running."
                AuthProbeResult.NotNextcloud ->
                    "That URL responded but doesn't look like a Nextcloud server. " +
                        "If Nextcloud is hosted under a subpath, include it (e.g. https://example.com/nextcloud)."
                AuthProbeResult.Unauthorized ->
                    "Login or app password rejected. Generate a fresh app password under " +
                        "Settings → Security → Devices & sessions, and double-check the login name."
                AuthProbeResult.TickbuddyNotInstalled ->
                    "Connected and signed in, but the Tickbuddy app isn't installed or enabled on this Nextcloud server. " +
                        "Ask an admin to enable it under Apps."
                is AuthProbeResult.UnexpectedStatus ->
                    "Unexpected response from the server (HTTP ${result.code} during ${result.stage.name.lowercase()} check). Try again or check server logs."
            }
            _ui.update { it.copy(probing = false, error = message) }
        }
    }
}
