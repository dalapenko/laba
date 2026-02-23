package com.dalapenko.laba.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dalapenko.laba.core.database.entity.BookEntity
import com.dalapenko.laba.core.database.entity.ProgressEntity
import com.dalapenko.laba.core.database.entity.TrackEntity
import com.dalapenko.laba.core.media.PlaybackController
import com.dalapenko.laba.core.media.PlaybackError
import com.dalapenko.laba.core.media.PlayerState
import com.dalapenko.laba.core.media.PlaylistItem
import com.dalapenko.laba.feature.library.BookRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.util.Log

sealed interface PlayerEvent {
    data object ClosePlayer : PlayerEvent
    data class TrackUnavailable(val trackName: String?, val trackIndex: Int) : PlayerEvent
}

data class PlayerUiState(
    val book: BookEntity? = null,
    val tracks: List<TrackEntity> = emptyList(),
    val playerState: PlayerState = PlayerState(),
    val isLoading: Boolean = true,
    val isInitializing: Boolean = true,  // True until correct initial state is set
)

class PlayerViewModel(
    private val bookId: Long,
    private val autoPlay: Boolean,
    private val repository: BookRepository,
    private val playbackController: PlaybackController,
    private val autoSaveIntervalMs: Long = 5_000L,  // Configurable for testing
) : ViewModel() {

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    val playerState: StateFlow<PlayerState> = playbackController.playerState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlayerState())

    init {
        loadBook()
        collectPlayerState()
        startProgressAutoSave()
        observePlaybackErrors()
    }

    private fun loadBook() {
        viewModelScope.launch {
            val result = repository.getBookWithTracks(bookId)
            if (result != null) {
                val (book, tracks) = result
                
                // Only set initial state if we're switching to a different book
                // If same book is already playing, we keep its current state
                if (playbackController.currentBookId.value != bookId) {
                    setInitialStateForBook(tracks)
                }
                
                // Mark as initialized and not loading
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

    private suspend fun setInitialStateForBook(tracks: List<TrackEntity>) {
        val progress = repository.getProgress(bookId)
        val targetTrackIndex = if (progress != null && !progress.isCompleted) {
            tracks.indexOfFirst { it.id == progress.lastTrackId }.takeIf { it >= 0 }
        } else null

        if (targetTrackIndex != null && progress != null) {
            val targetTrack = tracks[targetTrackIndex]
            playbackController.setInitialState(
                position = progress.lastPositionMs,
                duration = targetTrack.durationMs,
                trackIndex = targetTrackIndex,
                speed = progress.playbackSpeed.coerceIn(0.5f, 2.0f)
            )
        } else {
            val firstTrack = tracks.firstOrNull()
            playbackController.setInitialState(
                position = 0L,
                duration = firstTrack?.durationMs ?: 0L,
                trackIndex = 0,
                speed = 1.0f
            )
        }
    }

    private suspend fun setupPlaylist(book: BookEntity, tracks: List<TrackEntity>) {
        // Capture the current book's state before switching to prevent race conditions
        // This ensures we save the correct final position even if state updates arrive late
        playbackController.captureCurrentBookState()
        
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Setting up playlist for book $bookId (${book.title})")
        }
        
        // Book is already loaded in the player (e.g. user navigated back and reopened it).
        // Don't reset the playlist or touch playback state — just let the UI reflect what's playing.
        if (playbackController.currentBookId.value == bookId) {
            Log.d(TAG, "Book $bookId is already loaded, skipping playlist setup")
            return
        }

        // Build and set the playlist
        val items = tracks.map { track ->
            PlaylistItem(
                uri = track.fileUri,
                title = track.fileName,
                artist = book.author,
                artworkUri = book.coverUri,
            )
        }
        playbackController.setPlaylist(items, bookId)

        // Restore position and speed from saved progress
        val progress = repository.getProgress(bookId)
        if (progress != null && !progress.isCompleted) {
            val trackIndex = tracks.indexOfFirst { it.id == progress.lastTrackId }
            if (trackIndex >= 0) {
                playbackController.seekToTrack(trackIndex, progress.lastPositionMs)
            } else {
                // Track not found, unlock state updates
                playbackController.unlockStateUpdates()
            }
            playbackController.setSpeed(progress.playbackSpeed.coerceIn(0.5f, 2.0f))
        } else {
            // No saved progress or book was previously completed — start from beginning.
            // Reset isCompleted so LibraryScreen immediately shows progress bar instead of "Completed".
            if (progress != null) {
                repository.saveProgress(
                    progress.copy(
                        isCompleted = false,
                        lastPositionMs = 0L,
                        completedTracksMs = 0L,
                        lastTrackId = tracks.first().id,
                        lastUpdated = System.currentTimeMillis(),
                    )
                )
            }
            playbackController.unlockStateUpdates()
        }
        
        if (autoPlay) playbackController.play()
    }

    private fun collectPlayerState() {
        viewModelScope.launch {
            playbackController.playerState.collect { state ->
                // Guard 1: Only accept states when this book is the active book
                if (playbackController.currentBookId.value != bookId) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "Ignoring state update - different book is active " +
                            "(current=${playbackController.currentBookId.value}, this=$bookId)")
                    }
                    return@collect
                }
                
                // Guard 2: Validate track index is within our track range
                // This catches race conditions where currentBookId hasn't updated yet
                val tracks = _uiState.value.tracks
                if (tracks.isNotEmpty() && state.currentMediaItemIndex >= tracks.size) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "Ignoring state update - track index ${state.currentMediaItemIndex} " +
                            "exceeds our track count ${tracks.size}")
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
        val nearEnd = state.durationMs > 0 && state.currentPositionMs >= state.durationMs - 1000
        if (isLastTrack && nearEnd && !state.isPlaying) {
            viewModelScope.launch { saveProgressInternal(forceCompleted = true) }
        }
    }

    private fun startProgressAutoSave() {
        // Skip auto-save if interval is 0 or negative (for testing)
        if (autoSaveIntervalMs <= 0) return
        
        viewModelScope.launch {
            while (true) {
                delay(autoSaveIntervalMs)
                if (_uiState.value.tracks.isNotEmpty()) {
                    saveProgressInternal()
                }
            }
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
        playbackController.setSpeed(speed.coerceIn(0.5f, 2.0f))
    }

    private fun saveProgressInternal(forceCompleted: Boolean = false) {
        // ALWAYS use snapshot if available (continuously updated by collectPlayerState)
        // Fall back to local _uiState.playerState (never use global playbackController.playerState)
        val snapshot = playbackController.getBookSnapshot(bookId)
        val state = snapshot ?: _uiState.value.playerState
        val tracks = _uiState.value.tracks
        
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            val source = if (snapshot != null) "snapshot" else "local UI state"
            Log.d(TAG, "Saving progress for book $bookId from $source: " +
                "position=${state.currentPositionMs}ms, " +
                "track=${state.currentMediaItemIndex}, " +
                "forceCompleted=$forceCompleted")
        }
        
        if (tracks.isEmpty()) return

        val currentIndex = state.currentMediaItemIndex.coerceIn(0, tracks.lastIndex)
        val currentTrack = tracks[currentIndex]

        // Sum durations of all tracks before the current one
        val completedTracksMs = tracks.take(currentIndex).sumOf { it.durationMs }

        val isCompleted = forceCompleted || (
            currentIndex == tracks.lastIndex &&
                state.durationMs > 0 &&
                state.currentPositionMs >= state.durationMs - 1000
            )

        viewModelScope.launch {
            repository.saveProgress(
                ProgressEntity(
                    bookId = bookId,
                    lastTrackId = currentTrack.id,
                    lastPositionMs = state.currentPositionMs,
                    completedTracksMs = completedTracksMs,
                    lastUpdated = System.currentTimeMillis(),
                    isCompleted = isCompleted,
                    playbackSpeed = state.playbackSpeed,
                )
            )
            
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Progress saved to database for book $bookId: " +
                    "position=${state.currentPositionMs}ms, trackId=${currentTrack.id}")
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

    override fun onCleared() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "ViewModel cleared for book $bookId, saving final progress")
        }
        saveProgressInternal()
        super.onCleared()
    }
}

private const val TAG = "PlayerViewModel"