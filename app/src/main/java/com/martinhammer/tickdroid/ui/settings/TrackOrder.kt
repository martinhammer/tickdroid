package com.martinhammer.tickdroid.ui.settings

// Pure, Compose-free helpers for the reorderable Tracks settings list. JVM-tested.

/** This list with the element at [from] moved to [to]; the others shift to make room. */
internal fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * [rows] in the order of [pendingLocalIds] (the user's drag, not yet confirmed by the server).
 * Rows the pending list doesn't know (a pull added one mid-drag) go at the end; pending ids with
 * no row (deleted meanwhile) are dropped. Null means no pending order: [rows] as they are.
 */
internal fun applyOrder(rows: List<TrackRowState>, pendingLocalIds: List<Long>?): List<TrackRowState> {
    if (pendingLocalIds == null) return rows
    val byId = rows.associateBy { it.track.localId }
    val pending = pendingLocalIds.toSet()
    return pendingLocalIds.mapNotNull { byId[it] } + rows.filterNot { it.track.localId in pending }
}

internal fun canMoveUp(index: Int): Boolean = index > 0

internal fun canMoveDown(index: Int, size: Int): Boolean = index in 0 until size - 1
