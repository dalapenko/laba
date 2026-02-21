package com.dalapenko.laba.core.media

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.core.net.toUri

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

class PlaybackController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var isConnecting = false

    private val _currentBookId = MutableStateFlow<Long?>(null)
    val currentBookId: StateFlow<Long?> = _currentBookId.asStateFlow()

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateState()
            if (isPlaying) startPositionPolling()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateState()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            updateState()
        }
    }

    /** Connect (or reconnect) to an existing or new MediaSession. Safe to call multiple times. */
    fun connect() {
        if (isConnecting || controller?.isConnected == true) return
        isConnecting = true

        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                try {
                    val mc = future.get()
                    controller = mc
                    mc.addListener(listener)
                    updateState()
                } catch (_: Exception) {
                    // Service not running yet — will be started lazily when setPlaylist is called
                } finally {
                    isConnecting = false
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    fun play() {
        controller?.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        updateState()
    }

    fun seekToTrack(index: Int, positionMs: Long = 0L) {
        controller?.seekTo(index, positionMs)
        updateState()
    }

    fun setPlaylist(items: List<PlaylistItem>, bookId: Long) {
        _currentBookId.value = bookId
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

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
    }

    fun release() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture ?: return)
        controller = null
    }

    private fun updateState() {
        val c = controller ?: return
        _playerState.value = PlayerState(
            isPlaying = c.isPlaying,
            currentPositionMs = c.currentPosition,
            durationMs = c.duration.coerceAtLeast(0),
            currentMediaItemIndex = c.currentMediaItemIndex,
            playbackSpeed = c.playbackParameters.speed,
            isReady = c.playbackState == Player.STATE_READY || c.playbackState == Player.STATE_BUFFERING,
        )
    }

    private fun startPositionPolling() {
        scope.launch {
            while (controller?.isPlaying == true) {
                updateState()
                delay(250)
            }
        }
    }
}
