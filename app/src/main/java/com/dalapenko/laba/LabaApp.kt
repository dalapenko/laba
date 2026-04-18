package com.dalapenko.laba

import android.app.Application
import com.dalapenko.laba.core.di.appModule
import com.dalapenko.laba.core.di.featureModule
import com.dalapenko.laba.core.di.mediaModule
import com.dalapenko.laba.core.media.PlaybackController
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class LabaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@LabaApp)
            modules(appModule, mediaModule, featureModule)
        }

        // Initialize PlaybackController once at app startup
        // Establishes persistent connection to MediaSessionService for background playback
        val playbackController: PlaybackController by inject()
        playbackController.connect()
    }
}
