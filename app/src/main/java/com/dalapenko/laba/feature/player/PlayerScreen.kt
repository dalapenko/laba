package com.dalapenko.laba.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    bookId: Long,
    autoPlay: Boolean = true,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = koinViewModel { parametersOf(bookId, autoPlay) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    var showChapters by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.book?.title ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showChapters = true }) {
                        Icon(Icons.Default.ListAlt, contentDescription = "Show chapters")
                    }
                },
            )
        },
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(24.dp))

                CoverArt(
                    coverUri = uiState.book?.coverUri,
                    title = uiState.book?.title ?: "",
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .aspectRatio(1f),
                )

                Spacer(Modifier.height(24.dp))

                Text(
                    text = uiState.book?.title ?: "",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                val currentTrackIndex = playerState.currentMediaItemIndex
                    .coerceIn(0, uiState.tracks.lastIndex.coerceAtLeast(0))
                val currentTrack = uiState.tracks.getOrNull(currentTrackIndex)
                if (currentTrack != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = currentTrack.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(32.dp))

                val position = playerState.currentPositionMs.toFloat()
                val duration = playerState.durationMs.toFloat().coerceAtLeast(1f)
                var isSeeking by remember { mutableStateOf(false) }
                var seekPosition by remember { mutableStateOf(0f) }

                Slider(
                    value = if (isSeeking) seekPosition else position,
                    onValueChange = {
                        isSeeking = true
                        seekPosition = it
                    },
                    onValueChangeFinished = {
                        viewModel.seekTo(seekPosition.toLong())
                        isSeeking = false
                    },
                    valueRange = 0f..duration,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Playback position"
                        },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatTime(if (isSeeking) seekPosition.toLong() else playerState.currentPositionMs),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = formatTime(playerState.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Spacer(Modifier.height(24.dp))

                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(
                        onClick = {
                            if (currentTrackIndex > 0) viewModel.skipToTrack(currentTrackIndex - 1)
                        },
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Previous chapter",
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    Spacer(Modifier.width(16.dp))

                    FilledIconButton(
                        onClick = { viewModel.togglePlayPause() },
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(
                            imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    Spacer(Modifier.width(16.dp))

                    IconButton(
                        onClick = {
                            if (currentTrackIndex < uiState.tracks.lastIndex) {
                                viewModel.skipToTrack(currentTrackIndex + 1)
                            }
                        },
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Next chapter",
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                SpeedControl(
                    currentSpeed = playerState.playbackSpeed,
                    onSpeedChanged = { viewModel.setSpeed(it) },
                )
            }
        }
    }

    if (showChapters) {
        ChapterBottomSheet(
            tracks = uiState.tracks,
            currentTrackIndex = playerState.currentMediaItemIndex,
            onTrackSelected = { index ->
                viewModel.skipToTrack(index)
                showChapters = false
            },
            onDismiss = { showChapters = false },
        )
    }
}

@Composable
private fun CoverArt(
    coverUri: String?,
    title: String,
    modifier: Modifier = Modifier,
) {
    if (coverUri != null) {
        AsyncImage(
            model = coverUri,
            contentDescription = "Cover art for $title",
            modifier = modifier.clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title.take(2).uppercase(),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun SpeedControl(
    currentSpeed: Float,
    onSpeedChanged: (Float) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = String.format(Locale.ENGLISH, "%.2f×", currentSpeed),
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = currentSpeed,
            onValueChange = { raw ->
                val snapped = (raw * 20).toInt() / 20f
                onSpeedChanged(snapped.coerceIn(0.5f, 2.0f))
            },
            valueRange = 0.5f..2.0f,
            steps = 29,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Playback speed" },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "0.5×",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "2.0×",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ENGLISH, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ENGLISH, "%d:%02d", minutes, seconds)
    }
}
