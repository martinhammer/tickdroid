package com.martinhammer.tickdroid.ui.settings

import com.martinhammer.tickdroid.domain.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewTrackFormTest {

    @Test fun `the second of two equal labels is the duplicate, ignoring case and spaces`() {
        assertEquals(setOf(2), duplicateOptionIndexes(listOf("Happy", "Sad", " happy ")))
        assertEquals(emptySet<Int>(), duplicateOptionIndexes(listOf("Happy", "", "")))
    }

    @Test fun `Add track needs a name and, for a Choice, distinct non-blank options`() {
        assertFalse(canAddTrack(" ", TrackType.YesNo, emptyList()))
        assertTrue(canAddTrack("Run", TrackType.Number, emptyList()))
        assertFalse(canAddTrack("Mood", TrackType.Choice, listOf("Happy", "")))
        assertFalse(canAddTrack("Mood", TrackType.Choice, listOf("Happy", "HAPPY")))
        assertFalse(canAddTrack("Mood", TrackType.Choice, listOf("Happy")))
        assertTrue(canAddTrack("Mood", TrackType.Choice, listOf("Happy", "Sad")))
        // Another type's leftover options don't matter.
        assertTrue(canAddTrack("Steps", TrackType.Counter, listOf("", "")))
    }
}
