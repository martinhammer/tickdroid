package com.martinhammer.tickdroid.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackTypeTest {

    @Test fun `known wire values give the known types`() {
        assertEquals(TrackType.YesNo, TrackType.fromWire("boolean"))
        assertEquals(TrackType.Counter, TrackType.fromWire("counter"))
    }

    @Test fun `anything else is Unknown with that value, never an exception`() {
        assertEquals(TrackType.Unknown("future"), TrackType.fromWire("future"))
        assertEquals(TrackType.Unknown(""), TrackType.fromWire(""))
        // Matching is exact, as the server sends it.
        assertEquals(TrackType.Unknown("Counter"), TrackType.fromWire("Counter"))
    }

    @Test fun `wireValue round-trips`() {
        listOf("boolean", "counter", "future", "").forEach {
            assertEquals(it, TrackType.fromWire(it).wireValue)
        }
    }

    @Test fun `number and choice map to their types, matching exactly`() {
        assertEquals(TrackType.Number, TrackType.fromWire("number"))
        assertEquals(TrackType.Choice, TrackType.fromWire("choice"))
        assertEquals(TrackType.Unknown("Number"), TrackType.fromWire("Number"))
    }
}
