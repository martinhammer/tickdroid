package com.martinhammer.tickdroid.ui.journal

import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Pure, Compose-free maths for the "Tracks down" journal layout, so it can be JVM unit-tested.
// All sizes are plain Float dp.

/**
 * Share of the (capped) grid width reserved for the track-name column: ~148dp on a 411dp
 * phone, enough for an emoji plus a typical name on one line. Day slots get what's left.
 */
internal const val NameColumnFraction = 0.36f
internal const val MinDaysVisible = 3

/**
 * Each day column is a slot of [slotDp]; the cell sits centred in it at [cellDp]. Slots touch,
 * so the weekend tint forms a continuous band and the visible gap between cells is [CellGapDp].
 */
internal data class TracksDownMetrics(val daysVisible: Int, val slotDp: Float, val cellDp: Float)

/**
 * Up to the [GridSizingMaxWidthDp] cap (portrait), the name column gets [NameColumnFraction]
 * of the width and [maxDays] slots split the rest; fewer show when they wouldn't fit at
 * [MinCellSizeDp] (e.g. High gives 7 on a 411dp phone).
 *
 * Beyond the cap (landscape, tablets) the slot and name-column sizes stay what the cap gives,
 * the cap's "portrait-equivalent" size, as in Days down, and the extra width becomes extra
 * visible days rather than a wide empty name column. So density sets the cell size there, not
 * the count. The leftover of less than one slot goes to the name column.
 *
 * @param widthDp width available to the grid, insets already excluded
 * @param maxDays GridDensity.tracksDownDays for the current density
 */
internal fun tracksDownMetrics(widthDp: Float, maxDays: Int): TracksDownMetrics {
    val effective = minOf(widthDp, GridSizingMaxWidthDp)
    val nameColumn = effective * NameColumnFraction
    val available = effective - nameColumn - RightPadDp
    val fit = (available / (MinCellSizeDp + CellGapDp)).toInt()
    val capped = minOf(maxDays, fit).coerceAtLeast(MinDaysVisible)
    val slot = available / capped
    val cell = (slot - CellGapDp).coerceIn(MinCellSizeDp, MaxCellSizeDp)
    val n = if (widthDp > GridSizingMaxWidthDp) {
        ((widthDp - nameColumn - RightPadDp) / slot).toInt().coerceAtLeast(capped)
    } else {
        capped
    }
    return TracksDownMetrics(n, slot, cell)
}

/** Day shown in column [index]; column 0 is today, higher columns are older. */
internal fun dayForColumn(index: Int, today: LocalDate): LocalDate = today.minusDays(index.toLong())

/** Days in view when [firstColumn] is the leftmost visible column, newest first. */
internal fun daysInView(firstColumn: Int, daysVisible: Int, today: LocalDate): List<LocalDate> =
    (firstColumn until firstColumn + daysVisible).map { dayForColumn(it, today) }

/** Number of day columns inside the loaded window (today back to its oldest day). */
internal fun columnCount(window: DateWindow): Int =
    ChronoUnit.DAYS.between(window.oldestVisible, window.today).toInt() + 1

/**
 * Splits a track name into at most two label lines without ever breaking a word: line 1 takes
 * as many whole words as [fits], line 2 gets the rest. Each line is rendered single-line with an
 * ellipsis, so a word too wide for a line is cut off ("Exerc…") rather than wrapped mid-word,
 * and a long remainder ends in "…" on line 2. Android's line breaker has no "never split a
 * word" mode, hence doing it here.
 *
 * @param fits whether a candidate line fits the available width.
 */
internal fun splitLabel(name: String, fits: (String) -> Boolean): List<String> {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return listOf(name)
    var n = 1
    while (n < words.size && fits(words.subList(0, n + 1).joinToString(" "))) n++
    val first = words.subList(0, n).joinToString(" ")
    val rest = words.subList(n, words.size).joinToString(" ")
    return if (rest.isEmpty()) listOf(first) else listOf(first, rest)
}
