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

    private val _gridDensity = MutableStateFlow(
        GridDensity.fromName(prefs.getString(KEY_GRID_DENSITY, null))
    )
    val gridDensity: StateFlow<GridDensity> = _gridDensity.asStateFlow()

    private val _themeMode = MutableStateFlow(
        ThemeMode.fromName(prefs.getString(KEY_THEME_MODE, null))
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _editableDays = MutableStateFlow(
        EditableDays.fromName(prefs.getString(KEY_EDITABLE_DAYS, null))
    )
    val editableDays: StateFlow<EditableDays> = _editableDays.asStateFlow()

    fun setShowPrivate(value: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_PRIVATE, value).apply()
        _showPrivate.value = value
    }

    fun setGridDensity(value: GridDensity) {
        prefs.edit().putString(KEY_GRID_DENSITY, value.name).apply()
        _gridDensity.value = value
    }

    fun setThemeMode(value: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, value.name).apply()
        _themeMode.value = value
    }

    fun setEditableDays(value: EditableDays) {
        prefs.edit().putString(KEY_EDITABLE_DAYS, value.name).apply()
        _editableDays.value = value
    }

    /** Resets all UI preferences to defaults. Called on sign-out. */
    fun clear() {
        prefs.edit().clear().apply()
        _showPrivate.value = false
        _gridDensity.value = GridDensity.fromName(null)
        _themeMode.value = ThemeMode.fromName(null)
        _editableDays.value = EditableDays.fromName(null)
    }

    private companion object {
        const val KEY_SHOW_PRIVATE = "show_private_tracks"
        const val KEY_GRID_DENSITY = "grid_density"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_EDITABLE_DAYS = "editable_days"
    }
}
