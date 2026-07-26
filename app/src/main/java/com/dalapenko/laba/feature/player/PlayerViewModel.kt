package com.dalapenko.laba.feature.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dalapenko.laba.core.data.BookRepository
import com.dalapenko.laba.core.data.ProgressRepository
import com.dalapenko.laba.core.database.entity.BookEntity
import com.dalapenko.laba.core.database.entity.ProgressEntity
import com.dalapenko.laba.core.database.entity.TrackEntity
import com.dalapenko.laba.core.media.PlaybackController
import com.dalapenko.laba.core.media.PlaybackError
import com.dalapenko.laba.core.media.PlaybackPreparer
import com.dalapenko.laba.core.media.PlayerState
import com.dalapenko.laba.core.media.SleepTimerController
import com.dalapenko.laba.core.media.SleepTimerState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PlayerEvent {
    data object ClosePlayer : PlayerEvent
    data class TrackUnavailable(val trackName: String?, val trackIndex: Int) : PlayerEvent
}

data class PlayerUiState(
    val book: BookEntity? = null,
    val tracks: List<TrackEntity> = emptyList(),
    val playerState: PlayerState = PlayerState(),
    val isLoading: Boolean = true,
    val isInitializing: Boolean = true, // True until correct initial state is set
    val sleepTimerState: SleepTimerState = SleepTimerState(),
)

/** Navigation arguments, bundled so the [PlayerViewModel] constructor stays under detekt's parameter-count limit. */
data class PlayerNavArgs(val bookId: Long, val autoPlay: Boolean)

