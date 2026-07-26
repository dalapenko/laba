package com.dalapenko.laba.core.media

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerControllerTest {

    private val mockPlaybackController = mockk<PlaybackController>()
    private val playerStateFlow = MutableStateFlow(PlayerState())
    private val currentBookIdFlow = MutableStateFlow<Long?>(1L)

    @Before
    fun setup() {
        every { mockPlaybackController.playerState } returns playerStateFlow
        every { mockPlaybackController.currentBookId } returns currentBookIdFlow
        justRun { mockPlaybackController.pause() }
    }

    // ── startFixedDuration ────────────────────────────────────────────────────

    @Test
    fun givenFixedDuration_whenStarted_thenStateIsActiveWithFullRemaining() = runTest {
        val controller = SleepTimerController(mockPlaybackController, this)

        controller.startFixedDuration(5_000L)

        val state = controller.state.value
        assertTrue(state.isActive)
        assertEquals(5_000L, state.remainingMs)
        assertFalse(state.isEndOfChapterMode)
    }

    @Test
    fun givenFixedDuration_whenPartialTimeElapses_thenRemainingCountsDownAndStaysActive() = runTest {
        val controller = SleepTimerController(mockPlaybackController, this)

        controller.startFixedDuration(5_000L)
        advanceTimeBy(2_000L.milliseconds)
        runCurrent()

        val state = controller.state.value
        assertTrue(state.isActive)
        assertEquals(3_000L, state.remainingMs)
        verify(exactly = 0) { mockPlaybackController.pause() }
    }

    @Test
    fun givenFixedDuration_whenFullTimeElapses_thenPlaybackPausedAndTimerReset() = runTest {
        val controller = SleepTimerController(mockPlaybackController, this)

        controller.startFixedDuration(3_000L)
        advanceUntilIdle()

        verify(exactly = 1) { mockPlaybackController.pause() }
        assertFalse(controller.state.value.isActive)
        assertEquals(0L, controller.state.value.remainingMs)
    }

    @Test
    fun givenActiveFixedTimer_whenCancelled_thenPauseNeverCalledAndStateReset() = runTest {
        val controller = SleepTimerController(mockPlaybackController, this)

        controller.startFixedDuration(10_000L)
        advanceTimeBy(3_000L.milliseconds)
        controller.cancel()
        advanceUntilIdle()

        verify(exactly = 0) { mockPlaybackController.pause() }
        assertFalse(controller.state.value.isActive)
    }

    @Test
    fun givenActiveFixedTimer_whenStartedAgain_thenPreviousJobCancelledAndOnlyNewOneCompletes() = runTest {
        val controller = SleepTimerController(mockPlaybackController, this)

        controller.startFixedDuration(10_000L)
        advanceTimeBy(2_000L.milliseconds)
        controller.startFixedDuration(3_000L)
        advanceUntilIdle()

        verify(exactly = 1) { mockPlaybackController.pause() }
        assertFalse(controller.state.value.isActive)
    }

    // ── startEndOfChapter ─────────────────────────────────────────────────────

    @Test
    fun givenEndOfChapter_whenStarted_thenRemainingIsDurationMinusPosition() = runTest {
        playerStateFlow.value = PlayerState(durationMs = 10_000L, currentPositionMs = 2_000L, currentMediaItemIndex = 0)
        val controller = SleepTimerController(mockPlaybackController, this)

        controller.startEndOfChapter()

        val state = controller.state.value
        assertTrue(state.isActive)
        assertTrue(state.isEndOfChapterMode)
        assertEquals(8_000L, state.remainingMs)

        controller.cancel() // avoid leaving the infinite polling job running past test end
    }

    @Test
    fun givenEndOfChapter_whenPositionAdvancesWithinSameTrack_thenRemainingSelfCorrectsAndStaysActive() = runTest {
        playerStateFlow.value = PlayerState(durationMs = 10_000L, currentPositionMs = 2_000L, currentMediaItemIndex = 0)
        val controller = SleepTimerController(mockPlaybackController, this)
        controller.startEndOfChapter()

        playerStateFlow.value = PlayerState(durationMs = 10_000L, currentPositionMs = 3_000L, currentMediaItemIndex = 0)
        advanceTimeBy(1_000L.milliseconds)
        runCurrent()

        val state = controller.state.value
        assertTrue(state.isActive)
        assertEquals(7_000L, state.remainingMs)
        verify(exactly = 0) { mockPlaybackController.pause() }

        controller.cancel() // avoid leaving the infinite polling job running past test end
    }

    @Test
    fun givenEndOfChapter_whenTrackIndexChanges_thenPlaybackPausedAndTimerReset() = runTest {
        playerStateFlow.value = PlayerState(durationMs = 10_000L, currentPositionMs = 9_000L, currentMediaItemIndex = 0)
        val controller = SleepTimerController(mockPlaybackController, this)
        controller.startEndOfChapter()

        playerStateFlow.value = PlayerState(durationMs = 5_000L, currentPositionMs = 0L, currentMediaItemIndex = 1)
        advanceTimeBy(1_000L.milliseconds)
        runCurrent()

        verify(exactly = 1) { mockPlaybackController.pause() }
        assertFalse(controller.state.value.isActive)
    }

    @Test
    fun givenActiveEndOfChapterTimer_whenCancelled_thenPauseNeverCalledAndStateReset() = runTest {
        playerStateFlow.value = PlayerState(durationMs = 10_000L, currentPositionMs = 0L, currentMediaItemIndex = 0)
        val controller = SleepTimerController(mockPlaybackController, this)
        controller.startEndOfChapter()

        controller.cancel()
        playerStateFlow.value = PlayerState(durationMs = 5_000L, currentPositionMs = 0L, currentMediaItemIndex = 1)
        advanceTimeBy(1_000L.milliseconds)
        runCurrent()

        verify(exactly = 0) { mockPlaybackController.pause() }
        assertFalse(controller.state.value.isActive)
    }

    @Test
    fun givenSingleTrackBook_whenTrackNaturallyEndsAtSameIndex_thenPlaybackPausedAndTimerReset() = runTest {
        // Single-file books never transition to a "next" media item index, so index-change alone
        // can't detect the end - this covers the fix for that case.
        playerStateFlow.value = PlayerState(
            durationMs = 10_000L,
            currentPositionMs = 9_000L,
            currentMediaItemIndex = 0,
            isPlaying = true,
        )
        val controller = SleepTimerController(mockPlaybackController, this)
        controller.startEndOfChapter()

        playerStateFlow.value = PlayerState(
            durationMs = 10_000L,
            currentPositionMs = 10_000L,
            currentMediaItemIndex = 0,
            isPlaying = false,
        )
        advanceTimeBy(1_000L.milliseconds)
        runCurrent()

        verify(exactly = 1) { mockPlaybackController.pause() }
        assertFalse(controller.state.value.isActive)
    }

    @Test
    fun givenActiveEndOfChapterTimer_whenActiveBookChanges_thenTimerSilentlyDroppedWithoutPausingNewBook() = runTest {
        playerStateFlow.value = PlayerState(durationMs = 10_000L, currentPositionMs = 0L, currentMediaItemIndex = 0)
        val controller = SleepTimerController(mockPlaybackController, this)
        controller.startEndOfChapter()

        // User closed this book and opened a different one - the global playback state now
        // reflects the new book, coincidentally at the same track index.
        currentBookIdFlow.value = 2L
        playerStateFlow.value = PlayerState(durationMs = 45_000L, currentPositionMs = 0L, currentMediaItemIndex = 0)
        advanceTimeBy(1_000L.milliseconds)
        runCurrent()

        verify(exactly = 0) { mockPlaybackController.pause() }
        assertFalse(controller.state.value.isActive)
    }
}
