package com.martinhammer.tickdroid.data.prefs

enum class JournalLayout {
    DAYS_DOWN,
    TRACKS_DOWN;

    companion object {
        val Default = DAYS_DOWN
        fun fromName(name: String?): JournalLayout =
            values().firstOrNull { it.name == name } ?: Default
    }
}
