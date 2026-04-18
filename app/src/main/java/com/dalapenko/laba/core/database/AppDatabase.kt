package com.dalapenko.laba.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dalapenko.laba.core.database.dao.BookDao
import com.dalapenko.laba.core.database.dao.ProgressDao
import com.dalapenko.laba.core.database.dao.TrackDao
import com.dalapenko.laba.core.database.entity.BookEntity
import com.dalapenko.laba.core.database.entity.ProgressEntity
import com.dalapenko.laba.core.database.entity.TrackEntity

@Database(
    entities = [BookEntity::class, TrackEntity::class, ProgressEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun trackDao(): TrackDao
    abstract fun progressDao(): ProgressDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE progress ADD COLUMN playbackSpeed REAL NOT NULL DEFAULT 1.0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN isAvailable INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Recreate progress table with FK constraint (SQLite requires table recreation for FK)
                db.execSQL(
                    """
                        CREATE TABLE progress_new (
                            bookId INTEGER NOT NULL PRIMARY KEY,
                            lastTrackId INTEGER NOT NULL,
                            lastPositionMs INTEGER NOT NULL,
                            completedTracksMs INTEGER NOT NULL DEFAULT 0,
                            lastUpdated INTEGER NOT NULL,
                            isCompleted INTEGER NOT NULL DEFAULT 0,
                            playbackSpeed REAL NOT NULL DEFAULT 1.0,
                            FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE
                        )
                    """.trimIndent(),
                )
                db.execSQL("INSERT INTO progress_new SELECT * FROM progress")
                db.execSQL("DROP TABLE progress")
                db.execSQL("ALTER TABLE progress_new RENAME TO progress")
                // Add unique index to books
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_books_rootFolderUri ON books(rootFolderUri)",
                )
            }
        }
    }
}
