package com.martinhammer.tickdroid.data.prefs

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        val Default = SYSTEM
        fun fromName(name: String?): ThemeMode =
            values().firstOrNull { it.name == name } ?: Default
    }
}
