package com.martinhammer.tickdroid.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackEntity::class,
        TickEntity::class,
        TrackPrefsEntity::class,
        NoteEntity::class,
        TrackRunEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class TickdroidDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun tickDao(): TickDao
    abstract fun trackPrefsDao(): TrackPrefsDao
    abstract fun noteDao(): NoteDao
    abstract fun trackRunDao(): TrackRunDao

    companion object {
        const val NAME = "tickdroid.db"
    }
}
