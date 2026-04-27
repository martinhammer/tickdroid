package com.martinhammer.tickdroid.ui.settings

import androidx.lifecycle.ViewModel
import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.prefs.UiPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

data class AccountInfo(
    val serverUrl: String,
    val login: String,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val uiPreferences: UiPreferences,
) : ViewModel() {

    val account: AccountInfo =
        authRepository.currentCredentials()?.let {
            AccountInfo(serverUrl = it.serverUrl, login = it.login)
        } ?: AccountInfo(serverUrl = "", login = "")

    val showPrivate: StateFlow<Boolean> = uiPreferences.showPrivate

    fun setShowPrivate(value: Boolean) = uiPreferences.setShowPrivate(value)

    fun signOut() = authRepository.signOut()
}
