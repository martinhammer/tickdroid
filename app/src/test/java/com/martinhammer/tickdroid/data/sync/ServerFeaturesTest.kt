package com.martinhammer.tickdroid.data.sync

import com.martinhammer.tickdroid.data.remote.dto.TickbuddyCapability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerFeaturesTest {

    @Test fun `archivedTracks true only when the flag is true`() {
        assertTrue(archivedTracksFlag(TickbuddyCapability(features = mapOf("archivedTracks" to true))))
    }

    @Test fun `a missing capability, a missing flag or false mean false`() {
        assertFalse(archivedTracksFlag(null))
        assertFalse(archivedTracksFlag(TickbuddyCapability(version = "1.2.0")))
        assertFalse(archivedTracksFlag(TickbuddyCapability(features = mapOf("other" to true))))
        assertFalse(archivedTracksFlag(TickbuddyCapability(features = mapOf("archivedTracks" to false))))
    }
}
