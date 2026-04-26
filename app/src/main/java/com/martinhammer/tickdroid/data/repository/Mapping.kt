package com.martinhammer.tickdroid.data.repository

import com.martinhammer.tickdroid.data.local.TickEntity
import com.martinhammer.tickdroid.data.local.TrackEntity
import com.martinhammer.tickdroid.domain.Tick
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackType
import java.time.LocalDate

fun TrackEntity.toDomain(): Track = Track(
    localId = localId,
    serverId = serverId,
    name = name,
    type = TrackType.fromWire(type),
    sortOrder = sortOrder,
    private = private,
)

fun TickEntity.toDomain(): Tick = Tick(
    trackLocalId = trackLocalId,
    date = LocalDate.parse(date),
    value = value,
)
