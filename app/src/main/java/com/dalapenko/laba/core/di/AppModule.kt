package com.dalapenko.laba.core.di

import androidx.room.Room
import com.dalapenko.laba.core.database.AppDatabase
import com.dalapenko.laba.feature.library.BookRepository
import com.dalapenko.laba.feature.library.FolderScanner
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            DATABASE_NAME,
        )
            .fallbackToDestructiveMigration(false)
            .build()
    }

    single { get<AppDatabase>().bookDao() }
    single { get<AppDatabase>().trackDao() }
    single { get<AppDatabase>().progressDao() }

    single { BookRepository(get(), get(), get()) }
    single { FolderScanner(androidContext()) }
}

private const val DATABASE_NAME = "laba-database"
