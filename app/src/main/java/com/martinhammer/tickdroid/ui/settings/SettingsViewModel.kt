package com.martinhammer.tickdroid.ui.settings

import androidx.lifecycle.ViewModel
import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.prefs.EditableDays
import com.martinhammer.tickdroid.data.prefs.GridDensity
import com.martinhammer.tickdroid.data.prefs.ThemeMode
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

    val gridDensity: StateFlow<GridDensity> = uiPreferences.gridDensity

    val themeMode: StateFlow<ThemeMode> = uiPreferences.themeMode

    val editableDays: StateFlow<EditableDays> = uiPreferences.editableDays

    fun setShowPrivate(value: Boolean) = uiPreferences.setShowPrivate(value)

    fun setGridDensity(value: GridDensity) = uiPreferences.setGridDensity(value)

    fun setThemeMode(value: ThemeMode) = uiPreferences.setThemeMode(value)

    fun setEditableDays(value: EditableDays) = uiPreferences.setEditableDays(value)

    fun signOut() = authRepository.signOut()
}
