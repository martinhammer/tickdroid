package com.martinhammer.tickdroid.ui.journal

import com.martinhammer.tickdroid.data.sync.PushStatus
import com.martinhammer.tickdroid.data.sync.SyncErrorKind
import com.martinhammer.tickdroid.data.sync.SyncStatus

/** What (if anything) is wrong with sync right now. The chip in the journal top bar reads this. */
sealed interface SyncIssue {
    data object None : SyncIssue
    data class Offline(val hasUnsavedChanges: Boolean) : SyncIssue
    data class ServerUnreachable(val hasUnsavedChanges: Boolean) : SyncIssue
    data class ServerError(val hasUnsavedChanges: Boolean) : SyncIssue
}

/** Human-readable chip label for the journal top bar, or null when the chip should hide. */
fun SyncIssue.toLabel(): String? {
    val (category, hasUnsaved) = when (this) {
        SyncIssue.None -> return null
        is SyncIssue.Offline -> "Offline" to hasUnsavedChanges
        is SyncIssue.ServerUnreachable -> "Server unreachable" to hasUnsavedChanges
        is SyncIssue.ServerError -> "Sync error" to hasUnsavedChanges
    }
    return if (hasUnsaved) "$category, unsaved changes" else category
}

internal fun computeSyncIssue(
    isOnline: Boolean,
    pull: SyncStatus,
    push: PushStatus,
    hasUnsaved: Boolean,
): SyncIssue {
    if (!isOnline) return SyncIssue.Offline(hasUnsaved)
    val kind = (pull as? SyncStatus.Error)?.kind ?: (push as? PushStatus.Error)?.kind
    return when (kind) {
        null -> SyncIssue.None
        SyncErrorKind.ServerUnreachable -> SyncIssue.ServerUnreachable(hasUnsaved)
        SyncErrorKind.ServerError -> SyncIssue.ServerError(hasUnsaved)
    }
}
