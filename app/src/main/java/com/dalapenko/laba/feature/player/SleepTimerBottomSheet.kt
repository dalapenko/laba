package com.dalapenko.laba.feature.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dalapenko.laba.R
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerBottomSheet(
    isActive: Boolean,
    remainingMs: Long,
    remainingChapterMs: Long,
    initialFixedMinutes: Int,
    onStartFixedDuration: (Int) -> Unit,
    onStartEndOfChapter: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("sleep_timer_bottom_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.sleep_timer_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("sleep_timer_title"),
            )

            Spacer(Modifier.height(16.dp))

            if (isActive) {
                ActiveSleepTimerContent(remainingMs = remainingMs, onCancel = onCancel)
            } else {
                ConfigureSleepTimerContent(
                    remainingChapterMs = remainingChapterMs,
                    initialFixedMinutes = initialFixedMinutes,
                    onStartFixedDuration = onStartFixedDuration,
                    onStartEndOfChapter = onStartEndOfChapter,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ActiveSleepTimerContent(remainingMs: Long, onCancel: () -> Unit) {
    Text(
        text = formatCountdown(remainingMs),
        style = MaterialTheme.typography.displayMedium,
        modifier = Modifier.testTag("sleep_timer_remaining"),
    )

    Spacer(Modifier.height(24.dp))

    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sleep_timer_cancel_button"),
    ) {
        Text(stringResource(R.string.sleep_timer_cancel))
    }
}

@Composable
private fun ConfigureSleepTimerContent(
    remainingChapterMs: Long,
    initialFixedMinutes: Int,
    onStartFixedDuration: (Int) -> Unit,
    onStartEndOfChapter: () -> Unit,
) {
    var pendingEndOfChapter by remember { mutableStateOf(false) }
    var pendingMinutes by remember { mutableIntStateOf(initialFixedMinutes) }
    var hasLocalEdit by remember { mutableStateOf(false) }

    LaunchedEffect(initialFixedMinutes) {
        if (!hasLocalEdit) pendingMinutes = initialFixedMinutes
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.sleep_timer_end_of_chapter),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = pendingEndOfChapter,
            onCheckedChange = { checked ->
                hasLocalEdit = true
                pendingEndOfChapter = checked
                if (checked) {
                    pendingMinutes = millisToNearestMinute(remainingChapterMs)
                }
            },
            modifier = Modifier.testTag("sleep_timer_end_of_chapter_switch"),
        )
    }

    Spacer(Modifier.height(24.dp))

    SleepTimerDial(
        minutes = pendingMinutes,
        onMinutesChanged = { newValue ->
            hasLocalEdit = true
            pendingMinutes = newValue
            pendingEndOfChapter = false
        },
    )

    Spacer(Modifier.height(24.dp))

    Button(
        onClick = {
            if (pendingEndOfChapter) {
                onStartEndOfChapter()
            } else {
                onStartFixedDuration(pendingMinutes)
            }
        },
        enabled = pendingMinutes > 0,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sleep_timer_start_button"),
    ) {
        Text(stringResource(R.string.sleep_timer_start))
    }
}

private fun millisToNearestMinute(ms: Long): Int =
    ((ms + HALF_MINUTE_MS) / MILLIS_PER_MINUTE).toInt().coerceIn(1, SLEEP_TIMER_MAX_MINUTES)

private fun formatCountdown(remainingMs: Long): String {
    val totalSeconds = remainingMs / MILLIS_PER_SECOND
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return String.format(Locale.ENGLISH, "%02d:%02d", minutes, seconds)
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val HALF_MINUTE_MS = 30_000L
private const val MILLIS_PER_SECOND = 1000
private const val SECONDS_PER_MINUTE = 60
