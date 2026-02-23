package com.dalapenko.laba.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dalapenko.laba.core.data.BookRepository
import com.dalapenko.laba.core.database.AppDatabase
import com.dalapenko.laba.feature.library.FolderScanner
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

/**
 * Test module providing in-memory Room database for instrumented tests.
 *
 * Overrides the production appModule with test-friendly implementations:
 * - In-memory Room database (instead of persistent on-disk)
 * - Test DataStore (in cache directory, isolated per test run)
 * - Same BookRepository and FolderScanner wired to the test database
 *
 * All definitions use `override = true` so they replace the production bindings
 * loaded by appModule earlier in LabaTestRunner.callApplicationOnCreate.
 * Without this, Koin 4.x throws DefinitionOverrideException on conflicting singles.
 */
val testDatabaseModule = module {
    // DataStore — isolated to test cache directory
    single {
        PreferenceDataStoreFactory.create(
            produceFile = {
                File(
                    InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                    "test_settings.preferences_pb"
                )
            }
        )
    }

    // In-memory Room database — no allowMainThreadQueries needed:
    // Room suspend functions dispatch to their internal executor regardless of thread.
    single {
        Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        )
        .build()
    }

    single { get<AppDatabase>().bookDao() }
    single { get<AppDatabase>().trackDao() }
    single { get<AppDatabase>().progressDao() }

    // Same as production but wired to the in-memory database above
    single { BookRepository(get(), get(), get()) }
    single { FolderScanner(androidContext()) }
}
