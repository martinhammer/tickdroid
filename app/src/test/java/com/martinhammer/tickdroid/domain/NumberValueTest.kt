package com.martinhammer.tickdroid.domain

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalSerializationApi::class)
class NumberValueTest {

    private val uk = Locale.forLanguageTag("en-GB")
    private val nlBe = Locale.forLanguageTag("nl-BE")
    private val frBe = Locale.forLanguageTag("fr-BE")
    private val deCh = Locale.forLanguageTag("de-CH")

    private fun wire(text: String) = numberFromWire(JsonUnquotedLiteral(text))

    @Test fun `fromWire reads the number's own text into thousandths`() {
        assertEquals(10400, wire("10.4"))
        assertEquals(-1500, wire("-1.5"))
        assertEquals(0, wire("0"))
        assertEquals(72000, wire("72"))
        assertEquals(Int.MAX_VALUE, wire("2147483.647"))
        assertEquals(-Int.MAX_VALUE, wire("-2147483.647"))
    }

    @Test fun `fromWire refuses what the server never sends`() {
        assertNull(wire("1e3"))
        assertNull(numberFromWire(JsonPrimitive("x")))
        assertNull(numberFromWire(JsonNull))
        assertNull(wire("1.2345"))
        assertNull(wire("2147483.648"))
    }

    @Test fun `toWire has a point, no grouping, no exponent, no trailing zeros`() {
        assertEquals("10.4", numberToWire(10400))
        assertEquals("72", numberToWire(72000))
        assertEquals("-1.5", numberToWire(-1500))
        assertEquals("0", numberToWire(0))
        assertEquals("100", numberToWire(100000))
        assertEquals("0.001", numberToWire(1))
    }

    @Test fun `roundMilli rounds half away from zero`() {
        assertEquals(10500L, roundMilli(10450, 1))
        assertEquals(-10500L, roundMilli(-10450, 1))
        assertEquals(100L, roundMilli(50, 1))
        assertEquals(0L, roundMilli(49, 0))
        assertEquals(2147483650L, roundMilli(Int.MAX_VALUE, 2))
    }

    @Test fun `format follows the locale, with and without grouping`() {
        assertEquals("1,204.5", formatNumber(1204500, 1, uk, grouping = true))
        assertEquals("1204.5", formatNumber(1204500, 1, uk, grouping = false))
        assertEquals("1.204,5", formatNumber(1204500, 1, nlBe, grouping = true))
        assertEquals("1204,5", formatNumber(1204500, 1, nlBe, grouping = false))
        assertEquals("1204,5", formatNumber(1204500, 1, frBe, grouping = false))
        assertEquals("1204.5", formatNumber(1204500, 1, deCh, grouping = false))
        assertEquals("10.400", formatNumber(10400, 3, uk, grouping = false))
        assertEquals("10", formatNumber(10400, 0, uk, grouping = false))
    }

    @Test fun `a value that rounds to zero has no minus`() {
        assertEquals("0.0", formatNumber(-40, 1, uk, grouping = false))
        assertEquals("0", formatNumber(-400, 0, uk, grouping = false))
    }

    @Test fun `typing takes either separator in any language`() {
        assertEquals(Typed.Value(10400), parseTyped("10.4", 1, allowNegative = false))
        assertEquals(Typed.Value(10400), parseTyped("10,4", 1, allowNegative = false))
        assertEquals(Typed.Value(10500), parseTyped("10.45", 1, allowNegative = false))
        assertEquals(Typed.Value(-3000), parseTyped("−3", 1, allowNegative = true))
        assertEquals(Typed.Value(-3000), parseTyped(" -3 ", 1, allowNegative = true))
        assertEquals(Typed.Value(10000), parseTyped("9.5", 0, allowNegative = false))
    }

    @Test fun `typing refuses what can't be saved`() {
        assertEquals(Typed.Negative, parseTyped("-3", 1, allowNegative = false))
        listOf("1,204.5", ".5", "5.", "1e3", "abc", "1..2", "--1").forEach {
            assertEquals(it, Typed.NotANumber, parseTyped(it, 1, allowNegative = true))
        }
        assertEquals(Typed.Empty, parseTyped("", 1, allowNegative = false))
        assertEquals(Typed.Empty, parseTyped("  ", 1, allowNegative = false))
        assertEquals(Typed.TooLarge, parseTyped("2147483.6475", 3, allowNegative = false))
        assertEquals(Typed.TooLarge, parseTyped("2147483.648", 3, allowNegative = false))
        assertEquals(Typed.TooLarge, parseTyped("-2147483.648", 3, allowNegative = true))
        assertEquals(Typed.Value(Int.MAX_VALUE), parseTyped("2147483.647", 3, allowNegative = false))
    }

    @Test fun `a paste is read with the language's grouping rule`() {
        assertEquals("1204.5", ungroupPasted("1,204.5", uk))
        assertEquals("1204", ungroupPasted("1,204", uk))
        assertEquals("1204,5", ungroupPasted("1.204,5", nlBe))
        assertEquals("-1234567", ungroupPasted("-1,234,567", uk))
        // A point also counts as the decimal separator where it isn't the group separator.
        assertEquals("10,4", ungroupPasted("10.4", frBe))
        assertEquals("1204,5", ungroupPasted("1 204,5", frBe))
        // Not readable: lands as it is.
        assertNull(ungroupPasted("10,4", uk))
        assertNull(ungroupPasted("1,20,4", uk))
        assertNull(ungroupPasted("abc", uk))
        assertNull(ungroupPasted("5.", uk))
    }

    @Test fun `the limit message is in the phone's language`() {
        assertEquals("Numbers must be between -2,147,483.647 and 2,147,483.647", numberLimitMessage(uk))
    }

    // A stand-in for ICU's Extended_Pictographic, which the JVM doesn't have.
    private fun emoji(cp: Int) = cp >= 0x1F000 || cp in 0x2600..0x27BF

    @Test fun `shortLabel is the emoji or two letters in capitals`() {
        assertEquals("HA", shortLabel("Happy", ::emoji))
        assertEquals("😄", shortLabel("😄 Happy", ::emoji))
        assertEquals("👍🏽", shortLabel("👍🏽 Good", ::emoji))
        assertEquals("👨‍👩‍👧", shortLabel("👨‍👩‍👧 Family", ::emoji))
        assertEquals("🇧🇪", shortLabel("🇧🇪 Home", ::emoji))
        assertEquals("A", shortLabel("a", ::emoji))
        assertEquals("", shortLabel("  ", ::emoji))
    }
}
