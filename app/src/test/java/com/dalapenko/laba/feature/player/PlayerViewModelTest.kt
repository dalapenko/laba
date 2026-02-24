package com.dalapenko.laba.feature.player

import app.cash.turbine.test
import com.dalapenko.laba.MainDispatcherRule
import com.dalapenko.laba.core.data.BookRepository
import com.dalapenko.laba.core.data.ProgressRepository
import com.dalapenko.laba.core.media.PlaybackController
import com.dalapenko.laba.core.media.PlaybackError
import com.dalapenko.laba.core.media.PlaybackPreparer
import com.dalapenko.laba.core.media.PlayerState
import com.dalapenko.laba.testBook
import com.dalapenko.laba.testProgress
import com.dalapenko.laba.testTrack
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val mockRepository = mockk<BookRepository>()
    private val mockProgressRepository = mockk<ProgressRepository>()
    private val mockController = mockk<PlaybackController>()

    private val currentBookIdFlow = MutableStateFlow<Long?>(null)
    private val playerStateFlow = MutableStateFlow(PlayerState())
    private val playbackErrorFlow = MutableSharedFlow<PlaybackError>(extraBufferCapacity = 1)

    private val bookId = 1L

    @Before
    fun setup() {
        every { mockController.currentBookId } returns currentBookIdFlow
        every { mockController.playerState } returns playerStateFlow
        every { mockController.playbackError } returns playbackErrorFlow

        // Default: book not found — override per test
        coEvery { mockRepository.getBookWithTracks(any()) } returns null
        coEvery { mockProgressRepository.getProgress(any()) } returns null
        coJustRun { mockProgressRepository.saveProgress(any()) }
        coJustRun { mockRepository.setBookAvailability(any(), any()) }
        justRun { mockController.captureCurrentBookState() }
        justRun { mockController.setPlaylist(any(), any()) }
        justRun { mockController.setBookMetadata(any(), any(), any()) }
        justRun { mockController.setInitialState(any(), any(), any(), any()) }
        justRun { mockController.seekToTrack(any(), any()) }
        justRun { mockController.setSpeed(any()) }
        justRun { mockController.play() }
        justRun { mockController.pause() }
        justRun { mockController.stop() }
        justRun { mockController.updateBookSnapshot(any(), any()) }
        every { mockController.getBookSnapshot(any()) } returns null
    }

    private fun createViewModel(autoPlay: Boolean = false): PlayerViewModel {
        val preparer = PlaybackPreparer(mockProgressRepository, mockController)
        return PlayerViewModel(bookId, autoPlay, mockRepository, mockProgressRepository, mockController, preparer)
    }

    // ── loadBook ──────────────────────────────────────────────────────────────

    @Test
    fun givenBookExistsInDb_whenViewModelCreated_thenUiStateHasBookAndTracks() = runTest {
        val book = testBook(id = bookId)
        val tracks = listOf(testTrack(bookId = bookId))
        coEvery { mockRepository.getBookWithTracks(bookId) } returns (book to tracks)
        justRun { mockController.setInitialState(any(), any(), any(), any()) }

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(book, vm.uiState.value.book)
        assertEquals(tracks, vm.uiState.value.tracks)
        assertFalse(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.isInitializing)
    }

    @Test
    fun givenBookNotFoundInDb_whenViewModelCreated_thenUiStateHasNullBookAndIsNotLoading() = runTest {
        coEvery { mockRepository.getBookWithTracks(bookId) } returns null

        val vm = createViewModel()
        advanceUntilIdle()

        assertNull(vm.uiState.value.book)
        assertFalse(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.isInitializing)
    }

    @Test
    fun givenSameBookAlreadyPlaying_whenViewModelCreated_thenInitialStateNotSet() = runTest {
        val book = testBook(id = bookId)
        val tracks = listOf(testTrack(bookId = bookId))
        coEvery { mockRepository.getBookWithTracks(bookId) } returns (book to tracks)
        currentBookIdFlow.value = bookId  // already active

        createViewModel()
        advanceUntilIdle()

        // setInitialState should NOT be called since same book is active
        verify(exactly = 0) { mockController.setInitialState(any(), any(), any(), any()) }
    }

    // ── setupPlaylist / progress restore ──────────────────────────────────────

    @Test
    fun givenNoSavedProgress_whenPlaylistSetup_thenStartsFromTrackZeroPosition() = runTest {
        val book = testBook(id = bookId)
        val tracks = listOf(testTrack(id = 10L, bookId = bookId, durationMs = 60_000L))
        coEvery { mockRepository.getBookWithTracks(bookId) } returns (book to tracks)
        coEvery { mockProgressRepository.getProgress(bookId) } returns null

        createViewModel()
        advanceUntilIdle()

        verify { mockController.setInitialState(position = 0L, duration = 60_000L, trackIndex = 0, speed = 1.0f) }
    }

    @Test
    fun givenSavedProgressWithMatchingTrack_whenPlaylistSetup_thenRestoresPosition() = runTest {
        val book = testBook(id = bookId)
        val tracks = listOf(
            testTrack(id = 10L, bookId = bookId, durationMs = 60_000L, sequenceOrder = 0),
            testTrack(id = 11L, bookId = bookId, durationMs = 90_000L, sequenceOrder = 1),
        )
        val progress = testProgress(bookId = bookId, lastTrackId = 11L, lastPositionMs = 45_000L, playbackSpeed = 1.5f)
        coEvery { mockRepository.getBookWithTracks(bookId) } returns (book to tracks)
        coEvery { mockProgressRepository.getProgress(bookId) } returns progress

        createViewModel()
        advanceUntilIdle()

        verify { mockController.seekToTrack(1, 45_000L) }
        verify { mockController.setSpeed(1.5f) }
    }

    @Test
    fun givenCompletedProgress_whenPlaylistSetup_thenResetsProgressToBeginning() = runTest {
        val book = testBook(id = bookId)
        val tracks = listOf(testTrack(id = 10L, bookId = bookId))
        val completedProgress = testProgress(bookId = bookId, isCompleted = true, lastTrackId = 10L)
        coEvery { mockRepository.getBookWithTracks(bookId) } returns (book to tracks)
        coEvery { mockProgressRepository.getProgress(bookId) } returns completedProgress

        createViewModel()
        advanceUntilIdle()

        coVerify {
            mockProgressRepository.saveProgress(
                match { it.isCompleted == false && it.lastPositionMs == 0L }
            )
        }
    }

    @Test
    fun givenAutoPlayTrue_whenPlaylistSetup_thenPlayIsCalled() = runTest {
        val book = testBook(id = bookId)
        val tracks = listOf(testTrack(bookId = bookId))
        coEvery { mockRepository.getBookWithTracks(bookId) } returns (book to tracks)

        createViewModel(autoPlay = true)
        advanceUntilIdle()

        verify { mockController.play() }
    }

    @Test
    fun givenAutoPlayFalse_whenPlaylistSetup_thenPlayIsNotCalled() = runTest {
        val book = testBook(id = bookId)
        val tracks = listOf(testTrack(bookId = bookId))
        coEvery { mockRepository.getBookWithTracks(bookId) } returns (book to tracks)

        createViewModel(autoPlay = false)
        advanceUntilIdle()

        verify(exactly = 0) { mockController.play() }
    }

    // ── collectPlayerState guards ─────────────────────────────────────────────

    @Test
    fun givenDifferentBookIsActive_whenPlayerStateEmitted_thenUiStateNotUpdated() = runTest {
        val book = testBook(id = bookId)
        val tracks = listOf(testTrack(bookId = bookId))
        coEvery { mockRepository.getBookWithTracks(bookId) } returns (book to tracks)
        currentBookIdFlow.value = 999L  // different book
        val vm = createViewModel()
        advanceUntilIdle()

        val initialState = vm.uiState.value.playerState
        playerStateFlow.value = PlayerState(currentPositionMs = 99_999L)
        advanceUntilIdle()

        // State should NOT be updated because a different book is active
        assertEquals(initialState.currentPositionMs, vm.uiState.value.playerState.currentPositionMs)
    }

    @Test
    fun givenTrackIndexExceedsTrackCount_whenPlayerStateEmitted_thenUiStateNotUpdated() = runTest {
        val book = testBook(id = bookId)
        val tracks = listOf(testTrack(bookId = bookId))  // only 1 track, index 0
        coEvery { mockRepository.getBookWithTracks(bookId) } returns (book to tracks)
        currentBookIdFlow.value = bookId
        val vm = createViewModel()
        advanceUntilIdle()

        val initialState = vm.uiState.value.playerState
        // emit a state with track index 5 — out of range for 1-track list
        playerStateFlow.value = PlayerState(currentMediaItemIndex = 5, currentPositionMs = 50_000L)
        advanceUntilIdle()

        assertEquals(initialState.currentMediaItemIndex, vm.uiState.value.playerState.currentMediaItemIndex)
    }

    @Test
    fun givenValidPlayerState_whenEmitted_thenUiStateUpdatedAndSnapshotStored() = runTest {
        val book = testBook(id = bookId)
        val tracks = listOf(testTrack(bookId = bookId))
        coEvery { mockRepository.getBookWithTracks(bookId) } returns (book to tracks)
        currentBookIdFlow.value = bookId
        val vm = createViewModel()
        advanceUntilIdle()

        playerStateFlow.value = PlayerState(currentPositionMs = 30_000L, currentMediaItemIndex = 0)
        advanceUntilIdle()

        assertEquals(30_000L, vm.uiState.value.playerState.currentPositionMs)
        verify { mockController.updateBookSnapshot(bookId, any()) }
    }

    // ── checkCompletion ───────────────────────────────────────────────────────

    @Test
    fun givenLastTrackNearEndAndNotPlaying_whenStateReceived_thenProgressSavedAsCompleted() = runTest {
        val book = testBook(id = bookId)
        val tracks = listOf(testTrack(id = 10L, bookId = bookId, durationMs = 60_000L))
        coEvery { mockRepository.getBookWithTracks(bookId) } returns (book to tracks)
        currentBookIdFlow.value = bookId
        createViewModel()
        advanceUntilIdle()

        // Last track (index 0 == lastIndex), near end (59500 >= 60000 - 1000 = 59000), not playing
        playerStateFlow.value = PlayerState(
            currentMediaItemIndex = 0,
            currentPositionMs = 59_500L,
            durationMs = 60_000L,
            isPlaying = false,
        )
        advanceUntilIdle()

        coVerify { mockProgressRepository.saveProgress(match { it.isCompleted }) }
    }

    @Test
    fun givenLastTrackNearEndButStillPlaying_whenStateReceived_thenNotSavedAsCompleted() = runTest {
        val book = testBook(id = bookId)
        val tracks = listOf(testTrack(id = 10L, bookId = bookId, durationMs = 60_000L))
        coEvery { mockRepository.getBookWithTracks(bookId) } returns (book to tracks)
        currentBookIdFlow.value = bookId
        createViewModel()
        advanceUntilIdle()

        playerStateFlow.value = PlayerState(
            currentMediaItemIndex = 0,
            currentPositionMs = 59_500L,
            durationMs = 60_000L,
            isPlaying = true,  // still playing
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { mockProgressRepository.saveProgress(match { it.isCompleted }) }
    }

    @Test
    fun givenNotLastTrack_whenNearEnd_thenNotSavedAsCompleted() = runTest {
        val book = testBook(id = bookId)
        val tracks = listOf(
            testTrack(id = 10L, bookId = bookId, durationMs = 60_000L, sequenceOrder = 0),
            testTrack(id = 11L, bookId = bookId, durationMs = 60_000L, sequenceOrder = 1),
        )
        coEvery { mockRepository.getBookWithTracks(bookId) } returns (book to tracks)
        currentBookIdFlow.value = bookId
        createViewModel()
        advanceUntilIdle()

        // Track 0 (not last), near end
        playerStateFlow.value = PlayerState(
            currentMediaItemIndex = 0,
            currentPositionMs = 59_500L,
            durationMs = 60_000L,
            isPlaying = false,
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { mockProgressRepository.saveProgress(match { it.isCompleted }) }
    }

    // ── saveProgressInternal ──────────────────────────────────────────────────

    @Test
    fun givenSnapshotAvailableAndCompletionConditions_whenStateSaved_thenSnapshotStateIsUsed() = runTest {
        val book = testBook(id = bookId)
        val tracks = listOf(
            testTrack(id = 10L, bookId = bookId, durationMs = 30_000L, sequenceOrder = 0),
        )
        val snapshotState = PlayerState(
            currentPositionMs = 29_500L,
            currentMediaItemIndex = 0,
            durationMs = 30_000L,
            isPlaying = false
        )
        coEvery { mockRepository.getBookWithTracks(bookId) } returns (book to tracks)
        every { mockController.getBookSnapshot(bookId) } returns snapshotState
        currentBookIdFlow.value = bookId
        createViewModel()
        advanceUntilIdle()

        // Emit a state that triggers completion (last track, near end, not playing)
        // This will trigger saveProgressInternal(forceCompleted = true)
        playerStateFlow.value = snapshotState
        advanceUntilIdle()

        coVerify {
            mockProgressRepository.saveProgress(
                match {
                    it.lastPositionMs == 29_500L &&
                        it.isCompleted
                }
            )
        }
    }

    @Test
    fun givenThreeTracksAtLastTrack_whenCompletionTriggered_thenCompletedTracksMsIsCorrect() = runTest {
        val book = testBook(id = bookId)
        val tracks = listOf(
            testTrack(id = 10L, bookId = bookId, durationMs = 10_000L, sequenceOrder = 0),
            testTrack(id = 11L, bookId = bookId, durationMs = 20_000L, sequenceOrder = 1),
            testTrack(id = 12L, bookId = bookId, durationMs = 30_000L, sequenceOrder = 2),
        )
        coEvery { mockRepository.getBookWithTracks(bookId) } returns (book to tracks)
        currentBookIdFlow.value = bookId
        createViewModel()
        advanceUntilIdle()

        // Emit state that triggers completion on last track
        playerStateFlow.value = PlayerState(
            currentPositionMs = 29_500L,
            currentMediaItemIndex = 2,
            durationMs = 30_000L,
            isPlaying = false
        )
        advanceUntilIdle()

        coVerify {
            mockProgressRepository.saveProgress(
                match {
                    it.completedTracksMs == 30_000L &&  // tracks 0+1 = 10000+20000
                        it.isCompleted
                }
            )
        }
    }

    @Test
    fun givenEmptyTracks_whenCompletionChecked_thenSaveProgressNotCalled() = runTest {
        // Book not found → empty tracks
        coEvery { mockRepository.getBookWithTracks(bookId) } returns null
        currentBookIdFlow.value = bookId
        createViewModel()
        advanceUntilIdle()

        // Try to emit a state (but it should be ignored due to empty tracks)
        playerStateFlow.value = PlayerState(
            currentPositionMs = 29_500L,
            currentMediaItemIndex = 0,
            durationMs = 30_000L,
            isPlaying = false
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { mockProgressRepository.saveProgress(any()) }
    }

    // ── observePlaybackErrors ─────────────────────────────────────────────────

    @Test
    fun givenTrackUnavailableError_whenErrorEmitted_thenTrackUnavailableEventFired() = runTest {
        val book = testBook(id = bookId)
        val tracks = listOf(testTrack(id = 10L, bookId = bookId, fileName = "Chapter 1.mp3"))
        coEvery { mockRepository.getBookWithTracks(bookId) } returns (book to tracks)
        currentBookIdFlow.value = bookId
        val vm = createViewModel()
        advanceUntilIdle()

        vm.events.test {
            playbackErrorFlow.tryEmit(PlaybackError.TrackUnavailable(trackIndex = 0))
            advanceUntilIdle()
            val event = awaitItem()
            assertTrue(event is PlayerEvent.TrackUnavailable)
            assertEquals("Chapter 1.mp3", (event as PlayerEvent.TrackUnavailable).trackName)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun givenBookUnavailableError_whenErrorEmitted_thenBookMarkedUnavailableAndCloseEventFired() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.events.test {
            playbackErrorFlow.tryEmit(PlaybackError.BookUnavailable)
            advanceUntilIdle()
            coVerify { mockRepository.setBookAvailability(bookId, false) }
            verify { mockController.stop() }
            assertEquals(PlayerEvent.ClosePlayer, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    // ── setSpeed ──────────────────────────────────────────────────────────────

    @Test
    fun givenSpeedAboveMax_whenSetSpeed_thenSpeedClampedToTwo() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setSpeed(3.0f)

        verify { mockController.setSpeed(2.0f) }
    }

    @Test
    fun givenSpeedBelowMin_whenSetSpeed_thenSpeedClampedToHalf() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setSpeed(0.1f)

        verify { mockController.setSpeed(0.5f) }
    }

    @Test
    fun givenValidSpeed_whenSetSpeed_thenExactValuePassed() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setSpeed(1.25f)

        verify { mockController.setSpeed(1.25f) }
    }

    // ── TrackUnavailable with out-of-bounds index ─────────────────────────────

    @Test
    fun givenTrackUnavailableWithOutOfBoundsIndex_whenErrorEmitted_thenTrackNameIsNull() = runTest {
        val book = testBook(id = bookId)
        val tracks = listOf(testTrack(id = 10L, bookId = bookId))
        coEvery { mockRepository.getBookWithTracks(bookId) } returns (book to tracks)
        currentBookIdFlow.value = bookId
        val vm = createViewModel()
        advanceUntilIdle()

        vm.events.test {
            playbackErrorFlow.tryEmit(PlaybackError.TrackUnavailable(trackIndex = 99))
            advanceUntilIdle()
            val event = awaitItem() as PlayerEvent.TrackUnavailable
            assertNull(event.trackName)
            cancelAndConsumeRemainingEvents()
        }
    }
}
