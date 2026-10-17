package com.martinhammer.tickdroid.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Replays the exported v2 schema and runs the real migration against it. */
class MigrationTest {

    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TickdroidDatabase::class.java,
    )

    @Test fun migrate2To3_addsArchivedAt_keepsEverything() {
        helper.createDatabase(dbName, 2).use { db ->
            db.execSQL(
                "INSERT INTO tracks (localId, serverId, name, type, sortOrder, private, dirty, deleted, updatedAtLocal) " +
                    "VALUES (1, 10, 'Meditate', 'boolean', 0, 0, 0, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO ticks (localId, serverId, trackLocalId, date, value, dirty, deleted, updatedAtLocal) " +
                    "VALUES (1, NULL, 1, '2026-04-30', 1, 1, 0, 0)",
            )
            db.execSQL("INSERT INTO track_prefs (serverId, colorKey, emoji) VALUES (10, 'blue', '🧘')")
        }

        // Validates the result against 3.json.
        helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_2_3).use { db ->
            db.query("SELECT serverId, name, archivedAt FROM tracks").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(10L, c.getLong(0))
                assertEquals("Meditate", c.getString(1))
                assertTrue(c.isNull(2))
            }
            db.query("SELECT trackLocalId, date, value, dirty FROM ticks").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1L, c.getLong(0))
                assertEquals("2026-04-30", c.getString(1))
                assertEquals(1, c.getInt(2))
                assertEquals(1, c.getInt(3))
            }
            db.query("SELECT serverId, colorKey, emoji FROM track_prefs").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(10L, c.getLong(0))
                assertEquals("blue", c.getString(1))
                assertEquals("🧘", c.getString(2))
            }
            db.query("SELECT COUNT(*) FROM tracks WHERE archivedAt IS NOT NULL").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }
}
