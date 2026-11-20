package com.martinhammer.tickdroid.data.repository

import com.martinhammer.tickdroid.data.local.TickEntity
import com.martinhammer.tickdroid.data.local.TrackEntity
import com.martinhammer.tickdroid.data.local.TrackOptionEntity
import com.martinhammer.tickdroid.domain.Tick
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackType
import java.time.LocalDate

/** [options] are a Choice track's labels in place order (from `track_options`). */
fun TrackEntity.toDomain(options: List<String> = emptyList()): Track = Track(
    localId = localId,
    serverId = serverId,
    name = name,
    type = TrackType.fromWire(type),
    sortOrder = sortOrder,
    private = private,
    archivedAt = archivedAt?.let(LocalDate::parse),
    unit = unit,
    decimalPlaces = decimalPlaces,
    allowNegative = allowNegative,
    options = options,
    orderedScale = orderedScale,
    awaitingSync = awaitingSync,
)

/** Labels by track local id, each in place order. */
internal fun List<TrackOptionEntity>.labelsByTrack(): Map<Long, List<String>> =
    groupBy { it.trackLocalId }.mapValues { (_, rows) -> rows.sortedBy { it.place }.map { it.label } }

fun TickEntity.toDomain(): Tick = Tick(
    trackLocalId = trackLocalId,
    date = LocalDate.parse(date),
    value = value,
)
