package com.martinhammer.tickdroid.widget

import androidx.glance.action.actionParametersOf
import com.martinhammer.tickdroid.domain.TrackType
import java.time.LocalDate

/** Hand-built rows for the Glance render tests. */
internal object WidgetTestFixtures {
    val today: LocalDate = LocalDate.of(2026, 10, 5)

    val exercise = WidgetRow(101, "Exercise", TrackType.BOOLEAN, value = 1, emoji = "🏋", colorKey = null)
    val coffee = WidgetRow(102, "Coffee", TrackType.COUNTER, value = 2, emoji = "☕", colorKey = "red")
    val floss = WidgetRow(103, "Floss", TrackType.BOOLEAN, value = 0, emoji = null, colorKey = null)
    val water = WidgetRow(104, "Glasses of water", TrackType.COUNTER, value = 0, emoji = "💧", colorKey = null)

    val rows = listOf(exercise, coffee, floss, water)

    fun toggleParams(row: WidgetRow, day: LocalDate = today) =
        actionParametersOf(KeyTrackServerId to row.serverId, KeyEpochDay to day.toEpochDay())

    fun addOneParams(row: WidgetRow, day: LocalDate = today) =
        actionParametersOf(KeyTrackServerId to row.serverId, KeyEpochDay to day.toEpochDay(), KeyDelta to 1)
}
