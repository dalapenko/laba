package com.dalapenko.laba.baselineprofile

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This test class generates a baseline profile for the target package.
 *
 * It covers cold startup only. After running, copy the generated
 * baseline-prof.txt output into app/src/main/baselineProfiles/ so it
 * gets compiled into the release APK and used by [StartupBenchmarks].
 *
 * Run with:
 * ```
 * ./gradlew :baselineprofile:pixel7Api33DebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile
 * ```
 *
 * Or connect a rooted device / API 33+ device and run:
 * ```
 * ./gradlew :baselineprofile:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile
 * ```
 *
 * Only API 28+ (rooted) or API 33+ (non-rooted) are supported for profile generation.
 */
@RequiresApi(Build.VERSION_CODES.P)
@RunWith(AndroidJUnit4::class)
@LargeTest
internal class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        rule.collect(
            packageName = "com.dalapenko.laba",
            includeInStartupProfile = true,
            maxIterations = 3,
            stableIterations = 3,
        ) {
            pressHome()
            startActivityAndWait()
        }
    }
}
