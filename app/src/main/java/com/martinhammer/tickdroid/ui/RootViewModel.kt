package com.martinhammer.tickdroid.ui

import androidx.lifecycle.ViewModel
import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.auth.AuthState
import com.martinhammer.tickdroid.data.prefs.ThemeMode
import com.martinhammer.tickdroid.data.prefs.UiPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    authRepository: AuthRepository,
    uiPreferences: UiPreferences,
) : ViewModel() {
    val authState: StateFlow<AuthState> = authRepository.state
    val themeMode: StateFlow<ThemeMode> = uiPreferences.themeMode
}
