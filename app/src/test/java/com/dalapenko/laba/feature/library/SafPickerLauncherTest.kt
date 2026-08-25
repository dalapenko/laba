package com.dalapenko.laba.feature.library

import android.content.ActivityNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafPickerLauncherTest {

    @Test
    fun givenPickerAvailable_whenLaunching_thenInvokesPicker() {
        var pickerLaunched = false
        var unavailableShown = false

        launchSafPickerSafely(
            launch = { pickerLaunched = true },
            onUnavailable = { unavailableShown = true },
        )

        assertTrue(pickerLaunched)
        assertFalse(unavailableShown)
    }

    @Test
    fun givenPickerUnavailable_whenLaunching_thenShowsUnavailableStateWithoutThrowing() {
        var unavailableShown = false

        launchSafPickerSafely(
            launch = { throw ActivityNotFoundException("No Activity found to handle picker Intent") },
            onUnavailable = { unavailableShown = true },
        )

        assertTrue(unavailableShown)
    }
}
