package com.martinhammer.tickdroid.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, TickEntity::class, TrackPrefsEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class TickdroidDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun tickDao(): TickDao
    abstract fun trackPrefsDao(): TrackPrefsDao
}
