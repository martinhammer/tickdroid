package com.martinhammer.tickdroid.data.prefs

/**
 * @property visibleTracks Days down: track columns fully visible (plus a half-cell peek).
 * @property tracksDownDays Tracks down: day columns visible in portrait. Lower than
 *   [visibleTracks] because the name column takes a fixed share of the width there; Medium at 6
 *   keeps cells at their Days-down size on a 411dp phone instead of squeezing 7 in at ~29dp.
 */
enum class GridDensity(val visibleTracks: Int, val tracksDownDays: Int) {
    LOW(5, 5),
    MEDIUM(7, 6),
    HIGH(9, 8);

    companion object {
        val Default = MEDIUM
        fun fromName(name: String?): GridDensity =
            values().firstOrNull { it.name == name } ?: Default
    }
}
