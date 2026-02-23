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
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay10
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.dalapenko.laba.R
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
    val snackbarHostState = remember { SnackbarHostState() }
    val fallbackTrackNameTemplate = stringResource(R.string.fallback_track_name)
    val trackUnavailableTemplate = stringResource(R.string.snackbar_track_unavailable)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                PlayerEvent.ClosePlayer -> onBack()
                is PlayerEvent.TrackUnavailable -> {
                    val name = event.trackName
                        ?: String.format(fallbackTrackNameTemplate, event.trackIndex + 1)
                    snackbarHostState.showSnackbar(
                        message = String.format(trackUnavailableTemplate, name),
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    val showChapters = remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showChapters.value = true },
                        enabled = uiState.tracks.size > 1,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = stringResource(R.string.cd_show_chapters))
                    }
                },
            )
        },
    ) { padding ->
        if (uiState.isLoading || uiState.isInitializing) {
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
                var seekPosition by remember { mutableFloatStateOf(0f) }

                val playbackPositionDesc = stringResource(R.string.cd_playback_position)
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
                            contentDescription = playbackPositionDesc
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
                        enabled = uiState.tracks.size > 1 && currentTrackIndex > 0,
                        onClick = { viewModel.skipToTrack(currentTrackIndex - 1) },
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = stringResource(R.string.cd_previous_chapter),
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    IconButton(
                        onClick = { viewModel.seekBack() },
                    ) {
                        Icon(
                            Icons.Default.Replay10,
                            contentDescription = stringResource(R.string.cd_rewind_10),
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    FilledIconButton(
                        onClick = { viewModel.togglePlayPause() },
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(
                            imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playerState.isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play),
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    IconButton(
                        onClick = { viewModel.seekForward() },
                    ) {
                        Icon(
                            Icons.Default.Forward10,
                            contentDescription = stringResource(R.string.cd_forward_10),
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    IconButton(
                        enabled = uiState.tracks.size > 1 && currentTrackIndex < uiState.tracks.lastIndex,
                        onClick = { viewModel.skipToTrack(currentTrackIndex + 1) },
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = stringResource(R.string.cd_next_chapter),
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

    if (showChapters.value) {
        ChapterBottomSheet(
            tracks = uiState.tracks,
            currentTrackIndex = playerState.currentMediaItemIndex,
            onTrackSelected = { index ->
                viewModel.skipToTrack(index)
                showChapters.value = false
            },
            onDismiss = { showChapters.value = false },
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
            contentDescription = stringResource(R.string.cd_cover_art_for, title),
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
        val playbackSpeedDesc = stringResource(R.string.cd_playback_speed)
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
                .semantics { contentDescription = playbackSpeedDesc },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.speed_min),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.speed_max),
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
