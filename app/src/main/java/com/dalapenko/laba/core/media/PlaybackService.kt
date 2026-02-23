package com.dalapenko.laba.core.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.dalapenko.laba.MainActivity
import com.dalapenko.laba.R
import com.dalapenko.laba.core.database.entity.ProgressEntity
import com.dalapenko.laba.core.work.ProgressSaveWorker
import com.dalapenko.laba.feature.library.BookRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject

class PlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_SEEK_BACK = "com.dalapenko.laba.SEEK_BACK"
        const val ACTION_SEEK_FORWARD = "com.dalapenko.laba.SEEK_FORWARD"
        const val SEEK_INCREMENT_MS = 10_000L
        private const val PERIODIC_SAVE_INTERVAL_MS = 3_000L // Save every 3 seconds
        private const val TAG = "PlaybackService"
    }

    private val repository: BookRepository by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaSession: MediaSession? = null
    private var periodicSaveJob: Job? = null
    
    // Track metadata for progress calculation
    private var currentBookId: Long? = null
    private var trackDurations: List<Long> = emptyList()
    private var trackIds: List<Long> = emptyList()

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()
        
        // Add listener for progress tracking
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startPeriodicSave()
                } else {
                    stopPeriodicSave()
                    // Save immediately when playback stops
                    saveProgressImmediately()
                }
            }
            
            override fun onEvents(player: Player, events: Player.Events) {
                // Save on significant events to minimize data loss
                if (events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                    events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    saveProgressImmediately()
                }
            }
        })

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .setCallback(PlaybackSessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession
    
    /**
     * Set metadata for the current book. Called by PlaybackController when playlist changes.
     * This allows the service to calculate completedTracksMs.
     */
    fun setBookMetadata(bookId: Long, trackIds: List<Long>, trackDurations: List<Long>) {
        this.currentBookId = bookId
        this.trackIds = trackIds
        this.trackDurations = trackDurations
        
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Book metadata set: bookId=$bookId, tracks=${trackIds.size}")
        }
    }
    
    /**
     * Start periodic progress saving while playback is active.
     * Saves every 3 seconds to minimize data loss on process death.
     */
    private fun startPeriodicSave() {
        // Cancel existing job if any
        periodicSaveJob?.cancel()
        
        periodicSaveJob = scope.launch {
            while (true) {
                delay(PERIODIC_SAVE_INTERVAL_MS)
                saveProgressImmediately()
            }
        }
        
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Started periodic progress saving (${PERIODIC_SAVE_INTERVAL_MS}ms interval)")
        }
    }
    
    /**
     * Stop periodic progress saving.
     */
    private fun stopPeriodicSave() {
        periodicSaveJob?.cancel()
        periodicSaveJob = null
        
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Stopped periodic progress saving")
        }
    }
    
    /**
     * Save progress immediately (async, fire-and-forget).
     * Used during playback for periodic saves.
     */
    private fun saveProgressImmediately() {
        scope.launch {
            saveProgressSync()
        }
    }
    
    /**
     * Synchronous progress save. Must be called from main thread (player access requirement).
     * Returns immediately if no book is active or player is not available.
     */
    private suspend fun saveProgressSync() {
        // Try to get metadata from shared object if not set locally
        if (currentBookId == null) {
            val metadata = PlaybackServiceMetadata.get()
            if (metadata != null) {
                currentBookId = metadata.bookId
                trackIds = metadata.trackIds
                trackDurations = metadata.trackDurations
            }
        }
        
        val bookId = currentBookId
        val player = mediaSession?.player
        
        if (bookId == null || player == null) {
            return
        }
        
        // CRITICAL: Access player properties on main thread
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex < 0 || currentIndex >= trackIds.size) {
            if (trackIds.isEmpty()) {
                // Metadata not yet set, skip save
                return
            }
            Log.w(TAG, "Invalid track index: $currentIndex (total: ${trackIds.size})")
            return
        }
        
        val currentTrackId = trackIds[currentIndex]
        val currentPosition = player.currentPosition.coerceAtLeast(0)
        val duration = player.duration.coerceAtLeast(0)
        val playbackSpeed = player.playbackParameters.speed
        val isPlaying = player.isPlaying
        
        // Calculate completedTracksMs: sum of all tracks before current
        val completedTracksMs = trackDurations.take(currentIndex).sum()
        
        // Check if completed (last track, near end, not playing)
        val isLastTrack = currentIndex >= trackIds.lastIndex
        val nearEnd = duration > 0 && currentPosition >= duration - 1000
        val isCompleted = isLastTrack && nearEnd && !isPlaying
        
        // Now switch to IO dispatcher for database write
        try {
            val progress = ProgressEntity(
                bookId = bookId,
                lastTrackId = currentTrackId,
                lastPositionMs = currentPosition,
                completedTracksMs = completedTracksMs,
                lastUpdated = System.currentTimeMillis(),
                isCompleted = isCompleted,
                playbackSpeed = playbackSpeed,
            )
            
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                repository.saveProgress(progress)
            }
            
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Progress saved: bookId=$bookId, position=${currentPosition}ms, " +
                    "track=$currentIndex/${trackIds.size}, trackId=$currentTrackId, " +
                    "completed=$isCompleted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save progress", e)
        }
    }
    
    /**
     * Schedule progress save via WorkManager for guaranteed execution.
     * This survives process death via Android's persistent job queue.
     */
    private fun scheduleProgressSaveViaWorkManager(immediate: Boolean = false) {
        // Try to get metadata from shared object if not set locally
        if (currentBookId == null) {
            val metadata = PlaybackServiceMetadata.get()
            if (metadata != null) {
                currentBookId = metadata.bookId
                trackIds = metadata.trackIds
                trackDurations = metadata.trackDurations
            }
        }
        
        val bookId = currentBookId
        val player = mediaSession?.player
        
        if (bookId == null || player == null) {
            return
        }
        
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex < 0 || currentIndex >= trackIds.size) {
            if (trackIds.isEmpty()) {
                // Metadata not yet set, skip
                return
            }
            return
        }
        
        val currentTrackId = trackIds[currentIndex]
        val currentPosition = player.currentPosition.coerceAtLeast(0)
        val completedTracksMs = trackDurations.take(currentIndex).sum()
        
        val inputData = Data.Builder()
            .putLong(ProgressSaveWorker.KEY_BOOK_ID, bookId)
            .putLong(ProgressSaveWorker.KEY_LAST_TRACK_ID, currentTrackId)
            .putLong(ProgressSaveWorker.KEY_LAST_POSITION_MS, currentPosition)
            .putLong(ProgressSaveWorker.KEY_COMPLETED_TRACKS_MS, completedTracksMs)
            .putFloat(ProgressSaveWorker.KEY_PLAYBACK_SPEED, player.playbackParameters.speed)
            .putBoolean(ProgressSaveWorker.KEY_IS_COMPLETED, false)
            .build()
        
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false) // Always run, even on low battery
            .build()
        
        val saveRequest = OneTimeWorkRequestBuilder<ProgressSaveWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .apply {
                if (immediate) {
                    // Expedited work for critical saves (onDestroy)
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .build()
        
        WorkManager.getInstance(this).enqueueUniqueWork(
            "progress_save_$bookId",
            ExistingWorkPolicy.REPLACE,
            saveRequest
        )
        
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Scheduled WorkManager save for book $bookId (immediate=$immediate)")
        }
    }

    override fun onDestroy() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Service onDestroy() called - performing final progress save")
        }
        
        // Stop periodic saves
        stopPeriodicSave()
        
        // CRITICAL: Final save before service dies
        // Use runBlocking on main dispatcher (player access requirement)
        runBlocking {
            try {
                saveProgressSync()
                // Give Room time to flush WAL to disk
                delay(100)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save progress in onDestroy()", e)
            }
        }
        
        // Also schedule WorkManager as backup (survives process death)
        scheduleProgressSaveViaWorkManager(immediate = true)
        
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        scope.cancel()
        super.onDestroy()
    }

    @OptIn(UnstableApi::class)
    private inner class PlaybackSessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (session.isMediaNotificationController(controller)) {
                val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .remove(Player.COMMAND_SEEK_TO_NEXT)
                    .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .build()

                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand(ACTION_SEEK_BACK, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_SEEK_FORWARD, Bundle.EMPTY))
                    .build()

                val mediaButtonPreferences = ImmutableList.of(
                    CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
                        .setSessionCommand(SessionCommand(ACTION_SEEK_BACK, Bundle.EMPTY))
                        .setDisplayName(getString(R.string.notification_rewind_10))
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
                        .setSessionCommand(SessionCommand(ACTION_SEEK_FORWARD, Bundle.EMPTY))
                        .setDisplayName(getString(R.string.notification_forward_10))
                        .build(),
                )

                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailablePlayerCommands(playerCommands)
                    .setAvailableSessionCommands(sessionCommands)
                    .setMediaButtonPreferences(mediaButtonPreferences)
                    .build()
            }
            return super.onConnect(session, controller)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_SEEK_BACK -> {
                    session.player.seekTo(
                        (session.player.currentPosition - SEEK_INCREMENT_MS).coerceAtLeast(0L)
                    )
                }
                ACTION_SEEK_FORWARD -> {
                    val dur = session.player.duration
                    session.player.seekTo(
                        if (dur > 0) {
                            (session.player.currentPosition + SEEK_INCREMENT_MS).coerceAtMost(dur)
                        } else {
                            session.player.currentPosition + SEEK_INCREMENT_MS
                        }
                    )
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
}
