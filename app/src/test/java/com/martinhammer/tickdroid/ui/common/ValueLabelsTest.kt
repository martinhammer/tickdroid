package com.martinhammer.tickdroid.ui.common

import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ValueLabelsTest {

    private val uk = Locale.forLanguageTag("en-GB")
    private val run = Track(1, 1, "Run", TrackType.Number, 0, false, unit = "km", decimalPlaces = 1)
    private val steps = Track(2, 2, "Steps", TrackType.Number, 0, false, unit = "", decimalPlaces = 0)
    private val mood = Track(3, 3, "Mood", TrackType.Choice, 0, false, options = listOf("Happy", "Sad"))

    @Test fun `T24 descriptions carry the full value, unit and label`() {
        assertEquals("Run, 10.4 km", valueDescription(run, 10400, uk))
        assertEquals("Steps, 12,840", valueDescription(steps, 12840000, uk))
        assertEquals("Run, no value", valueDescription(run, null, uk))
        assertEquals("Mood, Happy", valueDescription(mood, 1, uk))
        assertEquals("Mood, #5", valueDescription(mood, 5, uk))
        assertEquals("Option 2, Happy", optionDescription(2, "Happy"))
    }

    @Test fun `the new toasts`() {
        assertEquals("Run can be changed after the next sync.", awaitingSyncToast("Run"))
        assertEquals("Run: not a valid number, so the change wasn't saved.", notANumberToast("Run"))
        assertEquals("Run doesn't allow negative values, so the change wasn't saved.", negativeNotAllowedToast("Run"))
    }
}
