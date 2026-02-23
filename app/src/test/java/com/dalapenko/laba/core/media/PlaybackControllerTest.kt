package com.dalapenko.laba.core.media

import android.content.Context
import com.dalapenko.laba.MainDispatcherRule
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for PlaybackController business logic that does NOT require a real MediaController.
 * Tests focus on: state snapshot CRUD, setInitialState, setPlaylist (bookId side-effect), and stop.
 *
 * Methods requiring a live MediaSession (play, pause, seekTo, etc.) are integration-test territory.
 */
class PlaybackControllerTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val mockContext = mockk<Context>(relaxed = true)
    private lateinit var controller: PlaybackController

    @Before
    fun setup() {
        // Dispatcher.Main is replaced by UnconfinedTestDispatcher before this runs (via Rule).
        controller = PlaybackController(mockContext)
    }

    // ── setInitialState ───────────────────────────────────────────────────────

    @Test
    fun givenCallToSetInitialState_whenCalled_thenPlayerStateReflectsValues() {
        controller.setInitialState(
            position = 15_000L,
            duration = 60_000L,
            trackIndex = 2,
            speed = 1.5f,
        )

        val state = controller.playerState.value
        assertEquals(15_000L, state.currentPositionMs)
        assertEquals(60_000L, state.durationMs)
        assertEquals(2, state.currentMediaItemIndex)
        assertEquals(1.5f, state.playbackSpeed)
    }

    // ── setPlaylist / currentBookId side-effect ───────────────────────────────

    @Test
    fun givenSetPlaylist_whenCalledWithBookId_thenCurrentBookIdUpdated() {
        controller.setPlaylist(emptyList(), bookId = 42L)

        assertEquals(42L, controller.currentBookId.value)
    }

    @Test
    fun givenTwoDifferentBookIds_whenSetPlaylistCalledTwice_thenCurrentBookIdIsLast() {
        controller.setPlaylist(emptyList(), bookId = 1L)
        controller.setPlaylist(emptyList(), bookId = 2L)

        assertEquals(2L, controller.currentBookId.value)
    }

    // ── stop ─────────────────────────────────────────────────────────────────

    @Test
    fun givenActiveBook_whenStop_thenCurrentBookIdIsNull() {
        controller.setPlaylist(emptyList(), bookId = 10L)

        controller.stop()

        assertNull(controller.currentBookId.value)
    }

    // ── Book state snapshots ──────────────────────────────────────────────────

    @Test
    fun givenNoSnapshot_whenGetBookSnapshot_thenReturnsNull() {
        val snapshot = controller.getBookSnapshot(999L)

        assertNull(snapshot)
    }

    @Test
    fun givenUpdatedSnapshot_whenGetBookSnapshot_thenReturnsStoredState() {
        val state = PlayerState(
            isPlaying = false,
            currentPositionMs = 12_000L,
            durationMs = 60_000L,
            currentMediaItemIndex = 1,
            playbackSpeed = 1.0f,
        )

        controller.updateBookSnapshot(bookId = 5L, state = state)

        assertEquals(state, controller.getBookSnapshot(5L))
    }

    @Test
    fun givenSnapshotWrittenTwice_whenGetBookSnapshot_thenReturnsLatestState() {
        val firstState = PlayerState(currentPositionMs = 1_000L)
        val secondState = PlayerState(currentPositionMs = 2_000L)

        controller.updateBookSnapshot(bookId = 1L, state = firstState)
        controller.updateBookSnapshot(bookId = 1L, state = secondState)

        assertEquals(secondState, controller.getBookSnapshot(1L))
    }

    @Test
    fun givenMultipleBookSnapshots_whenCleanupWithSubsetOfIds_thenOnlyActiveIdsRemain() {
        controller.updateBookSnapshot(1L, PlayerState(currentPositionMs = 1_000L))
        controller.updateBookSnapshot(2L, PlayerState(currentPositionMs = 2_000L))
        controller.updateBookSnapshot(3L, PlayerState(currentPositionMs = 3_000L))

        controller.cleanupOldSnapshots(activeBookIds = setOf(2L))

        assertNull(controller.getBookSnapshot(1L))
        assertNotNull(controller.getBookSnapshot(2L))
        assertNull(controller.getBookSnapshot(3L))
    }

    @Test
    fun givenNoActiveIds_whenCleanupOldSnapshots_thenAllSnapshotsRemoved() {
        controller.updateBookSnapshot(1L, PlayerState())
        controller.updateBookSnapshot(2L, PlayerState())

        controller.cleanupOldSnapshots(activeBookIds = emptySet())

        assertNull(controller.getBookSnapshot(1L))
        assertNull(controller.getBookSnapshot(2L))
    }

    // ── captureCurrentBookState ───────────────────────────────────────────────

    @Test
    fun givenNoActiveBook_whenCaptureCurrentBookState_thenNoSnapshotStored() {
        // currentBookId is null by default — capture should be a no-op
        controller.captureCurrentBookState()

        // No book ID to query, so any lookup returns null
        assertNull(controller.getBookSnapshot(0L))
    }

    @Test
    fun givenActiveBookWithState_whenCaptureCurrentBookState_thenSnapshotMatchesCurrentState() {
        controller.setPlaylist(emptyList(), bookId = 7L)
        controller.setInitialState(position = 5_000L, duration = 30_000L, trackIndex = 0, speed = 1.0f)

        controller.captureCurrentBookState()

        val snapshot = controller.getBookSnapshot(7L)
        assertNotNull(snapshot)
        assertEquals(5_000L, snapshot!!.currentPositionMs)
    }
}
