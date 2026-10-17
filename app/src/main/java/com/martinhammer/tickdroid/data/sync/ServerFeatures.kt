package com.martinhammer.tickdroid.data.sync

import android.content.Context
import android.content.SharedPreferences
import com.martinhammer.tickdroid.data.remote.dto.TickbuddyCapability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the signed-in server's Tickbuddy offers, as last seen. Refreshed at the start of every
 * pull ([SyncManager.pull], inside the sync lock and before the track read, so the read always
 * matches the flag), and by the Account screen's own fetch. Own SharedPreferences file; cleared
 * on sign-out, because the next account may be on another server.
 *
 * Later feature flags belong here too: one [update] reads every flag the app knows, with one pure
 * `…Flag(capability)` helper and one `StateFlow` per flag, each defaulting to false. Don't add a
 * second capabilities call or a second cache.
 */
@Singleton
class ServerFeatures @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("tickdroid_server_prefs", Context.MODE_PRIVATE)

    private val _archivedTracks = MutableStateFlow(prefs.getBoolean(KEY_ARCHIVED_TRACKS, false))

    /** Tickbuddy 5a+: archive and restore exist, and `GET /api/tracks` hides archived tracks unless asked. */
    val archivedTracks: StateFlow<Boolean> = _archivedTracks.asStateFlow()

    /** Stores what [capability] says. A missing capability (or flag) means false. */
    fun update(capability: TickbuddyCapability?) {
        val archived = archivedTracksFlag(capability)
        if (archived != _archivedTracks.value) {
            prefs.edit().putBoolean(KEY_ARCHIVED_TRACKS, archived).apply()
            _archivedTracks.value = archived
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
        _archivedTracks.value = false
    }

    private companion object {
        const val KEY_ARCHIVED_TRACKS = "archived_tracks"
    }
}

/** A missing capability or flag means false. */
internal fun archivedTracksFlag(capability: TickbuddyCapability?): Boolean =
    capability?.features?.get("archivedTracks") == true
