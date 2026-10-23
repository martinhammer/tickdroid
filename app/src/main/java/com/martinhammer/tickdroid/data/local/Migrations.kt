package com.martinhammer.tickdroid.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v2 → v3: archived tracks. One nullable column; every existing row reads back as null (active),
 * which is right because no earlier version stored an archived track. No default value, so it
 * matches the entity (Room's schema check compares the two).
 */
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN archivedAt TEXT")
    }
}

/**
 * v3 → v4: counters push their change with `increment`. Three nullable columns, no defaults, so
 * every existing row reads back null: clean rows have nothing pending, and a dirty counter row
 * queued by an older version stays absolute and goes up once more with `set`.
 */
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ticks ADD COLUMN pendingDelta INTEGER")
        db.execSQL("ALTER TABLE ticks ADD COLUMN sentDelta INTEGER")
        db.execSQL("ALTER TABLE ticks ADD COLUMN sentKey TEXT")
    }
}
