package com.martinhammer.tickdroid.ui.entry

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class NumberFieldTest {

    private val uk = Locale.forLanguageTag("en-GB")
    private val nlBe = Locale.forLanguageTag("nl-BE")

    private fun value(text: String, cursor: Int = text.length) = TextFieldValue(text, TextRange(cursor))

    @Test fun `typing keeps only digits, separators and minus signs`() {
        assertEquals("10", numberFieldChange(value("1"), value("10"), uk).text)
        assertEquals("1", numberFieldChange(value("1"), value("1a"), uk).text)
        assertEquals("-3,5", numberFieldChange(value("-3"), value("-3,5"), uk).text)
    }

    @Test fun `a paste lands ungrouped in the language's separator`() {
        assertEquals(value("1204.5"), numberFieldChange(value(""), value("1,204.5"), uk))
        assertEquals(value("1204,5"), numberFieldChange(value(""), value("1.204,5"), nlBe))
        // Unreadable: as it is, filtered.
        assertEquals("10,4", numberFieldChange(value(""), value("10,4"), uk).text)
        assertEquals("12", numberFieldChange(value(""), value("12 km"), uk).text)
    }

    @Test fun `at most 20 characters`() {
        assertEquals(20, numberFieldChange(value(""), value("1".repeat(30)), uk).text.length)
    }
}