class PlayerViewModel(
    private val navArgs: PlayerNavArgs,
    private val repository: BookRepository,
    private val progressRepository: ProgressRepository,
    private val playbackController: PlaybackController,
    private val playbackPreparer: PlaybackPreparer,
    private val sleepTimerController: SleepTimerController,
) : ViewModel() {

    private val bookId: Long get() = navArgs.bookId
    private val autoPlay: Boolean get() = navArgs.autoPlay

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        loadBook()
        collectPlayerState()
        observePlaybackErrors()
        collectSleepTimerState()
    }

    private fun loadBook() {
        viewModelScope.launch {
            val result = repository.getBookWithTracks(bookId)
            if (result != null) {
                val (book, tracks) = result
                _uiState.value = _uiState.value.copy(
                    book = book,
                    tracks = tracks,
                    isLoading = false,
                    isInitializing = false,
                )
                setupPlaylist(book, tracks)
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isInitializing = false,
                )
            }
        }
    }

    private suspend fun setupPlaylist(book: BookEntity, tracks: List<TrackEntity>) {
        playbackPreparer.setupPlayback(bookId, book, tracks, autoPlay = autoPlay)
    }

    private fun collectPlayerState() {
        viewModelScope.launch {
            playbackController.playerState.collect { state ->
                // Guard 1: Only accept states when this book is the active book
                if (playbackController.currentBookId.value != bookId) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(
                            TAG,
                            "Ignoring state update - different book is active " +
                                "(current=${playbackController.currentBookId.value}, this=$bookId)",
                        )
                    }
                    return@collect
                }

                // Guard 2: Validate track index is within our track range
                // This catches race conditions where currentBookId hasn't updated yet
                val tracks = _uiState.value.tracks
                if (tracks.isNotEmpty() && state.currentMediaItemIndex >= tracks.size) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(
                            TAG,
                            "Ignoring state update - track index ${state.currentMediaItemIndex} " +
                                "exceeds our track count ${tracks.size}",
                        )
                    }
                    return@collect
                }

                _uiState.value = _uiState.value.copy(playerState = state)

                // Continuously update snapshot to ensure we always have the latest state
                // This protects against race conditions even if switching happens mid-update
                playbackController.updateBookSnapshot(bookId, state)

                checkCompletion(state)
            }
        }
    }

    private fun checkCompletion(state: PlayerState) {
        val tracks = _uiState.value.tracks
        if (tracks.isEmpty()) return
        val isLastTrack = state.currentMediaItemIndex >= tracks.lastIndex
        val nearEnd = state.durationMs > 0 &&
            state.currentPositionMs >= state.durationMs - COMPLETION_THRESHOLD_MS
        if (isLastTrack && nearEnd && !state.isPlaying) {
            viewModelScope.launch { saveProgressInternal(forceCompleted = true) }
        }
    }

    fun togglePlayPause() {
        if (playbackController.playerState.value.isPlaying) {
            playbackController.pause()
        } else {
            playbackController.play()
        }
    }

    fun seekTo(positionMs: Long) {
        playbackController.seekTo(positionMs)
    }

    fun seekBack() {
        playbackController.seekBack()
    }

    fun seekForward() {
        playbackController.seekForward()
    }

    fun skipToTrack(index: Int) {
        playbackController.seekToTrack(index)
    }

    fun setSpeed(speed: Float) {
        playbackController.setSpeed(speed.coerceIn(PLAYBACK_SPEED_MIN, PLAYBACK_SPEED_MAX))
    }

    fun startSleepTimerFixed(durationMs: Long) {
        sleepTimerController.startFixedDuration(durationMs)
    }

    fun startSleepTimerEndOfChapter() {
        sleepTimerController.startEndOfChapter()
    }

    fun cancelSleepTimer() {
        sleepTimerController.cancel()
    }

    private fun collectSleepTimerState() {
        viewModelScope.launch {
            sleepTimerController.state.collect { state ->
                _uiState.value = _uiState.value.copy(sleepTimerState = state)
            }
        }
    }

    private fun saveProgressInternal(forceCompleted: Boolean = false) {
        // ALWAYS use snapshot if available (continuously updated by collectPlayerState)
        // Fall back to local _uiState.playerState (never use global playbackController.playerState)
        val snapshot = playbackController.getBookSnapshot(bookId)
        val state = snapshot ?: _uiState.value.playerState
        val tracks = _uiState.value.tracks

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            val source = if (snapshot != null) "snapshot" else "local UI state"
            Log.d(
                TAG,
                "Saving progress for book $bookId from $source: " +
                    "position=${state.currentPositionMs}ms, " +
                    "track=${state.currentMediaItemIndex}, " +
                    "forceCompleted=$forceCompleted",
            )
        }

        if (tracks.isEmpty()) return

        val currentIndex = state.currentMediaItemIndex.coerceIn(0, tracks.lastIndex)
        val currentTrack = tracks[currentIndex]

        // Sum durations of all tracks before the current one
        val completedTracksMs = tracks.take(currentIndex).sumOf { it.durationMs }

        val isCompleted = forceCompleted || (
            currentIndex == tracks.lastIndex &&
                state.durationMs > 0 &&
                state.currentPositionMs >= state.durationMs - COMPLETION_THRESHOLD_MS
            )

        viewModelScope.launch {
            progressRepository.saveProgress(
                ProgressEntity(
                    bookId = bookId,
                    lastTrackId = currentTrack.id,
                    lastPositionMs = state.currentPositionMs,
                    completedTracksMs = completedTracksMs,
                    lastUpdated = System.currentTimeMillis(),
                    isCompleted = isCompleted,
                    playbackSpeed = state.playbackSpeed,
                ),
            )

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(
                    TAG,
                    "Progress saved to database for book $bookId: " +
                        "position=${state.currentPositionMs}ms, trackId=${currentTrack.id}",
                )
            }
        }
    }

    private fun observePlaybackErrors() {
        viewModelScope.launch {
            playbackController.playbackError.collect { error ->
                when (error) {
                    is PlaybackError.TrackUnavailable -> {
                        // Get track name from the UI state
                        val trackName = _uiState.value.tracks
                            .getOrNull(error.trackIndex)?.fileName
                        _events.emit(PlayerEvent.TrackUnavailable(trackName, error.trackIndex))
                    }
                    PlaybackError.BookUnavailable -> {
                        // All tracks unavailable or can't recover - mark book unavailable
                        repository.setBookAvailability(bookId, false)
                        playbackController.stop()
                        _events.emit(PlayerEvent.ClosePlayer)
                    }
                }
            }
        }
    }
}

private const val COMPLETION_THRESHOLD_MS = 1000L
private const val PLAYBACK_SPEED_MIN = 0.5f
private const val PLAYBACK_SPEED_MAX = 2.0f

private const val TAG = "PlayerViewModel"
