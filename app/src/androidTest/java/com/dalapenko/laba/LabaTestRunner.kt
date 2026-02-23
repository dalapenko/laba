package com.dalapenko.laba

import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Custom test runner that substitutes LabaApp with TestLabaApp.
 *
 * newApplication() is the correct interception point: it runs before any Application.onCreate(),
 * so Koin is never started with production modules and the production PlaybackController
 * singleton is never created. TestLabaApp starts Koin once with all modules in a single call,
 * including the in-memory database and mocked PlaybackController overrides.
 *
 * Previous approach (callApplicationOnCreate + loadKoinModules) was broken because
 * LabaApp.onCreate() eagerly calls inject<PlaybackController>() and caches the production
 * singleton before loadKoinModules() could install the mock. override = true replaces the
 * Koin definition but does not evict an already-created singleton from the cache.
 *
 * To use this runner, configure it in app/build.gradle.kts:
 * ```
 * defaultConfig {
 *     testInstrumentationRunner = "com.dalapenko.laba.LabaTestRunner"
 * }
 * ```
 */
@Suppress("unused")
class LabaTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context,
    ): android.app.Application {
        return super.newApplication(cl, TestLabaApp::class.java.name, context)
    }
}
