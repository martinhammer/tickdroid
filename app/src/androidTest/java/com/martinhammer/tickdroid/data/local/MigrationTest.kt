package com.martinhammer.tickdroid.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Replays the exported schemas and runs the real migrations against them. */
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

    @Test fun migrate3To4_addsPendingDelta_keepsEverything() {
        helper.createDatabase(dbName, 3).use { db ->
            db.execSQL(
                "INSERT INTO tracks (localId, serverId, name, type, sortOrder, private, archivedAt, dirty, deleted, updatedAtLocal) " +
                    "VALUES (1, 10, 'Meditate', 'boolean', 0, 0, NULL, 0, 0, 0), (2, 11, 'Coffee', 'counter', 1, 0, NULL, 0, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO ticks (localId, serverId, trackLocalId, date, value, dirty, deleted, updatedAtLocal) VALUES " +
                    "(1, 100, 1, '2026-04-30', 1, 0, 0, 0), " + // yes/no
                    "(2, 101, 2, '2026-04-29', 3, 0, 0, 0), " + // clean counter
                    "(3, 102, 2, '2026-04-30', 5, 1, 0, 0)", // dirty counter, queued by v3
            )
            db.execSQL("INSERT INTO track_prefs (serverId, colorKey, emoji) VALUES (11, 'brown', '☕')")
        }

        // Validates the result against 4.json.
        helper.runMigrationsAndValidate(dbName, 4, true, MIGRATION_3_4).use { db ->
            db.query("SELECT localId, serverId, trackLocalId, date, value, dirty, deleted, pendingDelta, sentDelta, sentKey FROM ticks ORDER BY localId").use { c ->
                val expected = listOf(
                    listOf(1L, 100L, 1L, "2026-04-30", 1, 0, 0),
                    listOf(2L, 101L, 2L, "2026-04-29", 3, 0, 0),
                    listOf(3L, 102L, 2L, "2026-04-30", 5, 1, 0),
                )
                for (row in expected) {
                    assertTrue(c.moveToNext())
                    assertEquals(row, listOf(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getInt(4), c.getInt(5), c.getInt(6)))
                    assertTrue("pendingDelta", c.isNull(7))
                    assertTrue("sentDelta", c.isNull(8))
                    assertTrue("sentKey", c.isNull(9))
                }
                assertFalse(c.moveToNext())
            }
            db.query("SELECT COUNT(*) FROM tracks").use { c ->
                c.moveToFirst()
                assertEquals(2, c.getInt(0))
            }
            db.query("SELECT serverId, colorKey, emoji FROM track_prefs").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(11L, c.getLong(0))
                assertEquals("brown", c.getString(1))
                assertEquals("☕", c.getString(2))
            }
        }
    }
}
