package com.martinhammer.tickdroid.domain

/**
 * Local-only per-track UI overrides. Keyed by [Track.serverId] in storage.
 * Both fields null = use defaults (type-based color, 2-letter abbreviation).
 */
data class TrackPrefs(
    val colorKey: String? = null,
    val emoji: String? = null,
)
