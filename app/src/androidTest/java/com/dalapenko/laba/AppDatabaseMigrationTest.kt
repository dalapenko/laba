package com.dalapenko.laba

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dalapenko.laba.core.database.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate4To5_preservesBooksTracksProgressAndAvailability() {
        val databaseName = "migration-4-5-test"
        migrationHelper.createDatabase(databaseName, 4).apply {
            execSQL(
                """
                    INSERT INTO books (
                        id, title, author, coverUri, rootFolderUri, totalDurationMs, isAvailable
                    ) VALUES (1, 'Legacy book', 'Author', NULL, 'content://legacy/book', 120000, 0)
                """.trimIndent(),
            )
            execSQL(
                """
                    INSERT INTO tracks (
                        id, bookId, fileUri, fileName, durationMs, sequenceOrder
                    ) VALUES (10, 1, 'content://legacy/track', 'Chapter 1', 120000, 0)
                """.trimIndent(),
            )
            execSQL(
                """
                    INSERT INTO progress (
                        bookId, lastTrackId, lastPositionMs, completedTracksMs,
                        lastUpdated, isCompleted, playbackSpeed
                    ) VALUES (1, 10, 45000, 0, 1234, 0, 1.25)
                """.trimIndent(),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            5,
            true,
            AppDatabase.MIGRATION_4_5,
        )

        migrated.query(
            "SELECT title, isAvailable, addedAt FROM books WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Legacy book", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(0L, cursor.getLong(2))
        }
        migrated.query("SELECT COUNT(*) FROM tracks WHERE bookId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        migrated.query("SELECT lastPositionMs FROM progress WHERE bookId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(45_000L, cursor.getLong(0))
        }
        migrated.close()
    }
}
