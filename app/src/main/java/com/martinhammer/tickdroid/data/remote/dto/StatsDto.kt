package com.martinhammer.tickdroid.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * One entry of `GET /api/stats`, only the fields Tickdroid uses (the shared Json ignores the
 * rest). [current] is null for a track with no ticks; [maxValue] is the track's highest tick
 * value, for the Today squares' shading.
 */
@Serializable
data class StatsDto(
    val trackId: Long,
    val current: RunDto? = null,
    val upToYesterday: Boolean = false,
    val maxValue: Int = 0,
)

@Serializable
data class RunDto(
    val isStreak: Boolean,
    val length: Int,
)
