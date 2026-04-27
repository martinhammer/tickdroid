package com.martinhammer.tickdroid.data.prefs

enum class GridDensity(val visibleTracks: Int) {
    LOW(5),
    MEDIUM(7),
    HIGH(9);

    companion object {
        val Default = MEDIUM
        fun fromName(name: String?): GridDensity =
            values().firstOrNull { it.name == name } ?: Default
    }
}
