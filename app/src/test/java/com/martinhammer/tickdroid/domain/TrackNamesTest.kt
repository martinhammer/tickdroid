package com.martinhammer.tickdroid.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackNamesTest {

    @Test fun `blank and whitespace-only names are rejected`() {
        assertNull(cleanTrackName(""))
        assertNull(cleanTrackName("   \t "))
    }

    @Test fun `surrounding spaces are trimmed`() {
        assertEquals("Water", cleanTrackName("  Water \n"))
    }

    @Test fun `255 code points pass and 256 fail`() {
        assertEquals("a".repeat(255), cleanTrackName("a".repeat(255)))
        assertNull(cleanTrackName("a".repeat(256)))
    }

    @Test fun `an emoji counts once`() {
        val emojis = "💧".repeat(255) // 255 code points, 510 UTF-16 units
        assertEquals(emojis, cleanTrackName(emojis))
        assertNull(cleanTrackName(emojis + "💧"))
    }

    @Test fun `cap cuts a long paste to 255 code points`() {
        assertEquals("a".repeat(255), capTrackName("a".repeat(300)))
        assertEquals("short", capTrackName("short"))
    }

    @Test fun `cap never splits a surrogate pair`() {
        val capped = capTrackName("a" + "💧".repeat(299))
        assertEquals(255, capped.codePointCount(0, capped.length))
        assertEquals(1 + 254 * 2, capped.length)
        assertEquals(false, Character.isHighSurrogate(capped.last()))
    }
}
