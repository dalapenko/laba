package com.dalapenko.laba.feature.library

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dalapenko.laba.core.database.entity.TrackEntity
import com.dalapenko.laba.core.media.PlaybackController
import com.dalapenko.laba.core.media.PlaylistItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlaybackStatus(
    val activeBookId: Long?,
    val isPlaying: Boolean,
    val isMediaLoaded: Boolean,
)

class LibraryViewModel(
    private val repository: BookRepository,
    private val scanner: FolderScanner,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<LibraryEvent> = _events.asSharedFlow()

    private val _lastPlayedBookId = MutableStateFlow<Long?>(null)

    val playbackStatus: StateFlow<PlaybackStatus> = combine(
        playbackController.currentBookId,
        playbackController.playerState,
        _lastPlayedBookId,
    ) { mediaBookId, state, lastPlayed ->
        val isLoaded = mediaBookId != null
        PlaybackStatus(
            activeBookId = mediaBookId ?: lastPlayed,
            isPlaying = isLoaded && state.isPlaying,
            isMediaLoaded = isLoaded,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackStatus(null, false, false))

    // Tracks for the currently-playing book; used to compute live progress fraction.
    private val _currentBookTracks = MutableStateFlow<Pair<Long, List<TrackEntity>>?>(null)

    val books: StateFlow<List<BookWithProgress>> = combine(
        repository.observeAllBooksWithProgress(),
        playbackController.currentBookId,
        playbackController.playerState,
        _currentBookTracks,
    ) { dbBooks, activeBookId, playerState, tracksForBook ->
        if (activeBookId == null) return@combine dbBooks
        val tracks = tracksForBook?.takeIf { it.first == activeBookId }?.second
            ?: return@combine dbBooks
        if (tracks.isEmpty()) return@combine dbBooks
        dbBooks.map { item ->
            if (item.book.id != activeBookId) item
            else {
                val currentIndex = playerState.currentMediaItemIndex.coerceIn(0, tracks.lastIndex)
                val completedTracksMs = tracks.take(currentIndex).sumOf { it.durationMs }
                val absolute = completedTracksMs + playerState.currentPositionMs
                val liveFraction = if (item.book.totalDurationMs > 0)
                    (absolute.toFloat() / item.book.totalDurationMs).coerceIn(0f, 1f)
                else 0f
                item.copy(progressFraction = liveFraction)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** True while pull-to-refresh resync is running. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _scanResult = MutableStateFlow<ScanResult?>(null)
    val scanResult: StateFlow<ScanResult?> = _scanResult.asStateFlow()

    init {
        viewModelScope.launch { _lastPlayedBookId.value = repository.getLastPlayedBookId() }
        // Silently fill in covers for books added before cover extraction existed
        viewModelScope.launch { resyncMissingCovers() }
        viewModelScope.launch { repository.recheckAllAvailability(scanner) }
        // Keep track list in sync with whatever book is currently loaded in the player.
        // This drives the live progress fraction in `books` without requiring DB writes.
        viewModelScope.launch {
            playbackController.currentBookId.collect { bookId ->
                if (bookId != null && _currentBookTracks.value?.first != bookId) {
                    val result = repository.getBookWithTracks(bookId)
                    if (result != null) _currentBookTracks.value = bookId to result.second
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

    fun prepareAndPlay(bookId: Long) {
        viewModelScope.launch {
            val bookCheck = repository.getBookById(bookId)
            if (bookCheck != null && !bookCheck.isAvailable) {
                _events.tryEmit(LibraryEvent.FileNotAvailable)
                return@launch
            }
            if (bookCheck != null && !scanner.isBookAvailable(bookCheck.rootFolderUri)) {
                repository.setBookAvailability(bookId, false)
                _events.tryEmit(LibraryEvent.FileNotAvailable)
                return@launch
            }

            if (playbackController.currentBookId.value == bookId) {
                togglePlayPause()
                return@launch
            }

            val result = repository.getBookWithTracks(bookId) ?: return@launch
            val (book, tracks) = result
            _currentBookTracks.value = bookId to tracks

            // Pre-populate playerState from saved progress BEFORE setPlaylist so the
            // isLoadingPlaylist lock prevents Media3's zero-state from overwriting it,
            // keeping the library progress bar smooth (same technique as PlayerViewModel).
            val progress = repository.getProgress(bookId)
            val savedTrackIndex = if (progress != null && !progress.isCompleted)
                tracks.indexOfFirst { it.id == progress.lastTrackId }.takeIf { it >= 0 }
            else null

            if (savedTrackIndex != null && progress != null) {
                playbackController.setInitialState(
                    position = progress.lastPositionMs,
                    duration = tracks[savedTrackIndex].durationMs,
                    trackIndex = savedTrackIndex,
                    speed = progress.playbackSpeed.coerceIn(0.5f, 2.0f),
                )
            } else {
                playbackController.setInitialState(
                    position = 0L,
                    duration = tracks.firstOrNull()?.durationMs ?: 0L,
                    trackIndex = 0,
                    speed = 1.0f,
                )
            }

            val items = tracks.map { track ->
                PlaylistItem(
                    uri = track.fileUri,
                    title = track.fileName,
                    artist = book.author,
                    artworkUri = book.coverUri,
                )
            }
            playbackController.setPlaylist(items, bookId)

            if (savedTrackIndex != null && progress != null) {
                playbackController.seekToTrack(savedTrackIndex, progress.lastPositionMs)
                playbackController.setSpeed(progress.playbackSpeed.coerceIn(0.5f, 2.0f))
            }
            playbackController.play()
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    fun onFolderPicked(uri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                val scanned = scanner.scanFolder(uri)
                if (scanned == null || scanned.tracks.isEmpty()) {
                    _scanResult.value = ScanResult.Empty
                } else {
                    val book = scanner.toBookEntity(scanned)
                    val tracks = scanner.toTrackEntities(scanned)
                    repository.addBook(book, tracks)
                    _scanResult.value = ScanResult.Success(scanned.title)
                }
            } catch (_: SecurityException) {
                _scanResult.value = ScanResult.PermissionError
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun onFilePicked(uri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                val scanned = scanner.scanSingleFile(uri)
                if (scanned == null) {
                    _scanResult.value = ScanResult.Empty
                } else {
                    val book = scanner.toBookEntity(scanned)
                    val tracks = scanner.toTrackEntities(scanned)
                    repository.addBook(book, tracks)
                    _scanResult.value = ScanResult.Success(scanned.title)
                }
            } catch (_: SecurityException) {
                _scanResult.value = ScanResult.PermissionError
            } finally {
                _isScanning.value = false
            }
        }
    }

    // ── Resync ────────────────────────────────────────────────────────────────

    /** Pull-to-refresh: re-check availability then re-scan metadata for available books. */
    fun resyncAll() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                // Same lightweight exists() check as app startup — reliable for restoring
                // availability when a file comes back (e.g. undeleted from trash).
                repository.recheckAllAvailability(scanner)
                // Metadata rescan only for books confirmed available by the check above.
                repository.getAllBooks().forEach { book ->
                    if (!book.isAvailable) return@forEach
                    val updated = scanner.rescanBookMeta(book) ?: return@forEach
                    repository.updateBookMeta(updated.copy(isAvailable = true))
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** Called on init: only re-scans books that are missing a cover. */
    private suspend fun resyncMissingCovers() {
        repository.getBooksWithoutCover().forEach { book ->
            val updated = scanner.rescanBookMeta(book) ?: return@forEach
            if (updated.coverUri != null) repository.updateBookMeta(updated)
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    fun deleteBook(bookWithProgress: BookWithProgress) {
        viewModelScope.launch {
            if (playbackController.currentBookId.value == bookWithProgress.book.id) {
                playbackController.stop()
            }
            repository.deleteBook(bookWithProgress.book)
            scanner.deleteCoverFile(bookWithProgress.book.coverUri)
        }
    }

    fun onUnavailableBookClicked() {
        _events.tryEmit(LibraryEvent.FileNotAvailable)
    }

    fun clearScanResult() {
        _scanResult.value = null
    }
}

sealed interface LibraryEvent {
    data object FileNotAvailable : LibraryEvent
}

sealed interface ScanResult {
    data class Success(val title: String) : ScanResult
    data object Empty : ScanResult
    data object PermissionError : ScanResult
}
