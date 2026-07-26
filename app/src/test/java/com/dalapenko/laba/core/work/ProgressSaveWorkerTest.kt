package com.dalapenko.laba.core.work

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.dalapenko.laba.core.data.ProgressRepository
import com.dalapenko.laba.core.database.entity.ProgressEntity
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class ProgressSaveWorkerTest {

    private val mockRepository = mockk<ProgressRepository>()
    private val mockContext = mockk<Context>(relaxed = true)

    @Before
    fun setup() {
        coJustRun { mockRepository.saveProgress(any()) }
        startKoin {
            modules(module { single { mockRepository } })
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun buildWorker(inputData: Data): ProgressSaveWorker =
        TestListenableWorkerBuilder<ProgressSaveWorker>(mockContext)
            .setInputData(inputData)
            .build()

    @Test
    fun givenValidInputData_whenDoWork_thenSavesProgressAndSucceeds() = runTest {
        val inputData = Data.Builder()
            .putLong(ProgressSaveWorker.KEY_BOOK_ID, 1L)
            .putLong(ProgressSaveWorker.KEY_LAST_TRACK_ID, 2L)
            .putLong(ProgressSaveWorker.KEY_LAST_POSITION_MS, 30_000L)
            .putLong(ProgressSaveWorker.KEY_COMPLETED_TRACKS_MS, 15_000L)
            .putFloat(ProgressSaveWorker.KEY_PLAYBACK_SPEED, 1.5f)
            .putBoolean(ProgressSaveWorker.KEY_IS_COMPLETED, false)
            .build()
        val worker = buildWorker(inputData)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val savedSlot = slot<ProgressEntity>()
        coVerify { mockRepository.saveProgress(capture(savedSlot)) }
        assertEquals(1L, savedSlot.captured.bookId)
        assertEquals(2L, savedSlot.captured.lastTrackId)
        assertEquals(30_000L, savedSlot.captured.lastPositionMs)
        assertEquals(15_000L, savedSlot.captured.completedTracksMs)
        assertEquals(1.5f, savedSlot.captured.playbackSpeed)
        assertEquals(false, savedSlot.captured.isCompleted)
    }

    @Test
    fun givenMissingBookId_whenDoWork_thenFailsWithoutSavingProgress() = runTest {
        val inputData = Data.Builder()
            .putLong(ProgressSaveWorker.KEY_LAST_TRACK_ID, 2L)
            .putLong(ProgressSaveWorker.KEY_LAST_POSITION_MS, 30_000L)
            .build()
        val worker = buildWorker(inputData)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        coVerify(exactly = 0) { mockRepository.saveProgress(any()) }
    }

    @Test
    fun givenMissingLastTrackId_whenDoWork_thenFailsWithoutSavingProgress() = runTest {
        val inputData = Data.Builder()
            .putLong(ProgressSaveWorker.KEY_BOOK_ID, 1L)
            .putLong(ProgressSaveWorker.KEY_LAST_POSITION_MS, 30_000L)
            .build()
        val worker = buildWorker(inputData)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        coVerify(exactly = 0) { mockRepository.saveProgress(any()) }
    }

    @Test
    fun givenMissingOptionalFields_whenDoWork_thenDefaultsAreApplied() = runTest {
        val inputData = Data.Builder()
            .putLong(ProgressSaveWorker.KEY_BOOK_ID, 5L)
            .putLong(ProgressSaveWorker.KEY_LAST_TRACK_ID, 6L)
            .build()
        val worker = buildWorker(inputData)

        worker.doWork()

        val savedSlot = slot<ProgressEntity>()
        coVerify { mockRepository.saveProgress(capture(savedSlot)) }
        assertEquals(0L, savedSlot.captured.lastPositionMs)
        assertEquals(0L, savedSlot.captured.completedTracksMs)
        assertEquals(1f, savedSlot.captured.playbackSpeed)
        assertEquals(false, savedSlot.captured.isCompleted)
    }

    @Test
    fun givenCompletedFlagSet_whenDoWork_thenSavedProgressIsMarkedCompleted() = runTest {
        val inputData = Data.Builder()
            .putLong(ProgressSaveWorker.KEY_BOOK_ID, 1L)
            .putLong(ProgressSaveWorker.KEY_LAST_TRACK_ID, 2L)
            .putBoolean(ProgressSaveWorker.KEY_IS_COMPLETED, true)
            .build()
        val worker = buildWorker(inputData)

        worker.doWork()

        val savedSlot = slot<ProgressEntity>()
        coVerify { mockRepository.saveProgress(capture(savedSlot)) }
        assertEquals(true, savedSlot.captured.isCompleted)
    }
}
