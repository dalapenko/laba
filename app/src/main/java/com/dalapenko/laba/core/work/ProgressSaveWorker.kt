package com.dalapenko.laba.core.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dalapenko.laba.core.database.entity.ProgressEntity
import com.dalapenko.laba.core.data.ProgressRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * WorkManager worker that guarantees progress persistence even after process death.
 * This worker survives app termination via Android's persistent job queue.
 * 
 * Input data keys:
 * - bookId: Long (required)
 * - lastTrackId: Long (required)
 * - lastPositionMs: Long (required)
 * - completedTracksMs: Long (required)
 * - playbackSpeed: Float (required)
 * - isCompleted: Boolean (default: false)
 */
class ProgressSaveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val repository: ProgressRepository by inject()

    override suspend fun doWork(): Result {
        return try {
            val bookId = inputData.getLong(KEY_BOOK_ID, -1L)
            val lastTrackId = inputData.getLong(KEY_LAST_TRACK_ID, -1L)
            val lastPositionMs = inputData.getLong(KEY_LAST_POSITION_MS, 0L)
            val completedTracksMs = inputData.getLong(KEY_COMPLETED_TRACKS_MS, 0L)
            val playbackSpeed = inputData.getFloat(KEY_PLAYBACK_SPEED, 1f)
            val isCompleted = inputData.getBoolean(KEY_IS_COMPLETED, false)

            if (bookId == -1L || lastTrackId == -1L) {
                Log.e(TAG, "Invalid input data: bookId=$bookId, lastTrackId=$lastTrackId")
                return Result.failure()
            }

            val progress = ProgressEntity(
                bookId = bookId,
                lastTrackId = lastTrackId,
                lastPositionMs = lastPositionMs,
                completedTracksMs = completedTracksMs,
                lastUpdated = System.currentTimeMillis(),
                isCompleted = isCompleted,
                playbackSpeed = playbackSpeed,
            )

            repository.saveProgress(progress)

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Progress saved via WorkManager for book $bookId: " +
                    "position=${lastPositionMs}ms, trackId=$lastTrackId")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save progress via WorkManager", e)
            // Retry on failure
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ProgressSaveWorker"
        
        const val KEY_BOOK_ID = "bookId"
        const val KEY_LAST_TRACK_ID = "lastTrackId"
        const val KEY_LAST_POSITION_MS = "lastPositionMs"
        const val KEY_COMPLETED_TRACKS_MS = "completedTracksMs"
        const val KEY_PLAYBACK_SPEED = "playbackSpeed"
        const val KEY_IS_COMPLETED = "isCompleted"
    }
}
