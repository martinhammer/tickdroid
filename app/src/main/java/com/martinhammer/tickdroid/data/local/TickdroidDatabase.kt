package com.martinhammer.tickdroid.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, TickEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TickdroidDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun tickDao(): TickDao
}
