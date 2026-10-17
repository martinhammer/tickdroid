package com.martinhammer.tickdroid.ui.settings

import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Collator
import java.time.LocalDate
import java.util.Locale

class ArchivedTracksTest {

    private val collator = Collator.getInstance(Locale.UK).apply { strength = Collator.PRIMARY }

    private fun row(serverId: Long, name: String) = TrackRowState(
        Track(serverId, serverId, name, TrackType.BOOLEAN, sortOrder = 0, private = false, archivedAt = LocalDate.of(2026, 8, 14)),
        TrackPrefs(),
    )

    private fun List<TrackRowState>.ids() = map { it.track.serverId }

    @Test fun `sorts case-insensitively`() {
        assertEquals(listOf(2L, 1L), sortArchived(listOf(row(1, "Banana"), row(2, "apple")), collator).ids())
    }

    @Test fun `sorts accent-insensitively and breaks ties by server id`() {
        val rows = listOf(row(3, "Éclair"), row(1, "eclair"), row(2, "Date"))
        assertEquals(listOf(2L, 1L, 3L), sortArchived(rows, collator).ids())
    }

    @Test fun `archived subtitle for both types`() {
        assertEquals("Yes/No · Archived 14 Aug 2026", archivedSubtitle(TrackType.BOOLEAN, "14 Aug 2026"))
        assertEquals("Counter · Archived Aug 14, 2026", archivedSubtitle(TrackType.COUNTER, "Aug 14, 2026"))
    }
}
