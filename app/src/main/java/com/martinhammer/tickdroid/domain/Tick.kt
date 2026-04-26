package com.martinhammer.tickdroid.domain

import java.time.LocalDate

data class Tick(
    val trackLocalId: Long,
    val date: LocalDate,
    val value: Int,
)
