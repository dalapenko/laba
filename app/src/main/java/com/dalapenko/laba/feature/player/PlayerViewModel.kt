package com.dalapenko.laba.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dalapenko.laba.core.database.entity.BookEntity
import com.dalapenko.laba.core.database.entity.ProgressEntity
import com.dalapenko.laba.core.database.entity.TrackEntity
import com.dalapenko.laba.core.media.PlaybackController
import com.dalapenko.laba.core.media.PlayerState
import com.dalapenko.laba.core.media.PlaylistItem
import com.dalapenko.laba.feature.library.BookRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlayerUiState(
    val book: BookEntity? = null,
    val tracks: List<TrackEntity> = emptyList(),
    val playerState: PlayerState = PlayerState(),
    val isLoading: Boolean = true,
)

class PlayerViewModel(
    private val bookId: Long,
    private val repository: BookRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    val playerState: StateFlow<PlayerState> = playbackController.playerState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlayerState())

    init {
        loadBook()
        collectPlayerState()
        startProgressAutoSave()
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
                )
                setupPlaylist(book, tracks)
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    private suspend fun setupPlaylist(book: BookEntity, tracks: List<TrackEntity>) {
        playbackController.connect()
        delay(500) // allow MediaController to connect

        // Book is already loaded in the player (e.g. user navigated back and reopened it).
        // Don't reset the playlist or touch playback state — just let the UI reflect what's playing.
        if (playbackController.currentBookId == bookId) return

        val items = tracks.map { track ->
            PlaylistItem(
                uri = track.fileUri,
                title = track.fileName,
                artist = book.author,
                artworkUri = book.coverUri,
            )
        }
        playbackController.setPlaylist(items, bookId)

        val progress = repository.getProgress(bookId)
        if (progress != null && !progress.isCompleted) {
            val trackIndex = tracks.indexOfFirst { it.id == progress.lastTrackId }
            if (trackIndex >= 0) {
                playbackController.seekToTrack(trackIndex, progress.lastPositionMs)
            }
            playbackController.setSpeed(progress.playbackSpeed.coerceIn(0.5f, 2.0f))
        }
        playbackController.play()
    }

    private fun collectPlayerState() {
        viewModelScope.launch {
            playbackController.playerState.collect { state ->
                _uiState.value = _uiState.value.copy(playerState = state)
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
        viewModelScope.launch {
            while (true) {
                delay(5_000)
                if (_uiState.value.tracks.isNotEmpty()) saveProgressInternal()
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

    fun skipToTrack(index: Int) {
        playbackController.seekToTrack(index)
    }

    fun setSpeed(speed: Float) {
        playbackController.setSpeed(speed.coerceIn(0.5f, 2.0f))
    }

    private fun saveProgressInternal(forceCompleted: Boolean = false) {
        val state = playbackController.playerState.value
        val tracks = _uiState.value.tracks
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
        }
    }

    override fun onCleared() {
        super.onCleared()
        saveProgressInternal()
    }
}
