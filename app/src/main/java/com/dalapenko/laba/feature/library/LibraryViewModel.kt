package com.dalapenko.laba.feature.library

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dalapenko.laba.core.media.PlaybackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlaybackStatus(val activeBookId: Long?, val isPlaying: Boolean)

class LibraryViewModel(
    private val repository: BookRepository,
    private val scanner: FolderScanner,
    private val playbackController: PlaybackController,
) : ViewModel() {

    val playbackStatus: StateFlow<PlaybackStatus> = combine(
        playbackController.currentBookId,
        playbackController.playerState,
    ) { bookId, state ->
        PlaybackStatus(activeBookId = bookId, isPlaying = state.isPlaying)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackStatus(null, false))

    val books: StateFlow<List<BookWithProgress>> = repository.observeAllBooksWithProgress()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    }

    fun togglePlayPause() {
        if (playbackController.playerState.value.isPlaying) {
            playbackController.pause()
        } else {
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
            } catch (e: SecurityException) {
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
            } catch (e: SecurityException) {
                _scanResult.value = ScanResult.PermissionError
            } finally {
                _isScanning.value = false
            }
        }
    }

    // ── Resync ────────────────────────────────────────────────────────────────

    /** Pull-to-refresh: re-scan all books and update their metadata. */
    fun resyncAll() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                repository.getAllBooks().forEach { book ->
                    val updated = scanner.rescanBookMeta(book) ?: return@forEach
                    repository.updateBookMeta(updated)
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
            repository.deleteBook(bookWithProgress.book)
            scanner.deleteCoverFile(bookWithProgress.book.coverUri)
        }
    }

    fun clearScanResult() {
        _scanResult.value = null
    }
}

sealed interface ScanResult {
    data class Success(val title: String) : ScanResult
    data object Empty : ScanResult
    data object PermissionError : ScanResult
}
