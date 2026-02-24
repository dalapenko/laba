package com.dalapenko.laba.core.media

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.core.net.toUri
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val currentMediaItemIndex: Int = 0,
    val playbackSpeed: Float = 1f,
    val isReady: Boolean = false,
)

data class PlaylistItem(
    val uri: String,
    val title: String,
    val artist: String? = null,
    val artworkUri: String? = null,
)

sealed interface PlaybackError {
    data class TrackUnavailable(val trackIndex: Int) : PlaybackError
    data object BookUnavailable : PlaybackError
}

/**
 * Singleton controller managing playback via MediaSessionService.
 *
 * Lifecycle: Connects once at app startup via [com.dalapenko.laba.LabaApp.onCreate],
 * persists until process death.
 * This enables background playback and survives configuration changes.
 *
 * Thread safety: All methods must be called from the main thread.
 */
class PlaybackController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: MediaController? = null
    private var isConnecting = false

    // Track consecutive errors to detect if entire book is unavailable
    private var consecutiveErrors = 0
    private var lastErrorTrackIndex = -1

    // Prevent state updates while loading new playlist to avoid flickering
    private var isLoadingPlaylist = false

    private var pollingJob: Job? = null

    // Store the last known state for each book to prevent race conditions during book switches
    private val bookStateSnapshots = ConcurrentHashMap<Long, PlayerState>()

    // Cached metadata for re-sending to service after reconnect
    private var cachedBookId: Long? = null
    private var cachedTrackIds: LongArray = LongArray(0)
    private var cachedTrackDurations: LongArray = LongArray(0)

    private val _currentBookId = MutableStateFlow<Long?>(null)
    val currentBookId: StateFlow<Long?> = _currentBookId.asStateFlow()

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _playbackError = MutableSharedFlow<PlaybackError>(extraBufferCapacity = 1)
    val playbackError: SharedFlow<PlaybackError> = _playbackError.asSharedFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Reset error counter when playback succeeds
            if (isPlaying) {
                consecutiveErrors = 0
                lastErrorTrackIndex = -1
            }
            updateState()
            if (isPlaying) startPositionPolling()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Auto-unlock when Media3 is ready (after prepare completes)
            // This ensures we don't show intermediate states during loading
            if (playbackState == Player.STATE_READY && isLoadingPlaylist) {
                isLoadingPlaylist = false
            }
            updateState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateState()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            updateState()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION
            ) {
                val trackIndex = controller?.currentMediaItemIndex ?: -1

                // Track consecutive errors to detect if entire book is gone
                if (trackIndex == lastErrorTrackIndex) {
                    consecutiveErrors++
                } else {
                    consecutiveErrors = 1
                    lastErrorTrackIndex = trackIndex
                }

                // If we've had 3+ consecutive errors on different tracks, entire book is likely gone
                if (consecutiveErrors >= 3) {
                    consecutiveErrors = 0
                    _playbackError.tryEmit(PlaybackError.BookUnavailable)
                    return
                }

                if (trackIndex >= 0) {
                    _playbackError.tryEmit(PlaybackError.TrackUnavailable(trackIndex))
                    // Try to recover by moving to previous track's end
                    handleMissingTrack(trackIndex)
                } else {
                    _playbackError.tryEmit(PlaybackError.BookUnavailable)
                }
            }
        }
    }

    /** Connect to an existing or new MediaSession. No-op if already connected or connecting. */
    fun connect() {
        if (controller != null || isConnecting) return
        isConnecting = true

        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener(
            {
                try {
                    val mc = future.get()
                    controller = mc
                    mc.addListener(listener)
                    updateState()

                    // Start connection health monitoring
                    startConnectionMonitoring()
                } catch (_: Exception) {
                    // Service not running yet — will be started lazily when setPlaylist is called
                } finally {
                    isConnecting = false
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    /**
     * Monitor MediaController connection health and reconnect if disconnected.
     * This prevents saving stale progress when connection is lost.
     */
    private fun startConnectionMonitoring() {
        scope.launch {
            while (true) {
                delay(5_000L) // Check every 5 seconds
                val c = controller
                if (c == null || !c.isConnected) {
                    Log.w(TAG, "MediaController disconnected, attempting reconnect...")
                    controller = null
                    isConnecting = false
                    connect()
                    // Re-send cached metadata to newly connected service
                    sendBookMetadataToService()
                }
            }
        }
    }

    fun play() {
        controller?.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun stop() {
        controller?.run {
            stop()
            clearMediaItems()
        }
        _currentBookId.value = null
        updateState()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        updateState()
    }

    fun seekBack() {
        controller?.seekBack()
        updateState()
    }

    fun seekForward() {
        controller?.seekForward()
        updateState()
    }

    fun seekToTrack(index: Int, positionMs: Long = 0L) {
        controller?.seekTo(index, positionMs)
        // State will auto-unlock when Media3 reaches STATE_READY (see listener)
    }

    fun setInitialState(position: Long, duration: Long, trackIndex: Int, speed: Float) {
        // Lock state updates to prevent Media3 from overwriting this
        isLoadingPlaylist = true
        _playerState.value = PlayerState(
            currentPositionMs = position,
            durationMs = duration,
            currentMediaItemIndex = trackIndex,
            playbackSpeed = speed,
            isReady = false
        )
    }

    fun setPlaylist(items: List<PlaylistItem>, bookId: Long) {
        _currentBookId.value = bookId
        // Note: PlayerState should be set via setInitialState() before calling this
        // Keep isLoadingPlaylist = true to prevent listener updates
        val mediaItems = items.map { item ->
            MediaItem.Builder()
                .setUri(item.uri.toUri())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(item.artist)
                        .apply { item.artworkUri?.let { setArtworkUri(it.toUri()) } }
                        .build()
                )
                .build()
        }
        controller?.run {
            setMediaItems(mediaItems)
            prepare()
        }
    }

    fun setBookMetadata(bookId: Long, trackIds: List<Long>, trackDurations: List<Long>) {
        // Cache locally for re-sending after service reconnect
        cachedBookId = bookId
        cachedTrackIds = trackIds.toLongArray()
        cachedTrackDurations = trackDurations.toLongArray()
        sendBookMetadataToService()
    }

    private fun sendBookMetadataToService() {
        val bId = cachedBookId ?: return
        val c = controller ?: return
        val bundle = Bundle().apply {
            putLong(KEY_BOOK_ID, bId)
            putLongArray(KEY_TRACK_IDS, cachedTrackIds)
            putLongArray(KEY_TRACK_DURATIONS, cachedTrackDurations)
        }
        c.sendCustomCommand(SessionCommand(ACTION_SET_BOOK_METADATA, Bundle.EMPTY), bundle)
    }

    /**
     * Captures the current playback state for the active book.
     * Should be called before switching to a different book to preserve the current book's final state.
     * This prevents race conditions where a new book's initial state overwrites the previous book's state.
     */
    fun captureCurrentBookState() {
        val currentId = _currentBookId.value
        if (currentId == null) {
            Log.d(TAG, "captureCurrentBookState: No active book, skipping snapshot")
            return
        }

        val currentState = _playerState.value.copy()
        bookStateSnapshots[currentId] = currentState

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Captured state for book $currentId: " +
                "position=${currentState.currentPositionMs}ms, " +
                "track=${currentState.currentMediaItemIndex}, " +
                "speed=${currentState.playbackSpeed}x")
        }
    }

    /**
     * Updates the snapshot for a specific book with new state.
     * Called continuously during playback to ensure snapshot is always current.
     *
     * @param bookId The ID of the book to update
     * @param state The new state to store
     */
    fun updateBookSnapshot(bookId: Long, state: PlayerState) {
        bookStateSnapshots[bookId] = state.copy()
    }

    /**
     * Retrieves a previously captured state snapshot for a specific book.
     * Returns null if no snapshot exists (e.g., first time playing the book).
     *
     * @param bookId The ID of the book to retrieve the snapshot for
     * @return The captured PlayerState, or null if no snapshot exists
     */
    fun getBookSnapshot(bookId: Long): PlayerState? {
        val snapshot = bookStateSnapshots[bookId]
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            if (snapshot != null) {
                Log.d(TAG, "Retrieved snapshot for book $bookId: " +
                    "position=${snapshot.currentPositionMs}ms, " +
                    "track=${snapshot.currentMediaItemIndex}")
            } else {
                Log.d(TAG, "No snapshot found for book $bookId")
            }
        }
        return snapshot
    }

    /**
     * Removes snapshots for books that are no longer available.
     * Call this periodically to prevent unbounded memory growth.
     *
     * @param activeBookIds Set of currently available book IDs
     */
    fun cleanupOldSnapshots(activeBookIds: Set<Long>) {
        val removedCount = bookStateSnapshots.keys.retainAll(activeBookIds)
        if (removedCount && Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Cleaned up snapshots, ${bookStateSnapshots.size} remaining")
        }
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
    }

    private fun handleMissingTrack(missingTrackIndex: Int) {
        val c = controller ?: return

        // If this is the first track, try to skip to the next available track
        if (missingTrackIndex == 0) {
            if (c.mediaItemCount > 1) {
                c.seekToNext()
            } else {
                // Only one track and it's missing - stop playback
                c.stop()
            }
            return
        }

        // For other tracks, seek to the end of the previous track and pause
        val previousTrackIndex = missingTrackIndex - 1
        if (previousTrackIndex >= 0 && previousTrackIndex < c.mediaItemCount) {
            // Seek to previous track
            c.seekTo(previousTrackIndex, 0L)
            // Get duration of previous track and seek to near the end
            scope.launch {
                delay(100) // Wait for media to load
                val duration = c.duration
                if (duration > 0) {
                    // Seek to 1 second before the end, or the end if track is shorter
                    val seekPosition = (duration - 1000).coerceAtLeast(0)
                    c.seekTo(previousTrackIndex, seekPosition)
                }
                c.pause()
                updateState()
            }
        } else {
            // Fallback: just stop playback
            c.stop()
        }
    }

    private fun updateState() {
        // Don't update state from MediaController while we're setting up a new playlist
        // This prevents flickering from initial state -> media3 default -> correct state
        if (isLoadingPlaylist) return

        val c = controller ?: return
        val duration = c.duration.coerceAtLeast(0)
        _playerState.value = PlayerState(
            isPlaying = c.isPlaying,
            currentPositionMs = if (duration > 0) c.currentPosition else 0L,
            durationMs = duration,
            currentMediaItemIndex = c.currentMediaItemIndex,
            playbackSpeed = c.playbackParameters.speed,
            isReady = c.playbackState == Player.STATE_READY || c.playbackState == Player.STATE_BUFFERING,
        )
    }

    private fun startPositionPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (controller?.isPlaying == true) {
                updateState()
                delay(250)
            }
        }
    }
}

private const val ACTION_SET_BOOK_METADATA = "com.dalapenko.laba.SET_BOOK_METADATA"
private const val KEY_BOOK_ID = "bookId"
private const val KEY_TRACK_IDS = "trackIds"
private const val KEY_TRACK_DURATIONS = "trackDurations"
private const val TAG = "PlaybackController"
