package com.martinhammer.tickdroid.ui.common

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.domain.formatNumber
import com.martinhammer.tickdroid.domain.optionLabel
import com.martinhammer.tickdroid.domain.shortLabel
import java.util.Locale

// What Number and Choice tracks say, in the app, on Today and on the widgets. The texts are
// pure (JVM-tested in ValueLabelsTest); only [isEmojiCodePoint] needs the phone's ICU.

/** T20: a tap on a Number or Choice track still waiting for its first sync after the update. */
internal fun awaitingSyncToast(name: String): String = "$name can be changed after the next sync."

/** T21: the Number card closed (not by Done) with text that isn't a number. */
internal fun notANumberToast(name: String): String = "$name: not a valid number, so the change wasn't saved."

/** Web L14: Done on text that isn't a number. */
internal const val EnterValidNumber = "Enter a valid number"

/** Web L15: a negative value on a track that doesn't allow one. */
internal fun negativeNotAllowedToast(name: String): String =
    "$name doesn't allow negative values, so the change wasn't saved."

/** T23: New track, an option with the same label as another. */
internal const val DuplicateOptionMessage = "Another option has this label."

/** T15's empty value: an ellipsis where the value would be. */
internal const val EmptyValueMark = "…"

/** Web L20 / T24: a Number or Choice day with nothing entered. */
internal const val NoValue = "no value"

/**
 * A Number's value as Today, TalkBack and the card's header show it: at the track's decimal
 * places, grouped, in [locale]'s separators, with the unit after it when there is one.
 */
internal fun numberWithUnit(track: Track, milli: Int, locale: Locale): String {
    val value = formatNumber(milli, track.places, locale, grouping = true)
    val unit = track.unit.orEmpty()
    return if (unit.isEmpty()) value else "$value $unit"
}

/** A Choice place's label, or "#5" for a place with no option (only a bug makes one; row 11f). */
internal fun choiceLabel(track: Track, place: Int): String = track.optionLabel(place) ?: "#$place"

/**
 * T24, the content description of a Number or Choice cell or control: "Run, 10.4 km",
 * "Mood, Happy", "Run, no value", whatever the cell itself has room for. [value] null: no entry.
 */
internal fun valueDescription(track: Track, value: Int?, locale: Locale): String {
    val state = when {
        value == null -> NoValue
        track.type == TrackType.Number -> numberWithUnit(track, value, locale)
        else -> choiceLabel(track, value)
    }
    return "${track.name}, $state"
}

/** T24, a Choice sheet's row: "Option 2, Happy". */
internal fun optionDescription(place: Int, label: String): String = "Option $place, $label"

/**
 * Whether [codePoint] starts an emoji: ICU's `Extended_Pictographic`, on every supported Android.
 * Regional indicators (flags) are handled by [shortLabel] itself.
 */
internal fun isEmojiCodePoint(codePoint: Int): Boolean =
    UCharacter.hasBinaryProperty(codePoint, UProperty.EXTENDED_PICTOGRAPHIC)

/** A Choice option as a Journal or widget cell shows it by default: its emoji or two letters. */
internal fun choiceShortLabel(label: String): String = shortLabel(label, ::isEmojiCodePoint)
