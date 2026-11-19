package com.martinhammer.tickdroid.domain

/**
 * Local-only per-track UI overrides. Keyed by [Track.serverId] in storage.
 * All fields null = use defaults (type-based color, 2-letter abbreviation, short labels).
 */
data class TrackPrefs(
    val colorKey: String? = null,
    val emoji: String? = null,
    /** Choice only: what a Journal or widget cell shows. Null is [CellLabel.Short]. */
    val cellLabel: CellLabel? = null,
)

/** "Journal cells" on a Choice track: the option's short label, or its place in a circle. */
enum class CellLabel(val key: String) {
    Short("short"),
    Place("place"),
    ;

    companion object {
        fun fromKey(key: String?): CellLabel? = entries.firstOrNull { it.key == key }
    }
}
