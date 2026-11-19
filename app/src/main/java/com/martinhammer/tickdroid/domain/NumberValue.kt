package com.martinhammer.tickdroid.domain

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.BreakIterator
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

// A Number track's values, as whole thousandths in an Int (10.4 is 10400, −1.5 is −1500), exactly
// as the server stores them, so nothing is ever cut or rounded by a float. Pure and JVM-tested
// (NumberValueTest): BigDecimal and java.text only.

/** The server's limit either way, in thousandths: 2,147,483.647 (`NumberValue::MAX_MILLI`). */
const val MAX_MILLI = Int.MAX_VALUE

/** The most decimal places a Number track can have. */
const val MAX_DECIMAL_PLACES = 3

/** What the server sends: digits, an optional point and digits. Never an exponent or grouping. */
private val WIRE_NUMBER = Regex("""-?\d+(\.\d+)?""")

/** What can be typed: one point or one comma as the decimal separator (decision 4). */
private val TYPED_NUMBER = Regex("""([-−])?(\d+)([.,](\d+))?""")

/**
 * A Number's value off the wire, in thousandths, read from the JSON number's own text (never
 * through a Double). Null when it isn't a number, has more than 3 decimals, or is out of range:
 * the caller skips that tick rather than store it wrong.
 */
fun numberFromWire(p: JsonPrimitive): Int? {
    if (p is JsonNull) return null
    val text = p.content.trim()
    if (!WIRE_NUMBER.matches(text)) return null
    val milli = try {
        BigDecimal(text).movePointRight(3).setScale(0, RoundingMode.UNNECESSARY)
    } catch (_: ArithmeticException) {
        return null // more than 3 decimals: the server never sends one
    }
    return milli.toIntInRange()
}

/** The value for the API: a point, no grouping, no exponent, no trailing zeros (10400 → "10.4"). */
fun numberToWire(milli: Int): String =
    BigDecimal.valueOf(milli.toLong(), 3).stripTrailingZeros().let {
        // stripTrailingZeros gives 72000 a negative scale ("7.2E+4" in toString), never in toPlainString.
        if (it.signum() == 0) "0" else it.toPlainString()
    }

/**
 * [milli] rounded to [decimalPlaces], half away from zero as the server and the web round, still
 * in thousandths. A Long: rounding the limit up can pass it (2,147,483.647 at 2 is 2,147,483.65).
 */
fun roundMilli(milli: Int, decimalPlaces: Int): Long =
    roundedDecimal(BigDecimal.valueOf(milli.toLong(), 3), decimalPlaces).movePointRight(3).toLong()

/**
 * [milli] for display: rounded to [decimalPlaces] and shown with exactly that many decimals, in
 * [locale]'s own separators and minus sign, grouped or not. A value that rounds to zero has no
 * minus ("0.0", never "−0.0").
 */
fun formatNumber(milli: Int, decimalPlaces: Int, locale: Locale, grouping: Boolean): String {
    val places = decimalPlaces.coerceIn(0, MAX_DECIMAL_PLACES)
    var rounded = roundedDecimal(BigDecimal.valueOf(milli.toLong(), 3), places)
    if (rounded.signum() == 0) rounded = BigDecimal.ZERO.setScale(places)
    return numberFormat(locale, places, grouping).format(rounded)
}

/** A number of thousandths in a field: [formatNumber] without grouping, for editing. */
fun editableNumber(milli: Int, decimalPlaces: Int, locale: Locale): String =
    formatNumber(milli, decimalPlaces, locale, grouping = false)

private fun numberFormat(locale: Locale, places: Int, grouping: Boolean): DecimalFormat =
    (NumberFormat.getNumberInstance(locale) as DecimalFormat).apply {
        minimumFractionDigits = places
        maximumFractionDigits = places
        isGroupingUsed = grouping
        roundingMode = RoundingMode.HALF_UP
    }

private fun roundedDecimal(value: BigDecimal, decimalPlaces: Int): BigDecimal =
    // HALF_UP on a BigDecimal rounds 0.5 away from zero both ways (−10.45 → −10.5).
    value.setScale(decimalPlaces.coerceIn(0, MAX_DECIMAL_PLACES), RoundingMode.HALF_UP)

private fun BigDecimal.toIntInRange(): Int? =
    if (abs() > BigDecimal.valueOf(MAX_MILLI.toLong())) null else toInt()

/** What a Number card's text means. */
sealed interface Typed {
    /** Blank after trimming: clear the day. */
    data object Empty : Typed

    /** A value, already rounded to the track's decimal places, in thousandths. */
    data class Value(val milli: Int) : Typed

    data object NotANumber : Typed

    /** Below 0 on a track without negative values. */
    data object Negative : Typed

    /** Beyond ±2,147,483.647 after rounding. */
    data object TooLarge : Typed
}

/**
 * Reads what was typed into a Number card. An optional `-` or `−` (U+2212, as the web takes),
 * digits, then at most one point or comma followed by digits: either separator is the decimal
 * one, and anything with more than one separator is not a number (decision 4). A leading or
 * trailing separator (".5", "5.") is not a number either, as on the server. The value is
 * rounded to [decimalPlaces] before the checks.
 */
