package com.dalapenko.laba.core.media

import com.dalapenko.laba.core.data.ProgressRepository
import com.dalapenko.laba.testBook
import com.dalapenko.laba.testProgress
import com.dalapenko.laba.testTrack
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PlaybackPreparerTest {

    private val mockProgressRepository = mockk<ProgressRepository>()
    private val mockController = mockk<PlaybackController>()
    private val currentBookIdFlow = MutableStateFlow<Long?>(null)

    private lateinit var preparer: PlaybackPreparer

    @Before
    fun setup() {
        every { mockController.currentBookId } returns currentBookIdFlow
        justRun { mockController.captureCurrentBookState() }
        justRun { mockController.setInitialState(any(), any(), any(), any()) }
        justRun { mockController.setPlaylist(any(), any()) }
        justRun { mockController.setBookMetadata(any(), any(), any()) }
        justRun { mockController.seekToTrack(any(), any()) }
        justRun { mockController.setSpeed(any()) }
        justRun { mockController.play() }
        coJustRun { mockProgressRepository.saveProgress(any()) }
        preparer = PlaybackPreparer(mockProgressRepository, mockController)
    }

    @Test
    fun givenSameBookAlreadyActive_whenSetupPlaybackWithAutoPlay_thenOnlyPlaysWithoutRebuildingPlaylist() = runTest {
        currentBookIdFlow.value = 1L
        val book = testBook(id = 1L)
        val tracks = listOf(testTrack(id = 1L, bookId = 1L))

        preparer.setupPlayback(1L, book, tracks, autoPlay = true)

        verify { mockController.captureCurrentBookState() }
        verify { mockController.play() }
        verify(exactly = 0) { mockController.setPlaylist(any(), any()) }
        verify(exactly = 0) { mockController.setInitialState(any(), any(), any(), any()) }
    }

    @Test
    fun givenSameBookAlreadyActive_whenSetupPlaybackWithoutAutoPlay_thenPlayNotCalled() = runTest {
        currentBookIdFlow.value = 1L
        val book = testBook(id = 1L)
        val tracks = listOf(testTrack(id = 1L, bookId = 1L))

        preparer.setupPlayback(1L, book, tracks, autoPlay = false)

        verify(exactly = 0) { mockController.play() }
    }

    @Test
    fun givenDifferentBookWithNoSavedProgress_whenSetupPlayback_thenStartsFromBeginningWithDefaultSpeed() = runTest {
        val book = testBook(id = 2L)
        val tracks = listOf(testTrack(id = 10L, bookId = 2L, durationMs = 60_000L))
        coEvery { mockProgressRepository.getProgress(2L) } returns null

        preparer.setupPlayback(2L, book, tracks, autoPlay = false)

        verify { mockController.setInitialState(position = 0L, duration = 60_000L, trackIndex = 0, speed = 1.0f) }
        verify { mockController.setSpeed(1.0f) }
        verify(exactly = 0) { mockController.seekToTrack(any(), any()) }
    }

    @Test
    fun givenDifferentBookWithMatchingSavedProgress_whenSetupPlayback_thenResumesAtSavedPositionAndSpeed() = runTest {
        val book = testBook(id = 3L)
        val tracks = listOf(
            testTrack(id = 10L, bookId = 3L, durationMs = 60_000L, sequenceOrder = 0),
            testTrack(id = 11L, bookId = 3L, durationMs = 90_000L, sequenceOrder = 1),
        )
        val progress = testProgress(bookId = 3L, lastTrackId = 11L, lastPositionMs = 30_000L, playbackSpeed = 1.5f)
        coEvery { mockProgressRepository.getProgress(3L) } returns progress

        preparer.setupPlayback(3L, book, tracks, autoPlay = false)

        verify { mockController.setInitialState(position = 30_000L, duration = 90_000L, trackIndex = 1, speed = 1.5f) }
        verify { mockController.seekToTrack(1, 30_000L) }
        verify { mockController.setSpeed(1.5f) }
    }

    @Test
    fun givenSavedSpeedOutOfBounds_whenSetupPlayback_thenSpeedIsCoercedToAllowedRange() = runTest {
        val book = testBook(id = 4L)
        val tracks = listOf(testTrack(id = 10L, bookId = 4L, durationMs = 60_000L))
        val progress = testProgress(bookId = 4L, lastTrackId = 10L, playbackSpeed = 5.0f)
        coEvery { mockProgressRepository.getProgress(4L) } returns progress

        preparer.setupPlayback(4L, book, tracks, autoPlay = false)

        verify { mockController.setSpeed(2.0f) }
    }

    @Test
    fun givenSavedProgressReferencesUnknownTrack_whenSetupPlayback_thenFallsBackToBeginningAndNoResumeSideEffects() =
        runTest {
            val book = testBook(id = 5L)
            val tracks = listOf(testTrack(id = 10L, bookId = 5L, durationMs = 60_000L))
            val progress = testProgress(bookId = 5L, lastTrackId = 999L, lastPositionMs = 30_000L, isCompleted = false)
            coEvery { mockProgressRepository.getProgress(5L) } returns progress

            preparer.setupPlayback(5L, book, tracks, autoPlay = false)

            verify { mockController.setInitialState(position = 0L, duration = 60_000L, trackIndex = 0, speed = 1.0f) }
            verify(exactly = 0) { mockController.seekToTrack(any(), any()) }
            coVerify(exactly = 0) { mockProgressRepository.saveProgress(any()) }
        }

    @Test
    fun givenCompletedSavedProgress_whenSetupPlayback_thenProgressIsResetInRepository() = runTest {
        val book = testBook(id = 6L)
        val tracks = listOf(
            testTrack(id = 20L, bookId = 6L, durationMs = 60_000L, sequenceOrder = 0),
            testTrack(id = 21L, bookId = 6L, durationMs = 90_000L, sequenceOrder = 1),
        )
        val progress = testProgress(bookId = 6L, lastTrackId = 21L, isCompleted = true)
        coEvery { mockProgressRepository.getProgress(6L) } returns progress

        preparer.setupPlayback(6L, book, tracks, autoPlay = false)

        verify { mockController.setInitialState(position = 0L, duration = 60_000L, trackIndex = 0, speed = 1.0f) }
        val savedSlot = slot<com.dalapenko.laba.core.database.entity.ProgressEntity>()
        coVerify { mockProgressRepository.saveProgress(capture(savedSlot)) }
        assertEquals(false, savedSlot.captured.isCompleted)
        assertEquals(0L, savedSlot.captured.lastPositionMs)
        assertEquals(0L, savedSlot.captured.completedTracksMs)
        assertEquals(20L, savedSlot.captured.lastTrackId)
    }

    @Test
    fun givenAutoPlayTrue_whenSetupPlaybackForDifferentBook_thenPlayIsCalledAfterSetup() = runTest {
        val book = testBook(id = 7L)
        val tracks = listOf(testTrack(id = 10L, bookId = 7L, durationMs = 60_000L))
        coEvery { mockProgressRepository.getProgress(7L) } returns null

        preparer.setupPlayback(7L, book, tracks, autoPlay = true)

        verify { mockController.play() }
    }

    @Test
    fun givenDifferentBook_whenSetupPlayback_thenPlaylistBuiltFromTracksAndBookMetadata() = runTest {
        val book = testBook(id = 8L, author = "Author X", coverUri = "content://cover")
        val tracks = listOf(
            testTrack(id = 30L, bookId = 8L, fileUri = "content://t30", fileName = "T30", durationMs = 10_000L),
            testTrack(id = 31L, bookId = 8L, fileUri = "content://t31", fileName = "T31", durationMs = 20_000L),
        )
        coEvery { mockProgressRepository.getProgress(8L) } returns null

        preparer.setupPlayback(8L, book, tracks, autoPlay = false)

        val playlistSlot = slot<List<PlaylistItem>>()
        verify { mockController.setPlaylist(capture(playlistSlot), 8L) }
        assertEquals(2, playlistSlot.captured.size)
        assertEquals("content://t30", playlistSlot.captured[0].uri)
        assertEquals("T30", playlistSlot.captured[0].title)
        assertEquals("Author X", playlistSlot.captured[0].artist)
        assertEquals("content://cover", playlistSlot.captured[0].artworkUri)

        verify {
            mockController.setBookMetadata(
                bookId = 8L,
                trackIds = listOf(30L, 31L),
                trackDurations = listOf(10_000L, 20_000L),
            )
        }
    }
}
