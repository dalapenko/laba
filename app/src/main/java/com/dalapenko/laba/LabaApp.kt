package com.dalapenko.laba

import android.app.Application
import com.dalapenko.laba.core.di.appModule
import com.dalapenko.laba.core.di.featureModule
import com.dalapenko.laba.core.di.mediaModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class LabaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@LabaApp)
            modules(appModule, mediaModule, featureModule)
        }
    }
}
