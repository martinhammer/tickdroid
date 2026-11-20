package com.martinhammer.tickdroid.domain

/**
 * A track's current streak or break as the server last worked it out (`GET /api/stats`).
 * [isStreak] and [length] are null for a track with no ticks. [maxValue] is the track's highest
 * value, for the Today squares' shading: a count, a Number's thousandths, a scale's place (0
 * when unknown). [minValue] is a Number's lowest in thousandths, or 1 on an ordered scale; null
 * elsewhere or when unknown.
 */
data class TrackRun(
    val isStreak: Boolean?,
    val length: Int?,
    val upToYesterday: Boolean,
    val maxValue: Int,
    val minValue: Int? = null,
)
