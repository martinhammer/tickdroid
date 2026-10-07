package com.martinhammer.tickdroid.widget

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** What one placed widget shows. Keyed by `appWidgetId` in [WidgetConfigStore]. */
@Serializable
sealed interface WidgetConfig {
    /** Single track: one track, by server id (stable across pulls; local ids are not). */
    @Serializable @SerialName("button")
    data class Button(val trackServerId: Long) : WidgetConfig

    /** Today list and Today row: explicit per-track choices. Tracks not in the map are shown. */
    @Serializable @SerialName("selection")
    data class TrackSelection(
        val shown: Map<Long, Boolean> = emptyMap(),
        /** Today list only; the row always shows emoji or abbreviations. */
        val labels: TrackLabels = TrackLabels.Full,
    ) : WidgetConfig
}

/** How the Today list labels its rows: emoji + name, or emoji / abbreviation only. */
@Serializable
enum class TrackLabels { Full, Short }

/** JSON form of [WidgetConfig]. Lenient on read so a later version can add fields. */
internal object WidgetConfigCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(config: WidgetConfig): String = json.encodeToString(WidgetConfig.serializer(), config)

    /** null for anything missing or unreadable, so a corrupt entry reads as "not set up". */
    fun decode(raw: String?): WidgetConfig? =
        raw?.let { runCatching { json.decodeFromString(WidgetConfig.serializer(), it) }.getOrNull() }
}

/**
 * Per-widget choices, in their own SharedPreferences file so [com.martinhammer.tickdroid.data.prefs.UiPreferences.clear]
 * can't touch them by accident and sign-out clears them on purpose. Not in Room: this is per-device
 * UI state, and a table would cost a schema bump and a migration.
 */
@Singleton
class WidgetConfigStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _revision = MutableStateFlow(0)

    /** Bumped on every write, so widgets can redraw when a choice changes. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun get(appWidgetId: Int): WidgetConfig? = WidgetConfigCodec.decode(prefs.getString(key(appWidgetId), null))

    fun put(appWidgetId: Int, config: WidgetConfig) {
        prefs.edit().putString(key(appWidgetId), WidgetConfigCodec.encode(config)).apply()
        _revision.update { it + 1 }
    }

    fun remove(appWidgetIds: IntArray) {
        prefs.edit().apply { appWidgetIds.forEach { remove(key(it)) } }.apply()
        _revision.update { it + 1 }
    }

    /** Forget every widget's choices. Called on sign-out. */
    fun clear() {
        prefs.edit().clear().apply()
        _revision.update { it + 1 }
    }

    private fun key(appWidgetId: Int) = "widget_$appWidgetId"

    private companion object {
        const val PREFS_NAME = "tickdroid_widget_prefs"
    }
}
