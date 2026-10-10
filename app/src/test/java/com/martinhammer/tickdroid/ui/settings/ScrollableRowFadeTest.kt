package com.martinhammer.tickdroid.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollableRowFadeTest {

    // 48dp circles, 12dp gaps, a 360dp viewport, a 32dp fade (at 1px per dp).
    private fun scroll(selected: Int) = openingScrollPx(selected, 48f, 12f, 360f, 32f)

    @Test fun `first item never scrolls`() {
        assertEquals(0f, scroll(0))
    }

    @Test fun `an item already clear of the fade doesn't scroll`() {
        // Item 4 ends at 4 * 60 + 48 = 288, inside 360 - 32 = 328.
        assertEquals(0f, scroll(4))
    }

    @Test fun `an item under the fade scrolls just enough`() {
        // Item 5 ends at 348: 20px into the fade.
        assertEquals(20f, scroll(5))
        // Item 10 ends at 648.
        assertEquals(320f, scroll(10))
    }
}
