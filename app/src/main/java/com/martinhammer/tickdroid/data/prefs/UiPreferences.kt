package com.martinhammer.tickdroid.data.prefs

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UiPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("tickdroid_ui_prefs", Context.MODE_PRIVATE)

    private val _showPrivate = MutableStateFlow(prefs.getBoolean(KEY_SHOW_PRIVATE, false))
    val showPrivate: StateFlow<Boolean> = _showPrivate.asStateFlow()

    fun setShowPrivate(value: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_PRIVATE, value).apply()
        _showPrivate.value = value
    }

    private companion object {
        const val KEY_SHOW_PRIVATE = "show_private_tracks"
    }
}
