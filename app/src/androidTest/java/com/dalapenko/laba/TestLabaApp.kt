package com.dalapenko.laba

import android.app.Application
import com.dalapenko.laba.core.di.appModule
import com.dalapenko.laba.core.di.featureModule
import com.dalapenko.laba.core.di.mediaModule
import com.dalapenko.laba.di.testDatabaseModule
import com.dalapenko.laba.di.testFolderScannerModule
import com.dalapenko.laba.di.testPlaybackModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * Test Application that replaces LabaApp during instrumented tests.
 *
 * Key differences from production LabaApp:
 *
 * 1. All modules (production + test overrides) are loaded in a SINGLE startKoin call.
 *    This avoids the race where LabaApp.onCreate() eagerly calls inject<PlaybackController>()
 *    and caches the production singleton BEFORE loadKoinModules() can install the mock.
 *
 * 2. playbackController.connect() is NOT called. The mock's connect() is a no-op,
 *    and — more importantly — the production MediaSessionService must never bind in tests,
 *    because it would attempt to play the fake content:// URIs and emit TrackUnavailable errors.
 *
 * 3. Module loading order: Koin 4.1+ auto-overrides definitions loaded later in the list.
 *    testPlaybackModule is loaded AFTER mediaModule, so MockK PlaybackController replaces
 *    the production singleton.
 *    testFolderScannerModule is loaded AFTER appModule, so the test FolderScanner replaces
 *    the production scanner (prevents test books from being marked as unavailable).
 */
class TestLabaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@TestLabaApp)
            modules(
                appModule,                 // Room DB, DataStore, BookRepository, ProgressRepository, FolderScanner (production)
                mediaModule,               // PlaybackController (production - will be overridden)
                featureModule,             // ViewModels, PlaybackPreparer, SettingsRepository
                testDatabaseModule,        // override: in-memory Room, test DataStore, test DAOs
                testFolderScannerModule,   // override: FolderScanner that returns true for test URIs
                testPlaybackModule,        // override: mocked PlaybackController
            )
        }
        // Do NOT call playbackController.connect() here.
        // The mock's connect() is just runs and does not need explicit triggering.
        // Calling it would be harmless with the mock, but skipping it also ensures
        // that if the override ever fails, there is no accidental MediaSession binding.
    }
}
