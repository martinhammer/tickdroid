package com.martinhammer.tickdroid.ui.journal

import com.martinhammer.tickdroid.data.sync.PushStatus
import com.martinhammer.tickdroid.data.sync.SyncErrorKind
import com.martinhammer.tickdroid.data.sync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncIssueTest {

    @Test fun `None has no label`() {
        assertNull(SyncIssue.None.toLabel())
    }

    @Test fun `chip labels reflect kind and dirty state`() {
        assertEquals("Offline", SyncIssue.Offline(hasUnsavedChanges = false).toLabel())
        assertEquals("Offline, unsaved changes", SyncIssue.Offline(hasUnsavedChanges = true).toLabel())
        assertEquals("Server unreachable", SyncIssue.ServerUnreachable(false).toLabel())
        assertEquals("Server unreachable, unsaved changes", SyncIssue.ServerUnreachable(true).toLabel())
        assertEquals("Sync error", SyncIssue.ServerError(false).toLabel())
        assertEquals("Sync error, unsaved changes", SyncIssue.ServerError(true).toLabel())
    }


    @Test fun `offline outranks healthy sync, regardless of dirty`() {
        assertEquals(
            SyncIssue.Offline(hasUnsavedChanges = false),
            computeSyncIssue(isOnline = false, pull = SyncStatus.Idle, push = PushStatus.Idle, hasUnsaved = false),
        )
        assertEquals(
            SyncIssue.Offline(hasUnsavedChanges = true),
            computeSyncIssue(isOnline = false, pull = SyncStatus.Idle, push = PushStatus.Idle, hasUnsaved = true),
        )
    }

    @Test fun `offline outranks pull error`() {
        val issue = computeSyncIssue(
            isOnline = false,
            pull = SyncStatus.Error(SyncErrorKind.ServerError, "boom"),
            push = PushStatus.Idle,
            hasUnsaved = true,
        )
        assertEquals(SyncIssue.Offline(hasUnsavedChanges = true), issue)
    }

    @Test fun `online and idle is None`() {
        assertEquals(
            SyncIssue.None,
            computeSyncIssue(true, SyncStatus.Idle, PushStatus.Idle, hasUnsaved = false),
        )
    }

    @Test fun `online with pull ServerUnreachable maps to ServerUnreachable`() {
        assertEquals(
            SyncIssue.ServerUnreachable(hasUnsavedChanges = false),
            computeSyncIssue(true, SyncStatus.Error(SyncErrorKind.ServerUnreachable), PushStatus.Idle, false),
        )
    }

    @Test fun `online with pull ServerError maps to ServerError`() {
        assertEquals(
            SyncIssue.ServerError(hasUnsavedChanges = true),
            computeSyncIssue(true, SyncStatus.Error(SyncErrorKind.ServerError), PushStatus.Idle, true),
        )
    }

    @Test fun `online with push error maps when pull is healthy`() {
        assertEquals(
            SyncIssue.ServerError(hasUnsavedChanges = true),
            computeSyncIssue(true, SyncStatus.Idle, PushStatus.Error(SyncErrorKind.ServerError), true),
        )
    }

    @Test fun `pull error wins over push error`() {
        // Pull failure is the more authoritative "we couldn't read state at all" signal.
        assertEquals(
            SyncIssue.ServerUnreachable(hasUnsavedChanges = false),
            computeSyncIssue(
                true,
                SyncStatus.Error(SyncErrorKind.ServerUnreachable),
                PushStatus.Error(SyncErrorKind.ServerError),
                false,
            ),
        )
    }

    @Test fun `Syncing in progress without error stays None`() {
        assertEquals(
            SyncIssue.None,
            computeSyncIssue(true, SyncStatus.Syncing, PushStatus.Pushing, hasUnsaved = true),
        )
    }
}
