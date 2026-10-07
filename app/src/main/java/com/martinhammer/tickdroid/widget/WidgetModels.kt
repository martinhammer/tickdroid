package com.martinhammer.tickdroid.widget

import com.martinhammer.tickdroid.data.repository.TickKey
import com.martinhammer.tickdroid.domain.Tick
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackType
import java.time.LocalDate

// Pure, Compose-free models and layout maths for the home-screen widgets. JVM-tested
// (WidgetModelsTest); the Glance code only draws what these return.

/** One track as a widget shows it: today's value plus the user's emoji / colour overrides. */
internal data class WidgetRow(
    val serverId: Long,
    val name: String,
    val type: TrackType,
    val value: Int,
    val emoji: String?,
    val colorKey: String?,
) {
    /** Drives the cell colour: ticked yes/no, or a counter above 0. */
    val done: Boolean get() = value > 0
}

/** Today list and Today row. */
internal sealed interface ListModel {
    data object SignedOut : ListModel
    data class Content(
        val day: LocalDate,
        val rows: List<WidgetRow>,
        val labels: TrackLabels,
    ) : ListModel
}

/** Single track. */
internal sealed interface ButtonModel {
    data object SignedOut : ButtonModel
    /** No track chosen yet, or the chosen one was deleted or made private. Tapping opens setup. */
    data object ChooseTrack : ButtonModel
    data class Content(val day: LocalDate, val row: WidgetRow) : ButtonModel
}

/** What the sync slot needs: whether a sync is running, and whether unsent ticks are stuck. */
internal data class SyncBadge(
    val syncing: Boolean = false,
    val offlinePending: Boolean = false,
)

/** The list header's and the Today row's sync slot, in priority order. */
internal enum class SyncSlot { Syncing, Offline, Refresh }

internal val SyncBadge.slot: SyncSlot
    get() = when {
        syncing -> SyncSlot.Syncing
        offlinePending -> SyncSlot.Offline
        else -> SyncSlot.Refresh
    }

/**
 * Everything every widget draws from, as one value so equal data can be dropped before a redraw.
 * [ticks] covers [today] only. [configRevision] tracks [WidgetConfigStore] writes.
 */
internal data class WidgetInputs(
    val signedIn: Boolean,
    val today: LocalDate,
    val tracks: List<Track>,
    val prefs: Map<Long, TrackPrefs>,
    val ticks: Map<TickKey, Tick>,
    val badge: SyncBadge,
    val configRevision: Int,
)

/** Private tracks never show on a widget, whatever "Show private tracks" says. */
internal fun WidgetConfig.TrackSelection.isShown(track: Track): Boolean {
    val serverId = track.serverId ?: return false
    return !track.private && (shown[serverId] ?: true)
}

internal fun buildListModel(
    signedIn: Boolean,
    today: LocalDate,
    tracks: List<Track>,
    prefs: Map<Long, TrackPrefs>,
    ticks: Map<TickKey, Tick>,
    config: WidgetConfig.TrackSelection?,
): ListModel {
    if (!signedIn) return ListModel.SignedOut
    val selection = config ?: WidgetConfig.TrackSelection()
    // observeTracks() is already in journal order.
    val rows = tracks.filter { selection.isShown(it) }.map { it.toWidgetRow(today, prefs, ticks) }
    return ListModel.Content(today, rows, selection.labels)
}

internal fun buildButtonModel(
    signedIn: Boolean,
    today: LocalDate,
    tracks: List<Track>,
    prefs: Map<Long, TrackPrefs>,
    ticks: Map<TickKey, Tick>,
    config: WidgetConfig.Button?,
): ButtonModel {
    if (!signedIn) return ButtonModel.SignedOut
    val serverId = config?.trackServerId ?: return ButtonModel.ChooseTrack
    val track = tracks.firstOrNull { it.serverId == serverId && !it.private }
        ?: return ButtonModel.ChooseTrack
    return ButtonModel.Content(today, track.toWidgetRow(today, prefs, ticks))
}

internal fun WidgetInputs.listModel(config: WidgetConfig?): ListModel =
    buildListModel(signedIn, today, tracks, prefs, ticks, config as? WidgetConfig.TrackSelection)

internal fun WidgetInputs.buttonModel(config: WidgetConfig?): ButtonModel =
    buildButtonModel(signedIn, today, tracks, prefs, ticks, config as? WidgetConfig.Button)

private fun Track.toWidgetRow(
    today: LocalDate,
    prefs: Map<Long, TrackPrefs>,
    ticks: Map<TickKey, Tick>,
): WidgetRow {
    val serverId = requireNotNull(serverId) { "widget rows need a server id" }
    val trackPrefs = prefs[serverId]
    return WidgetRow(
        serverId = serverId,
        name = name,
        type = type,
        value = ticks[TickKey(localId, today)]?.value ?: 0,
        emoji = trackPrefs?.emoji,
        colorKey = trackPrefs?.colorKey,
    )
}

/** The two-letter label used wherever there's no emoji and no room for the name. */
internal fun abbreviation(name: String): String = name.take(2).uppercase()

/** TalkBack text for a cell. The list adds a hint, since there the whole row is the button. */
internal fun cellDescription(row: WidgetRow, tapHint: Boolean = false): String {
    val state = when {
        row.type == TrackType.COUNTER -> row.value.toString()
        row.done -> "done"
        else -> "not done"
    }
    val hint = if (tapHint && row.type == TrackType.COUNTER) ", tap to add one" else ""
    return "${row.name}, $state$hint"
}

/** Which parts of the Today list header fit at [widthDp]. */
internal data class HeaderParts(
    val showTitle: Boolean,
    val date: HeaderDate,
    val iconTargetDp: Int,
)

internal enum class HeaderDate { Long, Short, None }

// "Tickdroid" + a long date ("Sun, Oct 5") + two 40dp icon targets + padding needs ~250dp;
// any narrower and the title would ellipsize, so it goes first.
internal const val HeaderFullMinWidthDp = 250f
internal const val HeaderDateMinWidthDp = 150f

internal fun headerParts(widthDp: Float): HeaderParts = when {
    widthDp >= HeaderFullMinWidthDp -> HeaderParts(showTitle = true, date = HeaderDate.Long, iconTargetDp = 40)
    widthDp >= HeaderDateMinWidthDp -> HeaderParts(showTitle = false, date = HeaderDate.Short, iconTargetDp = 32)
    else -> HeaderParts(showTitle = false, date = HeaderDate.None, iconTargetDp = 32)
}

// Today row: 8dp padding each side, two 32dp control targets, 40dp per track column. The
// offline symbol takes the refresh icon's place, so nothing extra is reserved for it and no
// track ever disappears when it shows.
internal const val RowPaddingDp = 16
internal const val RowControlsDp = 2 * 32
internal const val RowColumnDp = 40

/** How many track columns fit beside the controls in a Today row [widthDp] wide. */
internal fun rowCapacity(widthDp: Float): Int =
    ((widthDp - RowPaddingDp - RowControlsDp) / RowColumnDp).toInt().coerceAtLeast(0)

/** Cell and label sizes for a Today row [heightDp] tall. */
internal data class RowMetrics(val cellDp: Int, val labelDp: Int, val gapDp: Int)

internal fun rowMetrics(heightDp: Float): RowMetrics =
    // Label 24 + gap 4 + cell 36 = 64dp; some launchers' one-row height is less than that.
    if (heightDp >= 64f) RowMetrics(cellDp = 36, labelDp = 24, gapDp = 4)
    else RowMetrics(cellDp = 32, labelDp = 20, gapDp = 2)
