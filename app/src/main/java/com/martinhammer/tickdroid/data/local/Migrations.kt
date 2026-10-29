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

/**
 * v4 → v5: notes and streaks. Two new tables, both children of `tracks` with `ON DELETE CASCADE`;
 * nothing existing changes. The SQL is copied from `app/schemas/…/5.json` (`createSql`), so it
 * matches what Room checks.
 */
internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `notes` (`localId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`trackLocalId` INTEGER NOT NULL, `date` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                "`dirty` INTEGER NOT NULL, `baseText` TEXT, FOREIGN KEY(`trackLocalId`) REFERENCES " +
                "`tracks`(`localId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_notes_trackLocalId_date` ON `notes` (`trackLocalId`, `date`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `track_runs` (`trackLocalId` INTEGER NOT NULL, `asOf` TEXT NOT NULL, " +
                "`isStreak` INTEGER, `length` INTEGER, `upToYesterday` INTEGER NOT NULL, " +
                "`maxValue` INTEGER NOT NULL, PRIMARY KEY(`trackLocalId`), FOREIGN KEY(`trackLocalId`) " +
                "REFERENCES `tracks`(`localId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
    }
}
