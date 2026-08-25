package com.dalapenko.laba.feature.library

import android.content.ActivityNotFoundException

/** Starts an external Storage Access Framework picker without crashing if it is unavailable. */
internal fun launchSafPickerSafely(
    launch: () -> Unit,
    onUnavailable: () -> Unit,
) {
    try {
        launch()
    } catch (_: ActivityNotFoundException) {
        onUnavailable()
    }
}
