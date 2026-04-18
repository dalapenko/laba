package com.dalapenko.laba.core.media

import android.app.PendingIntent
import android.content.Intent
import android.database.sqlite.SQLiteException
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
import com.dalapenko.laba.core.data.ProgressRepository
import com.dalapenko.laba.core.database.entity.ProgressEntity
import com.dalapenko.laba.core.work.ProgressSaveWorker
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
import org.koin.android.ext.android.inject

class PlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_SEEK_BACK = "com.dalapenko.laba.SEEK_BACK"
        const val ACTION_SEEK_FORWARD = "com.dalapenko.laba.SEEK_FORWARD"
        const val ACTION_SET_BOOK_METADATA = "com.dalapenko.laba.SET_BOOK_METADATA"
        const val KEY_BOOK_ID = "bookId"
        const val KEY_TRACK_IDS = "trackIds"
        const val KEY_TRACK_DURATIONS = "trackDurations"
        const val SEEK_INCREMENT_MS = 10_000L
        private const val COMPLETION_THRESHOLD_MS = 1_000L
        private const val PERIODIC_SAVE_INTERVAL_MS = 3_000L // Save every 3 seconds
        private const val TAG = "PlaybackService"
    }

    private data class ProgressSaveSnapshot(
        val bookId: Long,
        val currentTrackId: Long,
        val currentIndex: Int,
        val currentPosition: Long,
        val completedTracksMs: Long,
        val playbackSpeed: Float,
        val isCompleted: Boolean,
    )

    private val repository: ProgressRepository by inject()
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
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
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
                    events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
                ) {
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
     * Set metadata for the current book. Called via custom command from PlaybackController.
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
        val player = mediaSession?.player
        val snapshot = player?.let { currentProgressSnapshot(it) } ?: return

        // Now switch to IO dispatcher for database write
        try {
            val progress = ProgressEntity(
                bookId = snapshot.bookId,
                lastTrackId = snapshot.currentTrackId,
                lastPositionMs = snapshot.currentPosition,
                completedTracksMs = snapshot.completedTracksMs,
                lastUpdated = System.currentTimeMillis(),
                isCompleted = snapshot.isCompleted,
                playbackSpeed = snapshot.playbackSpeed,
            )

            kotlinx.coroutines.withContext(Dispatchers.IO) {
                repository.saveProgress(progress)
            }

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(
                    TAG,
                    "Progress saved: bookId=${snapshot.bookId}, position=${snapshot.currentPosition}ms, " +
                        "track=${snapshot.currentIndex}/${trackIds.size}, trackId=${snapshot.currentTrackId}, " +
                        "completed=${snapshot.isCompleted}",
                )
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to save progress", e)
        } catch (e: SQLiteException) {
            Log.e(TAG, "Failed to save progress", e)
        }
    }

    /**
     * Schedule progress save via WorkManager for guaranteed execution.
     * This survives process death via Android's persistent job queue.
     */
    private fun scheduleProgressSaveViaWorkManager(immediate: Boolean = false) {
        val player = mediaSession?.player
        val snapshot = player?.let { currentProgressSnapshot(it) } ?: return

        val inputData = Data.Builder()
            .putLong(ProgressSaveWorker.KEY_BOOK_ID, snapshot.bookId)
            .putLong(ProgressSaveWorker.KEY_LAST_TRACK_ID, snapshot.currentTrackId)
            .putLong(ProgressSaveWorker.KEY_LAST_POSITION_MS, snapshot.currentPosition)
            .putLong(ProgressSaveWorker.KEY_COMPLETED_TRACKS_MS, snapshot.completedTracksMs)
            .putFloat(ProgressSaveWorker.KEY_PLAYBACK_SPEED, snapshot.playbackSpeed)
            .putBoolean(ProgressSaveWorker.KEY_IS_COMPLETED, snapshot.isCompleted)
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
            "progress_save_${snapshot.bookId}",
            ExistingWorkPolicy.REPLACE,
            saveRequest,
        )

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Scheduled WorkManager save for book ${snapshot.bookId} (immediate=$immediate)")
        }
    }

    private fun currentProgressSnapshot(player: Player): ProgressSaveSnapshot? {
        val bookId = currentBookId
        val currentIndex = player.currentMediaItemIndex
        val isValidIndex = currentIndex in trackIds.indices
        if (!isValidIndex && trackIds.isNotEmpty()) {
            Log.w(TAG, "Invalid track index: $currentIndex (total: ${trackIds.size})")
        }
        return if (bookId != null && isValidIndex) {
            val currentPosition = player.currentPosition.coerceAtLeast(0)
            val duration = player.duration.coerceAtLeast(0)
            val isLastTrack = currentIndex >= trackIds.lastIndex
            val nearEnd = duration > 0 && currentPosition >= duration - COMPLETION_THRESHOLD_MS
            ProgressSaveSnapshot(
                bookId = bookId,
                currentTrackId = trackIds[currentIndex],
                currentIndex = currentIndex,
                currentPosition = currentPosition,
                completedTracksMs = trackDurations.take(currentIndex).sum(),
                playbackSpeed = player.playbackParameters.speed,
                isCompleted = isLastTrack && nearEnd && !player.isPlaying,
            )
        } else {
            null
        }
    }

    override fun onDestroy() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Service onDestroy() called - scheduling final progress save")
        }

        // Stop periodic saves
        stopPeriodicSave()

        // Schedule WorkManager expedited job — guaranteed to run even after process death.
        // Preferred over runBlocking which blocks the main thread and risks ANR.
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
            val baseSessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_SEEK_BACK, Bundle.EMPTY))
                .add(SessionCommand(ACTION_SEEK_FORWARD, Bundle.EMPTY))
                .add(SessionCommand(ACTION_SET_BOOK_METADATA, Bundle.EMPTY))
                .build()

            if (session.isMediaNotificationController(controller)) {
                val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .remove(Player.COMMAND_SEEK_TO_NEXT)
                    .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
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
                    .setAvailableSessionCommands(baseSessionCommands)
                    .setMediaButtonPreferences(mediaButtonPreferences)
                    .build()
            }

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(baseSessionCommands)
                .build()
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
                        (session.player.currentPosition - SEEK_INCREMENT_MS).coerceAtLeast(0L),
                    )
                }
                ACTION_SEEK_FORWARD -> {
                    val dur = session.player.duration
                    session.player.seekTo(
                        if (dur > 0) {
                            (session.player.currentPosition + SEEK_INCREMENT_MS).coerceAtMost(dur)
                        } else {
                            session.player.currentPosition + SEEK_INCREMENT_MS
                        },
                    )
                }
                ACTION_SET_BOOK_METADATA -> {
                    val bookId = args.getLong(KEY_BOOK_ID)
                    val trackIdArray = args.getLongArray(KEY_TRACK_IDS)?.toList() ?: emptyList()
                    val trackDurationArray = args.getLongArray(KEY_TRACK_DURATIONS)?.toList() ?: emptyList()
                    setBookMetadata(bookId, trackIdArray, trackDurationArray)
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
}
