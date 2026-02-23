package com.dalapenko.laba.di

import com.dalapenko.laba.core.media.PlaybackController
import com.dalapenko.laba.core.media.PlaybackError
import com.dalapenko.laba.core.media.PlayerState
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.dsl.module

/**
 * Test module that provides a mocked PlaybackController for UI tests.
 *
 * This controller simulates basic playback behavior without requiring
 * actual media playback infrastructure.
 *
 * Uses MockK Android for mocking the final PlaybackController class.
 * Replaces the production PlaybackController module.
 */
val testPlaybackModule = module {
    single<PlaybackController> {
        android.util.Log.d("TEST_MOCK_CREATION", "==== Creating MOCKED PlaybackController ====")
        
        // Create mocked PlaybackController using MockK Android
        val mockController = mockk<PlaybackController>(relaxed = true)
        
        android.util.Log.d("TEST_MOCK_CREATION", "Mock instance class: ${mockController::class.java.name}")
        android.util.Log.d("TEST_MOCK_CREATION", "Mock instance: $mockController")

        // Create mutable state flows that we can update in tests
        val currentBookIdFlow = MutableStateFlow<Long?>(null)
        val playerStateFlow = MutableStateFlow(
            PlayerState(
                isPlaying = false,
                currentPositionMs = 0L,
                durationMs = 180_000L, // 3 minutes default
                playbackSpeed = 1.0f,
                currentMediaItemIndex = 0,
                isReady = true
            )
        )
        val playbackErrorFlow = MutableSharedFlow<PlaybackError>(extraBufferCapacity = 1)

        // Mock the flow properties
        every { mockController.currentBookId } returns currentBookIdFlow
        every { mockController.playerState } returns playerStateFlow
        every { mockController.playbackError } returns playbackErrorFlow

        // Mock play/pause actions
        every { mockController.play() } answers {
            playerStateFlow.value = playerStateFlow.value.copy(isPlaying = true)
        }

        every { mockController.pause() } answers {
            playerStateFlow.value = playerStateFlow.value.copy(isPlaying = false)
        }

        // Mock seek operations
        every { mockController.seekTo(any()) } answers {
            val positionMs = firstArg<Long>()
            playerStateFlow.value = playerStateFlow.value.copy(currentPositionMs = positionMs)
        }

        every { mockController.seekForward() } answers {
            val currentPos = playerStateFlow.value.currentPositionMs
            val newPos = (currentPos + 10_000L).coerceAtMost(playerStateFlow.value.durationMs)
            playerStateFlow.value = playerStateFlow.value.copy(currentPositionMs = newPos)
        }

        every { mockController.seekBack() } answers {
            val currentPos = playerStateFlow.value.currentPositionMs
            val newPos = (currentPos - 10_000L).coerceAtLeast(0L)
            playerStateFlow.value = playerStateFlow.value.copy(currentPositionMs = newPos)
        }

        // Mock speed control
        every { mockController.setSpeed(any()) } answers {
            val speed = firstArg<Float>()
            playerStateFlow.value = playerStateFlow.value.copy(playbackSpeed = speed)
        }

        // Mock track navigation
        every { mockController.seekToTrack(any(), any()) } answers {
            val trackIndex = firstArg<Int>()
            val positionMs = secondArg<Long>()
            playerStateFlow.value = playerStateFlow.value.copy(
                currentMediaItemIndex = trackIndex,
                currentPositionMs = positionMs
            )
        }

        // Mock playlist operations
        every { mockController.setPlaylist(any(), any()) } answers {
            val bookId = secondArg<Long>()
            currentBookIdFlow.value = bookId
            playerStateFlow.value = playerStateFlow.value.copy(
                currentMediaItemIndex = 0,
                currentPositionMs = 0L,
                isReady = true
            )
        }

        // Mock initial state
        every { mockController.setInitialState(any(), any(), any(), any()) } answers {
            val positionMs = firstArg<Long>()
            val durationMs = secondArg<Long>()
            val trackIndex = thirdArg<Int>()
            val speed = arg<Float>(3)

            playerStateFlow.value = PlayerState(
                isPlaying = false,
                currentPositionMs = positionMs,
                durationMs = durationMs,
                playbackSpeed = speed,
                currentMediaItemIndex = trackIndex,
                isReady = true
            )
        }

        // Mock metadata operations
        every { mockController.setBookMetadata(any(), any(), any()) } just runs

        // Mock lifecycle methods
        every { mockController.connect() } just runs
        every { mockController.stop() } answers {
            currentBookIdFlow.value = null
            playerStateFlow.value = PlayerState(
                isPlaying = false,
                currentPositionMs = 0L,
                durationMs = 0L,
                playbackSpeed = 1.0f,
                currentMediaItemIndex = 0,
                isReady = false
            )
        }

        // Mock snapshot operations
        every { mockController.captureCurrentBookState() } just runs
        every { mockController.getBookSnapshot(any()) } returns null
        every { mockController.updateBookSnapshot(any(), any()) } just runs
        every { mockController.cleanupOldSnapshots(any()) } just runs

        mockController
    }
}
