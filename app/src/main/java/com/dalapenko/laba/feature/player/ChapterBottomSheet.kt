package com.dalapenko.laba.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dalapenko.laba.R
import com.dalapenko.laba.core.database.entity.TrackEntity
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterBottomSheet(
    tracks: List<TrackEntity>,
    currentTrackIndex: Int,
    onTrackSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Text(
            text = stringResource(R.string.chapters_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(tracks) { index, track ->
                val isCurrent = index == currentTrackIndex
                ListItem(
                    headlineContent = {
                        Text(
                            text = track.fileName,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    supportingContent = {
                        Text(formatDuration(track.durationMs))
                    },
                    leadingContent = if (isCurrent) {
                        {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.cd_currently_playing),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else null,
                    colors = if (isCurrent) {
                        ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        )
                    } else {
                        ListItemDefaults.colors()
                    },
                    modifier = Modifier.clickable { onTrackSelected(index) },
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ENGLISH, "%d:%02d", minutes, seconds)
}
