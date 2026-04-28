package com.martinhammer.tickdroid.data.prefs

import java.time.LocalDate

enum class EditableDays {
    ACTIVE_DAY,
    ACTIVE_AND_PREVIOUS,
    ONE_WEEK,
    ALL_DAYS;

    /** Number of days back from today that remain editable. `null` means unlimited. */
    val historyDays: Long?
        get() = when (this) {
            ACTIVE_DAY -> 0
            ACTIVE_AND_PREVIOUS -> 1
            ONE_WEEK -> 6
            ALL_DAYS -> null
        }

    fun isEditable(day: LocalDate, today: LocalDate): Boolean {
        if (day.isAfter(today)) return false
        val limit = historyDays ?: return true
        return !day.isBefore(today.minusDays(limit))
    }

    companion object {
        val Default = ACTIVE_DAY
        fun fromName(name: String?): EditableDays =
            values().firstOrNull { it.name == name } ?: Default
    }
}
