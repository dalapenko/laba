package com.dalapenko.laba.core.media

import com.dalapenko.laba.core.data.ProgressRepository
import com.dalapenko.laba.core.database.entity.BookEntity
import com.dalapenko.laba.core.database.entity.TrackEntity

private const val MIN_PLAYBACK_SPEED = 0.5f
private const val MAX_PLAYBACK_SPEED = 2.0f

class PlaybackPreparer(
    private val progressRepository: ProgressRepository,
    private val playbackController: PlaybackController,
) {

    suspend fun setupPlayback(
        bookId: Long,
        book: BookEntity,
        tracks: List<TrackEntity>,
        autoPlay: Boolean = false,
    ) {
        playbackController.captureCurrentBookState()

        if (playbackController.currentBookId.value == bookId) {
            if (autoPlay) playbackController.play()
            return
        }

        val progress = progressRepository.getProgress(bookId)
        val targetTrackIndex = if (progress != null && !progress.isCompleted) {
            tracks.indexOfFirst { it.id == progress.lastTrackId }.takeIf { it >= 0 }
        } else {
            null
        }

        if (targetTrackIndex != null && progress != null) {
            playbackController.setInitialState(
                position = progress.lastPositionMs,
                duration = tracks[targetTrackIndex].durationMs,
                trackIndex = targetTrackIndex,
                speed = progress.playbackSpeed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED),
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

        playbackController.setBookMetadata(
            bookId = bookId,
            trackIds = tracks.map { it.id },
            trackDurations = tracks.map { it.durationMs },
        )

        if (targetTrackIndex != null && progress != null) {
            playbackController.seekToTrack(targetTrackIndex, progress.lastPositionMs)
            playbackController.setSpeed(progress.playbackSpeed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED))
        } else if (progress?.isCompleted == true) {
            progressRepository.saveProgress(
                progress.copy(
                    isCompleted = false,
                    lastPositionMs = 0L,
                    completedTracksMs = 0L,
                    lastTrackId = tracks.first().id,
                    lastUpdated = System.currentTimeMillis(),
                )
            )
        }

        if (autoPlay) playbackController.play()
    }
}
