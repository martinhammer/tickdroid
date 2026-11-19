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

/**
 * v5 → v6: Number and Choice tracks. New nullable columns for their settings, a NOT NULL
 * `awaitingSync` with a default, `track_options`, `track_prefs.cellLabel` and
 * `track_runs.minValue`; the SQL for the table is copied from `app/schemas/…/6.json`.
 *
 * Unlike the earlier migrations it also changes data, once, before anything reads it: the ticks
 * of every `number` and `choice` track are only 1.5.0's 1/0 markers (it read those types
 * leniently), so they are deleted, and those tracks wait for their first successful pull before
 * they can be written (groundwork §9: an update opened offline would otherwise show empty past
 * days and could overwrite the server's value). None of the markers is dirty: 1.5.0 never wrote
 * such a track. Notes are kept: 1.5.0 read and wrote them correctly.
 */
internal val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN unit TEXT")
        db.execSQL("ALTER TABLE tracks ADD COLUMN decimalPlaces INTEGER")
        db.execSQL("ALTER TABLE tracks ADD COLUMN allowNegative INTEGER")
        db.execSQL("ALTER TABLE tracks ADD COLUMN orderedScale INTEGER")
        db.execSQL("ALTER TABLE tracks ADD COLUMN awaitingSync INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE track_prefs ADD COLUMN cellLabel TEXT")
        db.execSQL("ALTER TABLE track_runs ADD COLUMN minValue INTEGER")
        db.execSQL(TRACK_OPTIONS_SQL)
        db.execSQL("UPDATE tracks SET awaitingSync = 1 WHERE type IN ('number', 'choice')")
        db.execSQL(
            "DELETE FROM ticks WHERE trackLocalId IN (SELECT localId FROM tracks WHERE type IN ('number', 'choice'))",
        )
    }
}

private const val TRACK_OPTIONS_SQL =
    "CREATE TABLE IF NOT EXISTS `track_options` (`trackLocalId` INTEGER NOT NULL, `place` INTEGER NOT NULL, " +
        "`label` TEXT NOT NULL, PRIMARY KEY(`trackLocalId`, `place`), FOREIGN KEY(`trackLocalId`) " +
        "REFERENCES `tracks`(`localId`) ON UPDATE NO ACTION ON DELETE CASCADE )"
