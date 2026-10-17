package com.martinhammer.tickdroid.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** "About tracks", opened from "?" on both the Tracks settings list and the track screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TracksHelpSheet(archiveSupported: Boolean, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("About tracks", style = MaterialTheme.typography.headlineSmall)
            tracksHelpParagraphs(archiveSupported).forEach { paragraph ->
                Spacer(Modifier.height(12.dp))
                Text(paragraph, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The sheet's paragraphs. The archive one only when the server can archive. */
internal fun tracksHelpParagraphs(archiveSupported: Boolean): List<String> = listOfNotNull(
    "Name, type, private status and track order are synced to Tickbuddy on your Nextcloud server.",
    "Colour and icon are set on this phone only.",
    "The icon is shown next to or instead of the track name, depending on the space available.",
    "Private tracks are hidden from the journal unless “Show private tracks” is on in " +
        "App settings, and never shown on home screen widgets.",
    if (archiveSupported) {
        "Archived tracks are no longer tracked or shown in the journal and widgets, but their " +
            "history is kept and they can be restored."
    } else {
        null
    },
    // The same sentence with or without archive support (decided 15 Oct).
    "The following actions are not available while you're offline: add, rename, toggle " +
        "private, reorder, archive, restore and delete tracks.",
)
