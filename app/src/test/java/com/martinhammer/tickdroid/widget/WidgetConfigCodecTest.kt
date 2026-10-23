package com.martinhammer.tickdroid.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetConfigCodecTest {

    @Test fun `button round-trips`() {
        val config = WidgetConfig.Button(trackServerId = 42)
        assertEquals(config, WidgetConfigCodec.decode(WidgetConfigCodec.encode(config)))
    }

    @Test fun `selection round-trips`() {
        val config = WidgetConfig.TrackSelection(shown = mapOf(1L to true, 2L to false), labels = TrackLabels.Short)
        assertEquals(config, WidgetConfigCodec.decode(WidgetConfigCodec.encode(config)))
    }

    @Test fun `a background round-trips for both kinds`() {
        val button = WidgetConfig.Button(trackServerId = 42, background = 50)
        assertEquals(button, WidgetConfigCodec.decode(WidgetConfigCodec.encode(button)))
        val selection = WidgetConfig.TrackSelection(shown = mapOf(1L to true), background = 50)
        assertEquals(selection, WidgetConfigCodec.decode(WidgetConfigCodec.encode(selection)))
    }

    @Test fun `an entry without labels reads as Full, and without a background as Solid`() {
        val decoded = WidgetConfigCodec.decode("""{"type":"selection","shown":{"7":false}}""")
        assertEquals(
            WidgetConfig.TrackSelection(shown = mapOf(7L to false), labels = TrackLabels.Full, background = 100),
            decoded,
        )
        assertEquals(100, WidgetConfigCodec.decode("""{"type":"button","trackServerId":5}""")?.background)
    }

    @Test fun `unknown fields are ignored`() {
        val decoded = WidgetConfigCodec.decode("""{"type":"button","trackServerId":5,"future":"x"}""")
        assertEquals(WidgetConfig.Button(5), decoded)
    }

    @Test fun `garbage and missing entries read as null`() {
        assertNull(WidgetConfigCodec.decode(null))
        assertNull(WidgetConfigCodec.decode("not json"))
        assertNull(WidgetConfigCodec.decode("""{"type":"mystery"}"""))
    }
}
