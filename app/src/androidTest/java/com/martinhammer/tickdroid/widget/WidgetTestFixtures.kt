package com.martinhammer.tickdroid.widget

import androidx.glance.action.actionParametersOf
import com.martinhammer.tickdroid.domain.TrackType
import java.time.LocalDate

/** Hand-built rows for the Glance render tests. */
internal object WidgetTestFixtures {
    val today: LocalDate = LocalDate.of(2026, 10, 5)

    val exercise = WidgetRow(101, "Exercise", TrackType.YesNo, value = 1, emoji = "🏋", colorKey = null)
    val coffee = WidgetRow(102, "Coffee", TrackType.Counter, value = 2, emoji = "☕", colorKey = "red")
    val floss = WidgetRow(103, "Floss", TrackType.YesNo, value = 0, emoji = null, colorKey = null)
    val water = WidgetRow(104, "Glasses of water", TrackType.Counter, value = 0, emoji = "💧", colorKey = null)

    val rows = listOf(exercise, coffee, floss, water)

    /** A track of a type this Tickdroid doesn't know, with a value today. Not in [rows]. */
    val mood = WidgetRow(105, "Mood", TrackType.Unknown("future"), value = 1, emoji = null, colorKey = "blue")

    fun readOnlyParams(row: WidgetRow) = actionParametersOf(KeyTrackName to row.name)

    fun toggleParams(row: WidgetRow, day: LocalDate = today) =
        actionParametersOf(KeyTrackServerId to row.serverId, KeyEpochDay to day.toEpochDay())

    fun addOneParams(row: WidgetRow, day: LocalDate = today) =
        actionParametersOf(KeyTrackServerId to row.serverId, KeyEpochDay to day.toEpochDay(), KeyDelta to 1)
}
