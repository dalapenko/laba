package com.dalapenko.laba.core.di

import com.dalapenko.laba.core.media.PlaybackPreparer
import com.dalapenko.laba.feature.library.LibraryViewModel
import com.dalapenko.laba.feature.player.PlayerViewModel
import com.dalapenko.laba.feature.settings.SettingsRepository
import com.dalapenko.laba.feature.settings.SettingsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val featureModule = module {
    single { SettingsRepository(get()) }
    viewModelOf(::SettingsViewModel)

    single { PlaybackPreparer(get(), get()) }

    viewModelOf(::LibraryViewModel)
    // bookId and autoPlay are injected via parametersOf at the call site
    viewModel { params ->
        PlayerViewModel(
            bookId = params.get<Long>(),
            autoPlay = params.get<Boolean>(),
            repository = get(),
            progressRepository = get(),
            playbackController = get(),
            playbackPreparer = get(),
        )
    }
}
