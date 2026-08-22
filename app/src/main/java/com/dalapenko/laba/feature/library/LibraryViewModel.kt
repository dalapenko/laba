package com.dalapenko.laba.feature.library

import android.content.ContentResolver
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dalapenko.laba.core.data.BookRepository
import com.dalapenko.laba.core.data.BookWithProgress
import com.dalapenko.laba.core.data.ProgressRepository
import com.dalapenko.laba.core.database.entity.TrackEntity
import com.dalapenko.laba.core.media.PlaybackController
import com.dalapenko.laba.core.media.PlaybackPreparer
import com.dalapenko.laba.feature.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaybackStatus(
    val activeBookId: Long?,
    val isPlaying: Boolean,
    val isMediaLoaded: Boolean,
)

enum class ContinueBookStatus {
    CONTINUE,
    PLAYING,
    PAUSED,
    LAST_PLAYED,
}

data class ContinueBookUiState(
    val book: BookWithProgress,
    val status: ContinueBookStatus,
)

class LibraryViewModel(
    private val repository: BookRepository,
    private val progressRepository: ProgressRepository,
    private val scanner: FolderScanner,
    private val playbackController: PlaybackController,
    private val playbackPreparer: PlaybackPreparer,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<LibraryEvent> = _events.asSharedFlow()

    private val lastPlayedBookId: StateFlow<Long?> = progressRepository.observeLastPlayedBookId()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val playbackStatus: StateFlow<PlaybackStatus> = combine(
        playbackController.currentBookId,
        playbackController.playerState,
        lastPlayedBookId,
    ) { mediaBookId, state, lastPlayed ->
        val isLoaded = mediaBookId != null
        PlaybackStatus(
            activeBookId = mediaBookId ?: lastPlayed,
            isPlaying = isLoaded && state.isPlaying,
            isMediaLoaded = isLoaded,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(
            stopTimeoutMillis = 5000,
        ),
        initialValue = PlaybackStatus(
            activeBookId = null,
            isPlaying = false,
            isMediaLoaded = false,
        ),
    )

    // Tracks for the currently-playing book; used to compute live progress fraction.
    private val _currentBookTracks = MutableStateFlow<Pair<Long, List<TrackEntity>>?>(null)

    // Single shared subscription to avoid two parallel Room queries for the same data.
    private val allBooksFlow = repository.observeAllBooksWithProgress()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    val books: StateFlow<List<BookWithProgress>> = combine(
        allBooksFlow,
        playbackController.currentBookId,
        playbackController.playerState,
        _currentBookTracks,
    ) { dbBooks, activeBookId, playerState, tracksForBook ->
        if (activeBookId == null) return@combine dbBooks
        val tracks = tracksForBook?.takeIf { it.first == activeBookId }?.second
            ?: return@combine dbBooks
        if (tracks.isEmpty()) return@combine dbBooks
        dbBooks.map { item ->
            if (item.book.id != activeBookId) {
                item
            } else {
                val currentIndex = playerState.currentMediaItemIndex.coerceIn(0, tracks.lastIndex)
                val completedTracksMs = tracks.take(currentIndex).sumOf { it.durationMs }
                val absolute = completedTracksMs + playerState.currentPositionMs
                val liveFraction = if (item.book.totalDurationMs > 0) {
                    (absolute.toFloat() / item.book.totalDurationMs).coerceIn(0f, 1f)
                } else {
                    0f
                }
                item.copy(progressFraction = liveFraction)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val continueBook: StateFlow<ContinueBookUiState?> = combine(
        books,
        playbackController.currentBookId,
        playbackController.playerState,
        lastPlayedBookId,
    ) { bookItems, activeBookId, playerState, fallbackBookId ->
        val activeBook = activeBookId?.let { currentId ->
            bookItems.firstOrNull { it.book.id == currentId }
        }

        when {
            activeBook != null -> {
                ContinueBookUiState(
                    book = activeBook,
                    status = if (playerState.isPlaying) ContinueBookStatus.PLAYING else ContinueBookStatus.PAUSED,
                )
            }

            fallbackBookId != null -> {
                val lastPlayedBook = bookItems.firstOrNull { it.book.id == fallbackBookId }
                    ?: return@combine null
                ContinueBookUiState(
                    book = lastPlayedBook,
                    status = if (lastPlayedBook.isAvailable) {
                        ContinueBookStatus.CONTINUE
                    } else {
                        ContinueBookStatus.LAST_PLAYED
                    },
                )
            }

            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val sortOption: StateFlow<LibrarySortOption> = settingsRepository.librarySortOption
        .stateIn(viewModelScope, SharingStarted.Eagerly, LibrarySortOption.ADDED_AT_DESC)

    val libraryBooks: StateFlow<List<BookWithProgress>> = combine(
        books,
        continueBook,
        sortOption,
    ) { bookItems, continueItem, selectedSortOption ->
        val sortedBooks = sortLibraryBooks(bookItems, selectedSortOption)
        val continueBookId = continueItem?.book?.book?.id
        if (continueBookId == null) sortedBooks else sortedBooks.filterNot { it.book.id == continueBookId }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** True while pull-to-refresh resync is running. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _scanResult = MutableStateFlow<ScanResult?>(null)
    val scanResult: StateFlow<ScanResult?> = _scanResult.asStateFlow()

    init {
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
        // Purge in-memory snapshots for books that no longer exist in the library.
        // Runs on every DB change (including deletes) so the map never grows unboundedly.
        viewModelScope.launch {
            allBooksFlow.collect { bookList ->
                val activeIds = bookList.map { it.book.id }.toSet()
                playbackController.cleanupOldSnapshots(activeIds)
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

    fun setLibrarySortOption(option: LibrarySortOption) {
        viewModelScope.launch { settingsRepository.setLibrarySortOption(option) }
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

            playbackPreparer.setupPlayback(bookId, book, tracks, autoPlay = true)
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    private fun ContentResolver.acquireUriPermission(uri: Uri) {
        try {
            takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {
            takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun onFolderPicked(uri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                contentResolver.acquireUriPermission(uri)
                val scannedBooks = scanner.scanFolderTree(uri)
                if (scannedBooks.isEmpty()) {
                    _scanResult.value = ScanResult.Empty
                } else {
                    var addedCount = 0
                    val firstTitle = scannedBooks.first().title
                    for (scanned in scannedBooks) {
                        val book = scanner.toBookEntity(scanned)
                        val tracks = scanner.toTrackEntities(scanned)
                        val id = repository.addBookIfNew(book, tracks)
                        if (id != null) addedCount++
                    }
                    _scanResult.value = when {
                        addedCount == 0 -> ScanResult.Duplicate
                        else -> ScanResult.Success(firstTitle, addedCount)
                    }
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
                contentResolver.acquireUriPermission(uri)
                val scanned = scanner.scanSingleFile(uri)
                if (scanned == null) {
                    _scanResult.value = ScanResult.Empty
                } else {
                    val book = scanner.toBookEntity(scanned)
                    val tracks = scanner.toTrackEntities(scanned)
                    val id = repository.addBookIfNew(book, tracks)
                    _scanResult.value = if (id != null) ScanResult.Success(scanned.title) else ScanResult.Duplicate
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

    fun deleteBook(bookWithProgress: BookWithProgress, deleteFiles: Boolean = false) {
        viewModelScope.launch {
            if (playbackController.currentBookId.value == bookWithProgress.book.id) {
                playbackController.stop()
            }
            repository.deleteBook(bookWithProgress.book)
            scanner.deleteCoverFile(bookWithProgress.book.coverUri)
            if (deleteFiles) {
                val deleted = scanner.deleteBookFiles(bookWithProgress.book.rootFolderUri)
                if (deleted) {
                    _events.tryEmit(LibraryEvent.FilesDeletedSuccessfully)
                } else {
                    _events.tryEmit(LibraryEvent.FilesDeleteFailed)
                }
            }
        }
    }

    fun onUnavailableBookClicked() {
        _events.tryEmit(LibraryEvent.FileNotAvailable)
    }

    fun clearScanResult() {
        _scanResult.update { null }
    }
}

sealed interface LibraryEvent {
    data object FileNotAvailable : LibraryEvent
    data object FilesDeletedSuccessfully : LibraryEvent
    data object FilesDeleteFailed : LibraryEvent
}

sealed interface ScanResult {
    data class Success(val title: String, val count: Int = 1) : ScanResult
    data object Empty : ScanResult
    data object Duplicate : ScanResult
    data object PermissionError : ScanResult
}
