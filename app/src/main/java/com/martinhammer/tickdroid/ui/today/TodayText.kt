package com.martinhammer.tickdroid.ui.today

import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.SimpleDateFormat
import android.icu.util.ULocale
import com.martinhammer.tickdroid.domain.TrackRun
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

/** How far back a day is, which decides Today's title and the line under it. */
sealed interface DayKind {
    data object Today : DayKind
    data object Yesterday : DayKind

    /** 2 to 6 days back. */
    data class DaysAgo(val days: Int) : DayKind

    /** 7 or more days back: the title is the date, with the year only if it is an earlier one. */
    data class Date(val titleHasYear: Boolean, val lineHasYear: Boolean) : DayKind
}

fun dayKind(day: LocalDate, today: LocalDate): DayKind {
    val back = ChronoUnit.DAYS.between(day, today)
    return when {
        back <= 0L -> DayKind.Today
        back == 1L -> DayKind.Yesterday
        back < 7L -> DayKind.DaysAgo(back.toInt())
        else -> DayKind.Date(titleHasYear = day.year != today.year, lineHasYear = true)
    }
}

/**
 * Today's title: "Today", "Yesterday" and "3 days ago" from ICU in the phone's language (as the
 * web's `Intl.RelativeTimeFormat`), capitalised as at the start of a sentence; from 7 days back,
 * the date ("21 October", with the year in an earlier year) in the locale's own order.
 */
fun dayTitle(day: LocalDate, today: LocalDate, locale: Locale): String {
    val relative by lazy {
        RelativeDateTimeFormatter.getInstance(
            ULocale.forLocale(locale),
            null,
            RelativeDateTimeFormatter.Style.LONG,
            DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE,
        )
    }
    return when (val kind = dayKind(day, today)) {
        DayKind.Today -> relative.format(RelativeDateTimeFormatter.Direction.THIS, RelativeDateTimeFormatter.AbsoluteUnit.DAY)
        DayKind.Yesterday -> relative.format(RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.AbsoluteUnit.DAY)
        is DayKind.DaysAgo -> relative.format(
            kind.days.toDouble(),
            RelativeDateTimeFormatter.Direction.LAST,
            RelativeDateTimeFormatter.RelativeUnit.DAYS,
        )
        is DayKind.Date -> formatDate(day, if (kind.titleHasYear) "dMMMMy" else "dMMMM", locale)
    }
}

/**
 * The line under the title, and the note card's "Note for …": "Wednesday 28 October", with the
 * year from 7 days back ("Wednesday 21 October 2026").
 */
fun dayLine(day: LocalDate, today: LocalDate, locale: Locale): String {
    val withYear = (dayKind(day, today) as? DayKind.Date)?.lineHasYear == true
    return formatDate(day, if (withYear) "EEEEdMMMMy" else "EEEEdMMMM", locale)
}

/** [skeleton] through the locale's best pattern, so order and punctuation follow the locale. */
private fun formatDate(day: LocalDate, skeleton: String, locale: Locale): String {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    val date = Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant())
    return SimpleDateFormat(pattern, locale).format(date)
}

/**
 * A track's current run in Tickcli's words, as the web's `runText`: "3-day streak", "1-day
 * streak, up to yesterday", "21-day break", or "No ticks yet". Null (no text) for a track the
 * stats answer left out: "No ticks yet" would be wrong for a track with ticks.
 */
fun runText(run: TrackRun?): String? {
    if (run == null) return null
    val length = run.length ?: return "No ticks yet"
    return when {
        run.isStreak != true -> "$length-day break"
        run.upToYesterday -> "$length-day streak, up to yesterday"
        else -> "$length-day streak"
    }
}

/**
 * The seven days of a row's squares, newest first: the seven ending today while [day] is one of
 * them, otherwise [day] and the six days after it (so [day] is the last, right-hand square).
 */
fun squareWindow(today: LocalDate, day: LocalDate): List<LocalDate> {
    val newest = if (day.isAfter(today.minusDays(SQUARES.toLong()))) today else day.plusDays(SQUARES - 1L)
    return (0 until SQUARES).map { newest.minusDays(it.toLong()) }
}

const val SQUARES = 7

/** Alpha per level, from the web's `src/heatmap.ts`: index 0 is unused (empty days). */
val LEVEL_ALPHA = floatArrayOf(0f, 0.25f, 0.45f, 0.68f, 0.9f)

/**
 * The web's `levelFor` (`src/heatmap.ts`) line for line, so both shade a day the same way. Yes/no
 * days get level 2; counters are quantised into four levels against the track's own maximum,
 * and small ranges map value to level directly.
 */
fun levelFor(value: Int, isCounter: Boolean, maxValue: Int): Int {
    if (value <= 0) return 0
    if (!isCounter) return 2
    if (maxValue <= 4) return minOf(4, value)
    return maxOf(1, minOf(4, Math.ceil(value.toDouble() / maxValue * 4).toInt()))
}
