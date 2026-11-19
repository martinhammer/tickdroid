package com.martinhammer.tickdroid.ui.journal

import com.martinhammer.tickdroid.domain.formatNumber
import java.math.BigDecimal
import java.util.Locale

// How a Number (or a Choice's place) is fitted into a cell (decision 1, open decision 8). Pure and
// JVM-tested (ValueCellTextTest), and shared by the Journal's TickCell and the widgets' cell, so
// the two always pick the same text: Glance can't measure text, so both go by this estimate.

/** The sizes a cell's value steps down through: the counter's `titleMedium`, then smaller. */
internal val CellTextSizesSp = floatArrayOf(16f, 13f, 11f, 9f)

/** Between the cell's edge and its text, each side. */
internal const val CellTextPaddingDp = 3f

/** A digit's advance in em: Roboto's tabular figures are 0.56em; a little over, to stay safe. */
internal const val AppDigitAdvanceEm = 0.58f

/** RemoteViews' Medium, without tabular figures: a touch wider on average to stay safe. */
internal const val WidgetDigitAdvanceEm = 0.6f

/** A separator's or the minus sign's advance in em (Roboto's point and comma are about 0.26em). */
private const val NarrowAdvanceEm = 0.3f

/** The text a cell shows and its size. [sizeSp] is before the user's font scale. */
internal data class CellText(val text: String, val sizeSp: Float)

/**
 * The text for a Number cell [cellDp] wide: [milli] at the track's [decimalPlaces] (no grouping)
 * at 16, 13, 11 and 9sp, the first that fits; then rounded to a whole number at the same sizes;
 * then [compact]'s short form ("1.2k", the phone's `CompactDecimalFormat`) at the largest size that
 * fits, or 9sp if none does. [fontScale] is the user's font size setting.
 */
internal fun numberCellText(
    milli: Int,
    decimalPlaces: Int,
    cellDp: Float,
    fontScale: Float,
    locale: Locale,
    compact: (BigDecimal) -> String,
    digitAdvanceEm: Float = AppDigitAdvanceEm,
): CellText {
    val candidates = buildList {
        add(formatNumber(milli, decimalPlaces, locale, grouping = false))
        if (decimalPlaces > 0) add(formatNumber(milli, 0, locale, grouping = false))
    }
    fitText(candidates, cellDp, fontScale, digitAdvanceEm)?.let { return it }
    val short = compact(BigDecimal.valueOf(milli.toLong(), 3))
    return fitText(listOf(short), cellDp, fontScale, digitAdvanceEm) ?: CellText(short, CellTextSizesSp.last())
}

/** A Choice's place in its circle ([circleDp] across), by the same steps; never shortened. */
internal fun placeCellText(place: Int, circleDp: Float, fontScale: Float, digitAdvanceEm: Float = AppDigitAdvanceEm): CellText {
    val text = place.toString()
    // The circle's text has no padding of its own beyond what the round edge leaves.
    return fitText(listOf(text), circleDp + CellTextPaddingDp, fontScale, digitAdvanceEm)
        ?: CellText(text, CellTextSizesSp.last())
}

/** The first of [candidates] that fits at some size, each tried at every size before the next. */
private fun fitText(candidates: List<String>, cellDp: Float, fontScale: Float, digitAdvanceEm: Float): CellText? {
    val available = cellDp - CellTextPaddingDp * 2
    for (text in candidates) {
        for (size in CellTextSizesSp) {
            if (estimatedWidthDp(text, size * fontScale, digitAdvanceEm) <= available) return CellText(text, size)
        }
    }
    return null
}

/** About how wide [text] is at [sizeSp] (already font-scaled): digits tabular, separators narrow. */
internal fun estimatedWidthDp(text: String, sizeSp: Float, digitAdvanceEm: Float): Float {
    var em = 0f
    text.forEach { c ->
        em += if (c.isDigit() || c.isLetter()) digitAdvanceEm else NarrowAdvanceEm
    }
    return em * sizeSp
}

/** A Choice place's circle: about 72% of the cell. */
internal const val PlaceCircleFraction = 0.72f

/** How Today's wide control shows a Number: the value's size, and whether the unit fits after it. */
internal data class ControlFit(val sizeSp: Float, val showUnit: Boolean)

/** The unit's size on Today's control (`bodySmall`), and the gap before it. */
internal const val ControlUnitSp = 12f
internal const val ControlUnitGapDp = 4f

/**
 * Fits a Number's [value] (already formatted, with grouping) and [unit] into Today's control,
 * [widthDp] wide inside its padding (open decision 9). The value is never cut: the unit is left
 * out first, then the value steps down through the cell sizes; at the smallest it is shown
 * whatever its width.
 */
internal fun todayNumberFit(value: String, unit: String, widthDp: Float, fontScale: Float): ControlFit {
    val first = CellTextSizesSp.first()
    if (unit.isNotEmpty()) {
        val withUnit = estimatedWidthDp(value, first * fontScale, AppDigitAdvanceEm) + ControlUnitGapDp +
            estimatedWidthDp(unit, ControlUnitSp * fontScale, AppDigitAdvanceEm)
        if (withUnit <= widthDp) return ControlFit(first, showUnit = true)
    }
    for (size in CellTextSizesSp) {
        if (estimatedWidthDp(value, size * fontScale, AppDigitAdvanceEm) <= widthDp) return ControlFit(size, showUnit = false)
    }
    return ControlFit(CellTextSizesSp.last(), showUnit = false)
}

/** Whether "… km", Today's empty Number (T15), has room for its unit in [widthDp]. */
internal fun emptyControlShowsUnit(unit: String, widthDp: Float, fontScale: Float): Boolean =
    unit.isNotEmpty() &&
        estimatedWidthDp("…", CellTextSizesSp.first() * fontScale, AppDigitAdvanceEm) + ControlUnitGapDp +
        estimatedWidthDp(unit, ControlUnitSp * fontScale, AppDigitAdvanceEm) <= widthDp
