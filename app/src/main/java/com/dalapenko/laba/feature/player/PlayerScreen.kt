package com.dalapenko.laba.feature.player

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.dalapenko.laba.R
import com.dalapenko.laba.core.media.PlayerState
import kotlinx.coroutines.flow.Flow
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.util.Locale
import kotlin.math.roundToInt

private val LANDSCAPE_TRANSPORT_ICON_SIZE = 32.dp
private val LANDSCAPE_TRANSPORT_PLAY_BUTTON_SIZE = 56.dp
private val LANDSCAPE_TRANSPORT_SPACING = 4.dp
private val PORTRAIT_TRANSPORT_ICON_SIZE = 36.dp
private val PORTRAIT_TRANSPORT_PLAY_BUTTON_SIZE = 64.dp
private val PORTRAIT_TRANSPORT_SPACING = 8.dp

/** Bundles viewmodel callbacks so composables down the tree take one param instead of six. */
private data class PlayerActions(
    val onSeekTo: (Long) -> Unit,
    val onSkipToTrack: (Int) -> Unit,
    val onSeekBack: () -> Unit,
    val onSeekForward: () -> Unit,
    val onTogglePlayPause: () -> Unit,
    val onSetSpeed: (Float) -> Unit,
)

/** Derived, display-ready playback data shared by the landscape and portrait layouts. */
private data class PlaybackDisplayState(
    val title: String,
    val coverUri: String?,
    val trackFileName: String?,
    val position: Float,
    val duration: Float,
    val currentPositionMs: Long,
    val durationMs: Long,
    val isSeeking: Boolean,
    val seekPosition: Float,
    val isPlaying: Boolean,
    val playbackSpeed: Float,
    val isPreviousEnabled: Boolean,
    val isNextEnabled: Boolean,
)

/** Resolved transport-control callbacks, closed over the current track index and seek state. */
private data class TransportActions(
    val onPrevious: () -> Unit,
    val onRewind: () -> Unit,
    val onPlayPause: () -> Unit,
    val onForward: () -> Unit,
    val onNext: () -> Unit,
    val onSeekChange: (Float) -> Unit,
    val onSeekFinished: () -> Unit,
    val onSetSpeed: (Float) -> Unit,
)

/** Bundles the sleep-timer viewmodel callbacks separately so [PlayerActions] stays under the param-count limit. */
private data class SleepTimerActions(
    val onStartFixedDuration: (Long) -> Unit,
    val onStartEndOfChapter: () -> Unit,
    val onCancel: () -> Unit,
)

