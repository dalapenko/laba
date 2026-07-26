package com.dalapenko.laba.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [computeProgressSnapshot], the pure decision logic extracted from [PlaybackService]
 * (index validation, completion detection, completed-tracks accounting) that would otherwise
 * be untestable without a real Media3 Player/Service instance.
 */
class PlaybackServiceSnapshotTest {

    @Test
    fun givenNoActiveBook_whenComputeSnapshot_thenReturnsNull() {
        val snapshot = computeProgressSnapshot(
            PlaybackSnapshotInput(
                bookId = null,
                trackIds = listOf(1L, 2L),
                trackDurations = listOf(1000L, 2000L),
                currentIndex = 0,
                currentPositionMs = 0L,
                durationMs = 1000L,
                isPlaying = true,
                playbackSpeed = 1f,
            ),
        )

        assertNull(snapshot)
    }

    @Test
    fun givenIndexOutOfBoundsWithKnownTracks_whenComputeSnapshot_thenReturnsNull() {
        val snapshot = computeProgressSnapshot(
            PlaybackSnapshotInput(
                bookId = 1L,
                trackIds = listOf(1L, 2L),
                trackDurations = listOf(1000L, 2000L),
                currentIndex = 5,
                currentPositionMs = 0L,
                durationMs = 1000L,
                isPlaying = true,
                playbackSpeed = 1f,
            ),
        )

        assertNull(snapshot)
    }

    @Test
    fun givenEmptyTrackIds_whenComputeSnapshot_thenReturnsNullWithoutWarningLog() {
        val snapshot = computeProgressSnapshot(
            PlaybackSnapshotInput(
                bookId = 1L,
                trackIds = emptyList(),
                trackDurations = emptyList(),
                currentIndex = 0,
                currentPositionMs = 0L,
                durationMs = 1000L,
                isPlaying = true,
                playbackSpeed = 1f,
            ),
        )

        assertNull(snapshot)
    }

    @Test
    fun givenValidMidPlaybackState_whenComputeSnapshot_thenReturnsSnapshotWithoutCompletion() {
        val snapshot = computeProgressSnapshot(
            PlaybackSnapshotInput(
                bookId = 7L,
                trackIds = listOf(10L, 11L, 12L),
                trackDurations = listOf(1000L, 2000L, 3000L),
                currentIndex = 1,
                currentPositionMs = 500L,
                durationMs = 2000L,
                isPlaying = true,
                playbackSpeed = 1.25f,
            ),
        )

        assertEquals(7L, snapshot?.bookId)
        assertEquals(11L, snapshot?.currentTrackId)
        assertEquals(1, snapshot?.currentIndex)
        assertEquals(500L, snapshot?.currentPosition)
        assertEquals(1000L, snapshot?.completedTracksMs)
        assertEquals(1.25f, snapshot?.playbackSpeed)
        assertFalse(snapshot!!.isCompleted)
    }

    @Test
    fun givenLastTrackNearEndAndNotPlaying_whenComputeSnapshot_thenIsCompletedTrue() {
        val snapshot = computeProgressSnapshot(
            PlaybackSnapshotInput(
                bookId = 1L,
                trackIds = listOf(10L, 11L),
                trackDurations = listOf(1000L, 2000L),
                currentIndex = 1,
                currentPositionMs = 1999L,
                durationMs = 2000L,
                isPlaying = false,
                playbackSpeed = 1f,
            ),
        )

        assertTrue(snapshot!!.isCompleted)
    }

    @Test
    fun givenLastTrackNearEndButStillPlaying_whenComputeSnapshot_thenIsCompletedFalse() {
        val snapshot = computeProgressSnapshot(
            PlaybackSnapshotInput(
                bookId = 1L,
                trackIds = listOf(10L, 11L),
                trackDurations = listOf(1000L, 2000L),
                currentIndex = 1,
                currentPositionMs = 1999L,
                durationMs = 2000L,
                isPlaying = true,
                playbackSpeed = 1f,
            ),
        )

        assertFalse(snapshot!!.isCompleted)
    }

    @Test
    fun givenNotLastTrackNearEnd_whenComputeSnapshot_thenIsCompletedFalse() {
        val snapshot = computeProgressSnapshot(
            PlaybackSnapshotInput(
                bookId = 1L,
                trackIds = listOf(10L, 11L, 12L),
                trackDurations = listOf(1000L, 2000L, 3000L),
                currentIndex = 0,
                currentPositionMs = 999L,
                durationMs = 1000L,
                isPlaying = false,
                playbackSpeed = 1f,
            ),
        )

        assertFalse(snapshot!!.isCompleted)
    }

    @Test
    fun givenUnknownDuration_whenComputeSnapshot_thenNotConsideredNearEndOrCompleted() {
        val snapshot = computeProgressSnapshot(
            PlaybackSnapshotInput(
                bookId = 1L,
                trackIds = listOf(10L),
                trackDurations = listOf(0L),
                currentIndex = 0,
                currentPositionMs = 0L,
                durationMs = 0L,
                isPlaying = false,
                playbackSpeed = 1f,
            ),
        )

        assertFalse(snapshot!!.isCompleted)
    }

    @Test
    fun givenNegativePositionAndDuration_whenComputeSnapshot_thenClampedToZero() {
        val snapshot = computeProgressSnapshot(
            PlaybackSnapshotInput(
                bookId = 1L,
                trackIds = listOf(10L),
                trackDurations = listOf(1000L),
                currentIndex = 0,
                currentPositionMs = -50L,
                durationMs = -100L,
                isPlaying = false,
                playbackSpeed = 1f,
            ),
        )

        assertEquals(0L, snapshot?.currentPosition)
    }

    @Test
    fun givenLaterTrackIndex_whenComputeSnapshot_thenCompletedTracksMsSumsPriorTrackDurations() {
        val snapshot = computeProgressSnapshot(
            PlaybackSnapshotInput(
                bookId = 1L,
                trackIds = listOf(10L, 11L, 12L, 13L),
                trackDurations = listOf(1000L, 2000L, 3000L, 4000L),
                currentIndex = 3,
                currentPositionMs = 0L,
                durationMs = 4000L,
                isPlaying = true,
                playbackSpeed = 1f,
            ),
        )

        assertEquals(6000L, snapshot?.completedTracksMs)
    }
}
