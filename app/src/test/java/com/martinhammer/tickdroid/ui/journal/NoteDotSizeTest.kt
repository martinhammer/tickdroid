package com.martinhammer.tickdroid.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteDotSizeTest {

    @Test fun `4dp in cells under 32dp, else 6dp`() {
        assertEquals(4f, noteDotSizeDp(28f), 0f)
        assertEquals(4f, noteDotSizeDp(31.9f), 0f)
        assertEquals(6f, noteDotSizeDp(32f), 0f)
        assertEquals(6f, noteDotSizeDp(64f), 0f)
    }

    @Test fun `High density's minimum cell gets the small dot, Today's 48dp cell the full one`() {
        assertEquals(4f, noteDotSizeDp(MinCellSizeDp), 0f)
        assertEquals(6f, noteDotSizeDp(48f), 0f)
    }
}