fun parseTyped(text: String, decimalPlaces: Int, allowNegative: Boolean): Typed {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return Typed.Empty
    val match = TYPED_NUMBER.matchEntire(trimmed) ?: return Typed.NotANumber
    val (sign, whole, _, fraction) = match.destructured
    val plain = (if (sign.isEmpty()) "" else "-") + whole + (if (fraction.isEmpty()) "" else ".$fraction")
    val rounded = roundedDecimal(BigDecimal(plain), decimalPlaces)
    if (rounded.signum() < 0 && !allowNegative) return Typed.Negative
    val milli = rounded.movePointRight(3).toIntInRange() ?: return Typed.TooLarge
    return Typed.Value(milli)
}

/** The limit in [locale]'s words (T22): "Numbers must be between −2,147,483.647 and 2,147,483.647". */
fun numberLimitMessage(locale: Locale): String {
    val format = numberFormat(locale, MAX_DECIMAL_PLACES, grouping = true)
    val max = BigDecimal.valueOf(MAX_MILLI.toLong(), 3)
    return "Numbers must be between ${format.format(max.negate())} and ${format.format(max)}"
}

/**
 * Pasted text read with the web's rule for [locale] (server spec row 16): its group separator
 * only between groups of three digits, its decimal separator, and a point also taken as the
 * decimal separator where the group separator isn't a point. Returns the number without grouping,
 * with [locale]'s decimal separator ("1,204.5" in English → "1204.5"; "1.204,5" in Dutch →
 * "1204,5"), or null when the rule can't read it (the paste then lands as it is).
 */
fun ungroupPasted(text: String, locale: Locale): String? {
    val symbols = DecimalFormatSymbols.getInstance(locale)
    val group = symbols.groupingSeparator
    val decimal = symbols.decimalSeparator
    // A no-break space as the group separator (French, say) is typed or copied as a plain one too.
    val groups = if (group.isWhitespace() || group == ' ' || group == ' ') {
        setOf(group, ' ', ' ', ' ')
    } else {
        setOf(group)
    }
    val decimals = if (decimal != '.' && '.' !in groups) setOf(decimal, '.') else setOf(decimal)
    var rest = text.trim()
    if (rest.isEmpty()) return null
    val sign = if (rest[0] == '-' || rest[0] == '−') "-".also { rest = rest.substring(1) } else ""
    val decimalAt = rest.indexOfFirst { it in decimals }
    val whole = if (decimalAt < 0) rest else rest.substring(0, decimalAt)
    val fraction = if (decimalAt < 0) null else rest.substring(decimalAt + 1)
    if (fraction != null && (fraction.isEmpty() || !fraction.all { it in '0'..'9' })) return null
    val digits = when {
        whole.isNotEmpty() && whole.all { it in '0'..'9' } -> whole
        else -> {
            // Grouped: 1 to 3 digits, then groups of exactly three, each after one separator.
            val parts = whole.split(*groups.toCharArray())
            val ok = parts.size > 1 && parts[0].length in 1..3 &&
                parts.all { p -> p.isNotEmpty() && p.all { it in '0'..'9' } } &&
                parts.drop(1).all { it.length == 3 }
            if (!ok) return null
            parts.joinToString("")
        }
    }
    val outDecimal = if (decimal == ',' || decimal == '.') decimal else '.'
    return sign + digits + (fraction?.let { "$outDecimal$it" } ?: "")
}

/**
 * A Choice option's label as a Journal or widget cell shows it: its first grapheme when that is
 * an emoji (with its modifiers, variation selector, joined parts or flag pair), else its first
 * two graphemes in capitals, as `abbreviation()` does but never cutting a surrogate pair.
 * [isEmoji] tells whether a code point starts an emoji: on the phone ICU's
 * `Extended_Pictographic`, which the JVM doesn't have, hence a parameter.
 */
fun shortLabel(label: String, isEmoji: (codePoint: Int) -> Boolean): String {
    val text = label.trim()
    if (text.isEmpty()) return ""
    val first = text.codePointAt(0)
    if (isEmoji(first) || isRegionalIndicator(first)) return emojiCluster(text)
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    iterator.next()
    val end = iterator.next().takeIf { it != BreakIterator.DONE } ?: text.length
    return text.substring(0, end).uppercase()
}

/** Flags are two regional indicators; neither is `Extended_Pictographic`. */
fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

/**
 * The emoji at the start of [text], with what belongs to it: skin-tone modifiers, variation
 * selectors, a keycap, tags, and parts joined by ZWJ; or a flag's two regional indicators. By
 * hand rather than BreakIterator, whose emoji clusters depend on the JDK.
 */
private fun emojiCluster(text: String): String {
    val first = text.codePointAt(0)
    var end = Character.charCount(first)
    if (isRegionalIndicator(first)) {
        if (end < text.length && isRegionalIndicator(text.codePointAt(end))) end += Character.charCount(text.codePointAt(end))
        return text.substring(0, end)
    }
    while (end < text.length) {
        val cp = text.codePointAt(end)
        when {
            cp in 0x1F3FB..0x1F3FF || cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 || cp in 0xE0020..0xE007F ->
                end += Character.charCount(cp)
            cp == 0x200D && end + 1 < text.length -> {
                end += 1
                end += Character.charCount(text.codePointAt(end))
            }
            else -> break
        }
    }
    return text.substring(0, end)
}
