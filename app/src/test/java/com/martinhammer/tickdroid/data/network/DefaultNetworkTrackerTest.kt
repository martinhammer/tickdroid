package com.martinhammer.tickdroid.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultNetworkTrackerTest {

    private val tracker = DefaultNetworkTracker<String>()

    @Test fun `losing the default network goes offline`() {
        assertEquals(true, tracker.onCapabilities("A", hasInternet = true))
        assertEquals(false, tracker.onLost("A"))
    }

    @Test fun `switch with the new default first stays online`() {
        tracker.onCapabilities("A", hasInternet = true)
        assertNull(tracker.onAvailable("B"))
        assertEquals(true, tracker.onCapabilities("B", hasInternet = true))
        assertNull(tracker.onLost("A"))
    }

    @Test fun `switch with the old default lost first goes offline then online`() {
        tracker.onCapabilities("A", hasInternet = true)
        assertEquals(false, tracker.onLost("A"))
        assertNull(tracker.onAvailable("B"))
        assertEquals(true, tracker.onCapabilities("B", hasInternet = true))
    }

    @Test fun `a late onLost for an old default changes nothing`() {
        tracker.onCapabilities("A", hasInternet = true)
        tracker.onCapabilities("B", hasInternet = true)
        assertNull(tracker.onLost("A"))
        assertEquals(false, tracker.onLost("B"))
    }

    @Test fun `capabilities without internet are offline, and coming back is online`() {
        assertEquals(false, tracker.onCapabilities("A", hasInternet = false))
        assertEquals(true, tracker.onCapabilities("A", hasInternet = true))
    }

    @Test fun `onAvailable alone emits nothing`() {
        assertNull(tracker.onAvailable("A"))
    }
}