@Composable
fun PlayerScreen(
    bookId: Long,
    autoPlay: Boolean = true,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = koinViewModel { parametersOf(bookId, autoPlay) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    PlayerEventEffect(
        events = viewModel.events,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
    )

    val actions = remember(viewModel) {
        PlayerActions(
            onSeekTo = viewModel::seekTo,
            onSkipToTrack = viewModel::skipToTrack,
            onSeekBack = viewModel::seekBack,
            onSeekForward = viewModel::seekForward,
            onTogglePlayPause = viewModel::togglePlayPause,
            onSetSpeed = viewModel::setSpeed,
        )
    }
    val sleepTimerActions = remember(viewModel) {
        SleepTimerActions(
            onStartFixedDuration = viewModel::startSleepTimerFixed,
            onStartEndOfChapter = viewModel::startSleepTimerEndOfChapter,
            onCancel = viewModel::cancelSleepTimer,
        )
    }

    PlayerScaffold(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        actions = actions,
        sleepTimerActions = sleepTimerActions,
    )
}

@Composable
private fun PlayerEventEffect(
    events: Flow<PlayerEvent>,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
) {
    val fallbackTrackNameTemplate = stringResource(R.string.fallback_track_name)
    val trackUnavailableTemplate = stringResource(R.string.snackbar_track_unavailable)

    LaunchedEffect(Unit) {
        events.collect { event ->
            when (event) {
                PlayerEvent.ClosePlayer -> onBack()
                is PlayerEvent.TrackUnavailable -> {
                    val name = event.trackName
                        ?: String.format(fallbackTrackNameTemplate, event.trackIndex + 1)
                    snackbarHostState.showSnackbar(
                        message = String.format(trackUnavailableTemplate, name),
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerScaffold(
    uiState: PlayerUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    actions: PlayerActions,
    sleepTimerActions: SleepTimerActions,
) {
    val playerState = uiState.playerState
    val showChapters = remember { mutableStateOf(false) }
    val showSleepTimer = remember { mutableStateOf(false) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        modifier = Modifier.testTag("player_screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            PlayerTopBar(
                title = uiState.book?.title ?: "",
                chaptersEnabled = uiState.tracks.size > 1,
                sleepTimerActive = uiState.sleepTimerState.isActive,
                onBack = onBack,
                onShowChapters = { showChapters.value = true },
                onShowSleepTimer = { showSleepTimer.value = true },
            )
        },
    ) { padding ->
        if (uiState.isLoading || uiState.isInitializing) {
            LoadingIndicator(padding)
        } else {
            PlayerContent(
                uiState = uiState,
                playerState = playerState,
                isLandscape = isLandscape,
                padding = padding,
                actions = actions,
            )
        }
    }

    PlayerBottomSheets(
        uiState = uiState,
        playerState = playerState,
        actions = actions,
        sleepTimerActions = sleepTimerActions,
        showChapters = showChapters,
        showSleepTimer = showSleepTimer,
    )
}

@Composable
private fun PlayerBottomSheets(
    uiState: PlayerUiState,
    playerState: PlayerState,
    actions: PlayerActions,
    sleepTimerActions: SleepTimerActions,
    showChapters: MutableState<Boolean>,
    showSleepTimer: MutableState<Boolean>,
) {
    if (showChapters.value) {
        ChapterBottomSheet(
            tracks = uiState.tracks,
            currentTrackIndex = playerState.currentMediaItemIndex,
            onTrackSelected = { index ->
                actions.onSkipToTrack(index)
                showChapters.value = false
            },
            onDismiss = { showChapters.value = false },
        )
    }

    if (showSleepTimer.value) {
        SleepTimerBottomSheet(
            isActive = uiState.sleepTimerState.isActive,
            remainingMs = uiState.sleepTimerState.remainingMs,
            remainingChapterMs = (playerState.durationMs - playerState.currentPositionMs).coerceAtLeast(0L),
            onStartFixedDuration = { durationMs ->
                sleepTimerActions.onStartFixedDuration(durationMs)
                showSleepTimer.value = false
            },
            onStartEndOfChapter = {
                sleepTimerActions.onStartEndOfChapter()
                showSleepTimer.value = false
            },
            onCancel = {
                sleepTimerActions.onCancel()
                showSleepTimer.value = false
            },
            onDismiss = { showSleepTimer.value = false },
        )
    }
}

@Composable
private fun LoadingIndicator(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerTopBar(
    title: String,
    chaptersEnabled: Boolean,
    sleepTimerActive: Boolean,
    onBack: () -> Unit,
    onShowChapters: () -> Unit,
    onShowSleepTimer: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("player_book_title"),
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("back_button"),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
        },
        actions = {
            IconButton(
                onClick = onShowSleepTimer,
                modifier = Modifier.testTag("sleep_timer_button"),
            ) {
                Icon(
                    Icons.Outlined.Bedtime,
                    contentDescription = stringResource(R.string.cd_sleep_timer),
                    tint = if (sleepTimerActive) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
            IconButton(
                onClick = onShowChapters,
                enabled = chaptersEnabled,
                modifier = Modifier.testTag("chapters_button"),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ListAlt,
                    contentDescription = stringResource(R.string.cd_show_chapters),
                )
            }
        },
    )
}

@Composable
private fun PlayerContent(
    uiState: PlayerUiState,
    playerState: PlayerState,
    isLandscape: Boolean,
    padding: PaddingValues,
    actions: PlayerActions,
) {
    val currentTrackIndex = playerState.currentMediaItemIndex
        .coerceIn(0, uiState.tracks.lastIndex.coerceAtLeast(0))
    val currentTrack = uiState.tracks.getOrNull(currentTrackIndex)
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }

    val displayState = PlaybackDisplayState(
        title = uiState.book?.title ?: "",
        coverUri = uiState.book?.coverUri,
        trackFileName = currentTrack?.fileName,
        position = playerState.currentPositionMs.toFloat(),
        duration = playerState.durationMs.toFloat().coerceAtLeast(1f),
        currentPositionMs = playerState.currentPositionMs,
        durationMs = playerState.durationMs,
        isSeeking = isSeeking,
        seekPosition = seekPosition,
        isPlaying = playerState.isPlaying,
        playbackSpeed = playerState.playbackSpeed,
        isPreviousEnabled = uiState.tracks.size > 1 && currentTrackIndex > 0,
        isNextEnabled = uiState.tracks.size > 1 && currentTrackIndex < uiState.tracks.lastIndex,
    )
    val transportActions = TransportActions(
        onPrevious = { actions.onSkipToTrack(currentTrackIndex - 1) },
        onRewind = actions.onSeekBack,
        onPlayPause = actions.onTogglePlayPause,
        onForward = actions.onSeekForward,
        onNext = { actions.onSkipToTrack(currentTrackIndex + 1) },
        onSeekChange = {
            isSeeking = true
            seekPosition = it
        },
        onSeekFinished = {
            actions.onSeekTo(seekPosition.toLong())
            isSeeking = false
        },
        onSetSpeed = actions.onSetSpeed,
    )

    if (isLandscape) {
        LandscapePlayerContent(displayState, transportActions, padding)
    } else {
        PortraitPlayerContent(displayState, transportActions, padding)
    }
}

@Composable
private fun LandscapePlayerContent(
    state: PlaybackDisplayState,
    actions: TransportActions,
    padding: PaddingValues,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            coverUri = state.coverUri,
            title = state.title,
            modifier = Modifier
                .fillMaxHeight(LANDSCAPE_COVER_HEIGHT_FRACTION)
                .aspectRatio(1f),
        )

        Spacer(Modifier.width(20.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TrackInfo(
                title = state.title,
                trackFileName = state.trackFileName,
                titleStyle = MaterialTheme.typography.titleLarge,
            )

            Spacer(Modifier.height(12.dp))

            ProgressSection(state = state, onSeekChange = actions.onSeekChange, onSeekFinished = actions.onSeekFinished)

            Spacer(Modifier.height(8.dp))

            TransportControls(
                state = state,
                actions = actions,
                iconSize = LANDSCAPE_TRANSPORT_ICON_SIZE,
                playButtonSize = LANDSCAPE_TRANSPORT_PLAY_BUTTON_SIZE,
                spacing = LANDSCAPE_TRANSPORT_SPACING,
            )

            Spacer(Modifier.height(8.dp))

            SpeedControl(currentSpeed = state.playbackSpeed, onSpeedChanged = actions.onSetSpeed)
        }
    }
}

@Composable
private fun PortraitPlayerContent(
    state: PlaybackDisplayState,
    actions: TransportActions,
    padding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))

        CoverArt(
            coverUri = state.coverUri,
            title = state.title,
            modifier = Modifier
                .fillMaxWidth(PORTRAIT_COVER_WIDTH_FRACTION)
                .aspectRatio(1f),
        )

        Spacer(Modifier.height(24.dp))

        TrackInfo(
            title = state.title,
            trackFileName = state.trackFileName,
            titleStyle = MaterialTheme.typography.headlineSmall,
        )

        Spacer(Modifier.height(32.dp))

        ProgressSection(state = state, onSeekChange = actions.onSeekChange, onSeekFinished = actions.onSeekFinished)

        Spacer(Modifier.height(24.dp))

        TransportControls(
            state = state,
            actions = actions,
            iconSize = PORTRAIT_TRANSPORT_ICON_SIZE,
            playButtonSize = PORTRAIT_TRANSPORT_PLAY_BUTTON_SIZE,
            spacing = PORTRAIT_TRANSPORT_SPACING,
        )

        Spacer(Modifier.height(24.dp))

        SpeedControl(currentSpeed = state.playbackSpeed, onSpeedChanged = actions.onSetSpeed)
    }
}

@Composable
private fun TrackInfo(
    title: String,
    trackFileName: String?,
    titleStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = titleStyle,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (trackFileName != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = trackFileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProgressSection(
    state: PlaybackDisplayState,
    onSeekChange: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playbackPositionDesc = stringResource(R.string.cd_playback_position)
    val displayedPositionMs = if (state.isSeeking) state.seekPosition.toLong() else state.currentPositionMs

    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = if (state.isSeeking) state.seekPosition else state.position,
            onValueChange = onSeekChange,
            onValueChangeFinished = onSeekFinished,
            valueRange = 0f..state.duration,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("position_slider")
                .semantics { contentDescription = playbackPositionDesc },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = formatTime(displayedPositionMs), style = MaterialTheme.typography.labelSmall)
            Text(text = formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TransportControls(
    state: PlaybackDisplayState,
    actions: TransportActions,
    iconSize: Dp,
    playButtonSize: Dp,
    spacing: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        TransportIconButton(
            icon = Icons.Default.SkipPrevious,
            contentDescription = stringResource(R.string.cd_previous_chapter),
            iconSize = iconSize,
            testTag = "previous_chapter_button",
            enabled = state.isPreviousEnabled,
            onClick = actions.onPrevious,
        )

        Spacer(Modifier.width(spacing))

        TransportIconButton(
            icon = Icons.Default.Replay10,
            contentDescription = stringResource(R.string.cd_rewind_10),
            iconSize = iconSize,
            testTag = "rewind_button",
            onClick = actions.onRewind,
        )

        Spacer(Modifier.width(spacing))

        FilledIconButton(
            onClick = actions.onPlayPause,
            modifier = Modifier
                .size(playButtonSize)
                .testTag("play_pause_button"),
        ) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (state.isPlaying) {
                    stringResource(R.string.cd_pause)
                } else {
                    stringResource(R.string.cd_play)
                },
                modifier = Modifier.size(iconSize),
            )
        }

        Spacer(Modifier.width(spacing))

        TransportIconButton(
            icon = Icons.Default.Forward10,
            contentDescription = stringResource(R.string.cd_forward_10),
            iconSize = iconSize,
            testTag = "forward_button",
            onClick = actions.onForward,
        )

        Spacer(Modifier.width(spacing))

        TransportIconButton(
            icon = Icons.Default.SkipNext,
            contentDescription = stringResource(R.string.cd_next_chapter),
            iconSize = iconSize,
            testTag = "next_chapter_button",
            enabled = state.isNextEnabled,
            onClick = actions.onNext,
        )
    }
}

@Composable
private fun TransportIconButton(
    icon: ImageVector,
    contentDescription: String,
    iconSize: Dp,
    testTag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(iconSize))
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
        modifier = Modifier
            .fillMaxWidth()
            .testTag("speed_control"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = String.format(Locale.ENGLISH, "%.2f×", currentSpeed),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("speed_text"),
        )
        val playbackSpeedDesc = stringResource(R.string.cd_playback_speed)
        Slider(
            value = currentSpeed,
            onValueChange = { raw ->
                val snapped = (raw * PLAYBACK_SPEED_STEP_COUNT).roundToInt() / PLAYBACK_SPEED_STEP_COUNT.toFloat()
                onSpeedChanged(snapped.coerceIn(PLAYBACK_SPEED_MIN, PLAYBACK_SPEED_MAX))
            },
            valueRange = PLAYBACK_SPEED_MIN..PLAYBACK_SPEED_MAX,
            steps = PLAYBACK_SPEED_SLIDER_STEPS,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("speed_slider")
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
    val totalSeconds = ms / MILLIS_PER_SECOND
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        String.format(Locale.ENGLISH, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ENGLISH, "%d:%02d", minutes, seconds)
    }
}

private const val PLAYBACK_SPEED_STEP_COUNT = 20
private const val PLAYBACK_SPEED_MIN = 0.5f
private const val PLAYBACK_SPEED_MAX = 2.0f
private const val PLAYBACK_SPEED_SLIDER_STEPS = 29
private const val MILLIS_PER_SECOND = 1000
private const val SECONDS_PER_HOUR = 3600
private const val SECONDS_PER_MINUTE = 60
private const val LANDSCAPE_COVER_HEIGHT_FRACTION = 0.85f
private const val PORTRAIT_COVER_WIDTH_FRACTION = 0.7f
