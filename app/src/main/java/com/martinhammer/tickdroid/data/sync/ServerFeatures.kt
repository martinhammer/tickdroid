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

    private val _booleanSet = MutableStateFlow(prefs.getBoolean(KEY_BOOLEAN_SET, false))

    /** Tickbuddy 2.3.0+: `POST /api/ticks/set` takes a yes/no day's state. */
    val booleanSet: StateFlow<Boolean> = _booleanSet.asStateFlow()

    private val _counterIncrement = MutableStateFlow(prefs.getBoolean(KEY_COUNTER_INCREMENT, false))

    /** Tickbuddy 2.3.0+: `POST /api/ticks/increment` exists. Only used together with [idempotencyKey]. */
    val counterIncrement: StateFlow<Boolean> = _counterIncrement.asStateFlow()

    private val _idempotencyKey = MutableStateFlow(prefs.getBoolean(KEY_IDEMPOTENCY_KEY, false))

    /** Tickbuddy 2.3.0+: a write repeated with the same `Idempotency-Key` is answered, not redone. */
    val idempotencyKey: StateFlow<Boolean> = _idempotencyKey.asStateFlow()

    /** Stores what [capability] says. A missing capability (or flag) means false. */
    fun update(capability: TickbuddyCapability?) {
        store(_archivedTracks, KEY_ARCHIVED_TRACKS, archivedTracksFlag(capability))
        store(_booleanSet, KEY_BOOLEAN_SET, booleanSetFlag(capability))
        store(_counterIncrement, KEY_COUNTER_INCREMENT, counterIncrementFlag(capability))
        store(_idempotencyKey, KEY_IDEMPOTENCY_KEY, idempotencyKeyFlag(capability))
    }

    private fun store(flow: MutableStateFlow<Boolean>, key: String, value: Boolean) {
        if (value != flow.value) {
            prefs.edit().putBoolean(key, value).apply()
            flow.value = value
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
        _archivedTracks.value = false
        _booleanSet.value = false
        _counterIncrement.value = false
        _idempotencyKey.value = false
    }

    private companion object {
        const val KEY_ARCHIVED_TRACKS = "archived_tracks"
        const val KEY_BOOLEAN_SET = "boolean_set"
        const val KEY_COUNTER_INCREMENT = "counter_increment"
        const val KEY_IDEMPOTENCY_KEY = "idempotency_key"
    }
}

/** A missing capability or flag means false. */
internal fun archivedTracksFlag(capability: TickbuddyCapability?): Boolean =
    capability?.features?.get("archivedTracks") == true

internal fun booleanSetFlag(capability: TickbuddyCapability?): Boolean =
    capability?.features?.get("booleanSet") == true

internal fun counterIncrementFlag(capability: TickbuddyCapability?): Boolean =
    capability?.features?.get("counterIncrement") == true

internal fun idempotencyKeyFlag(capability: TickbuddyCapability?): Boolean =
    capability?.features?.get("idempotencyKey") == true
