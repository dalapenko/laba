package com.dalapenko.laba.di

import android.util.Log
import com.dalapenko.laba.feature.library.FolderScanner
import io.mockk.coEvery
import io.mockk.mockk
import org.koin.dsl.module

val testFolderScannerModule = module {
    single {
        mockk<FolderScanner>(relaxed = true).apply {
            coEvery { isBookAvailable(any()) } answers {
                val uri = firstArg<String>()
                val isTestUri = uri.startsWith("content://test/")
                Log.d("TEST_FOLDER_SCANNER", "isBookAvailable($uri) = $isTestUri")
                isTestUri
            }
            
            coEvery { scanFolder(any()) } answers {
                val uri = firstArg<String>()
                Log.w("TEST_FOLDER_SCANNER", "scanFolder($uri) called in tests - returning null")
                null
            }
        }
    }
}
