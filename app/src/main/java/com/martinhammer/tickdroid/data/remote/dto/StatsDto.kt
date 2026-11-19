package com.martinhammer.tickdroid.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One entry of `GET /api/stats`, only the fields Tickdroid uses (the shared Json ignores the
 * rest). [current] is null for a track with no ticks. [maxValue] and [minValue] are the track's
 * highest and lowest values, for the Today squares' shading, kept raw and converted per type in
 * `SyncManager.refreshRuns`: a counter's maximum is an integer, a Number's a decimal (or null with
 * no entries), an ordered scale's a place (null on a plain list). Read as integers they would fail
 * the whole stats read on the first decimal.
 */
@Serializable
data class StatsDto(
    val trackId: Long,
    val current: RunDto? = null,
    val upToYesterday: Boolean = false,
    val maxValue: JsonElement? = null,
    val minValue: JsonElement? = null,
)

@Serializable
data class RunDto(
    val isStreak: Boolean,
    val length: Int,
)
