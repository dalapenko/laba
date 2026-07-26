package com.dalapenko.laba.core.di

import com.dalapenko.laba.core.media.PlaybackController
import com.dalapenko.laba.core.media.SleepTimerController
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val mediaModule = module {
    single { PlaybackController(androidContext()) }
    single { SleepTimerController(get()) }
}
