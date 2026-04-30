package com.martinhammer.tickdroid.data.time

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Tiny seam over the wall clock so tests can pin "today". */
interface Clock {
    fun today(): LocalDate
    fun nowMillis(): Long
}

@Singleton
class SystemClock @Inject constructor() : Clock {
    override fun today(): LocalDate = LocalDate.now()
    override fun nowMillis(): Long = System.currentTimeMillis()
}
