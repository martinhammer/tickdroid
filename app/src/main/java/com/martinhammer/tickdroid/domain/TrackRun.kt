package com.martinhammer.tickdroid.domain

/**
 * A track's current streak or break as the server last worked it out (`GET /api/stats`).
 * [isStreak] and [length] are null for a track with no ticks. [maxValue] is the track's highest
 * tick value, for the Today squares' shading.
 */
data class TrackRun(
    val isStreak: Boolean?,
    val length: Int?,
    val upToYesterday: Boolean,
    val maxValue: Int,
)
