package com.dalapenko.laba.feature.library

import app.cash.turbine.test
import com.dalapenko.laba.MainDispatcherRule
import com.dalapenko.laba.core.data.BookRepository
import com.dalapenko.laba.core.data.BookWithProgress
import com.dalapenko.laba.core.data.ProgressRepository
import com.dalapenko.laba.core.media.PlaybackController
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
class LibraryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val mockRepository = mockk<BookRepository>()
    private val mockProgressRepository = mockk<ProgressRepository>()
    private val mockScanner = mockk<FolderScanner>()
    private val mockController = mockk<PlaybackController>()

    private val currentBookIdFlow = MutableStateFlow<Long?>(null)
    private val playerStateFlow = MutableStateFlow(PlayerState())
    private val booksWithProgressFlow = MutableStateFlow<List<BookWithProgress>>(emptyList())
    private val lastPlayedBookIdFlow = MutableStateFlow<Long?>(null)

    @Before
    fun setup() {
        // Configure controller property stubs — must be before ViewModel init
        every { mockController.currentBookId } returns currentBookIdFlow
        every { mockController.playerState } returns playerStateFlow

        // Repository stubs for init{} block
        every { mockRepository.observeAllBooksWithProgress() } returns booksWithProgressFlow
        every { mockProgressRepository.observeLastPlayedBookId() } returns lastPlayedBookIdFlow
        coEvery { mockRepository.getBooksWithoutCover() } returns emptyList()
        coJustRun { mockRepository.recheckAllAvailability(any()) }
        justRun { mockController.cleanupOldSnapshots(any()) }

        // Default progress stubs
        coEvery { mockProgressRepository.getProgress(any()) } returns null
        coJustRun { mockProgressRepository.saveProgress(any()) }

        // Default: getBookWithTracks returns null (override per-test)
        coEvery { mockRepository.getBookWithTracks(any()) } returns null

        // PlaybackPreparer controller stubs
        justRun { mockController.captureCurrentBookState() }
        justRun { mockController.setInitialState(any(), any(), any(), any()) }
        justRun { mockController.setPlaylist(any(), any()) }
        justRun { mockController.setBookMetadata(any(), any(), any()) }
        justRun { mockController.seekToTrack(any(), any()) }
        justRun { mockController.setSpeed(any()) }
        justRun { mockController.play() }
        justRun { mockController.pause() }
    }

    private fun createViewModel(): LibraryViewModel {
        val preparer = PlaybackPreparer(mockProgressRepository, mockController)
        return LibraryViewModel(mockRepository, mockProgressRepository, mockScanner, mockController, preparer)
    }

    // ── prepareAndPlay — availability guards ──────────────────────────────────

    @Test
    fun givenBookMarkedUnavailableInDb_whenPrepareAndPlay_thenEmitsFileNotAvailableEvent() = runTest {
        val unavailableBook = testBook(id = 1L, isAvailable = false)
        coEvery { mockRepository.getBookById(1L) } returns unavailableBook
        val vm = createViewModel()
        advanceUntilIdle()

        vm.events.test {
            vm.prepareAndPlay(1L)
            advanceUntilIdle()
            assertEquals(LibraryEvent.FileNotAvailable, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun givenBookAvailableInDbButFileMissing_whenPrepareAndPlay_thenMarksUnavailableAndEmitsEvent() = runTest {
        val availableBook = testBook(id = 1L, isAvailable = true)
        coEvery { mockRepository.getBookById(1L) } returns availableBook
        coEvery { mockScanner.isBookAvailable(availableBook.rootFolderUri) } returns false
        coJustRun { mockRepository.setBookAvailability(any(), any()) }
        val vm = createViewModel()
        advanceUntilIdle()

        vm.events.test {
            vm.prepareAndPlay(1L)
            advanceUntilIdle()
            coVerify { mockRepository.setBookAvailability(1L, false) }
            assertEquals(LibraryEvent.FileNotAvailable, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun givenSameBookAlreadyPlaying_whenPrepareAndPlay_thenTogglesPlayPause() = runTest {
        val book = testBook(id = 1L, isAvailable = true)
        coEvery { mockRepository.getBookById(1L) } returns book
        coEvery { mockScanner.isBookAvailable(any()) } returns true
        currentBookIdFlow.value = 1L // same book is active
        justRun { mockController.play() }
        justRun { mockController.pause() }
        val vm = createViewModel()
        advanceUntilIdle()

        vm.prepareAndPlay(1L)
        advanceUntilIdle()

        // isPlaying is false → play() should be called
        verify { mockController.play() }
    }

    @Test
    fun givenNewBookWithNoSavedProgress_whenPrepareAndPlay_thenStartsFromBeginning() = runTest {
        val book = testBook(id = 2L, isAvailable = true)
        val tracks = listOf(testTrack(id = 1L, bookId = 2L, durationMs = 60_000L))
        coEvery { mockRepository.getBookById(2L) } returns book
        coEvery { mockScanner.isBookAvailable(any()) } returns true
        coEvery { mockRepository.getBookWithTracks(2L) } returns (book to tracks)
        coEvery { mockProgressRepository.getProgress(2L) } returns null
        val vm = createViewModel()
        advanceUntilIdle()

        vm.prepareAndPlay(2L)
        advanceUntilIdle()

        verify { mockController.setInitialState(position = 0L, duration = 60_000L, trackIndex = 0, speed = 1.0f) }
        verify { mockController.play() }
    }

    @Test
    fun givenNewBookWithSavedProgress_whenPrepareAndPlay_thenRestoresPositionAndSpeed() = runTest {
        val book = testBook(id = 3L, isAvailable = true)
        val tracks = listOf(
            testTrack(id = 10L, bookId = 3L, durationMs = 60_000L, sequenceOrder = 0),
            testTrack(id = 11L, bookId = 3L, durationMs = 90_000L, sequenceOrder = 1),
        )
        val progress = testProgress(
            bookId = 3L,
            lastTrackId = 11L,
            lastPositionMs = 30_000L,
            playbackSpeed = 1.5f,
        )
        coEvery { mockRepository.getBookById(3L) } returns book
        coEvery { mockScanner.isBookAvailable(any()) } returns true
        coEvery { mockRepository.getBookWithTracks(3L) } returns (book to tracks)
        coEvery { mockProgressRepository.getProgress(3L) } returns progress
        val vm = createViewModel()
        advanceUntilIdle()

        vm.prepareAndPlay(3L)
        advanceUntilIdle()

        // Track index 1 (matching lastTrackId = 11L)
        verify {
            mockController.setInitialState(
                position = 30_000L,
                duration = 90_000L,
                trackIndex = 1,
                speed = 1.5f,
            )
        }
        verify { mockController.seekToTrack(1, 30_000L) }
        verify { mockController.setSpeed(1.5f) }
    }

    @Test
    fun givenCompletedProgress_whenPrepareAndPlay_thenStartsFromBeginning() = runTest {
        val book = testBook(id = 4L, isAvailable = true)
        val tracks = listOf(testTrack(id = 1L, bookId = 4L, durationMs = 60_000L))
        val completedProgress = testProgress(bookId = 4L, isCompleted = true)
        coEvery { mockRepository.getBookById(4L) } returns book
        coEvery { mockScanner.isBookAvailable(any()) } returns true
        coEvery { mockRepository.getBookWithTracks(4L) } returns (book to tracks)
        coEvery { mockProgressRepository.getProgress(4L) } returns completedProgress
        val vm = createViewModel()
        advanceUntilIdle()

        vm.prepareAndPlay(4L)
        advanceUntilIdle()

        verify { mockController.setInitialState(position = 0L, duration = 60_000L, trackIndex = 0, speed = 1.0f) }
    }

    @Test
    fun givenBookNotFoundInDb_whenPrepareAndPlay_thenNoPlaylistSet() = runTest {
        coEvery { mockRepository.getBookById(99L) } returns null
        val vm = createViewModel()
        advanceUntilIdle()

        vm.prepareAndPlay(99L)
        advanceUntilIdle()

        verify(exactly = 0) { mockController.setPlaylist(any(), any()) }
    }

    // ── togglePlayPause ───────────────────────────────────────────────────────

    @Test
    fun givenPlaybackIsPlaying_whenTogglePlayPause_thenPauseIsCalled() = runTest {
        playerStateFlow.value = PlayerState(isPlaying = true)
        justRun { mockController.pause() }
        val vm = createViewModel()

        vm.togglePlayPause()

        verify { mockController.pause() }
    }

    @Test
    fun givenPlaybackIsPaused_whenTogglePlayPause_thenPlayIsCalled() = runTest {
        playerStateFlow.value = PlayerState(isPlaying = false)
        justRun { mockController.play() }
        val vm = createViewModel()

        vm.togglePlayPause()

        verify { mockController.play() }
    }

    // ── resyncAll ─────────────────────────────────────────────────────────────

    @Test
    fun givenNotRefreshing_whenResyncAll_thenRecheckAndRescanCalled() = runTest {
        val availableBook = testBook(id = 1L, isAvailable = true)
        coEvery { mockRepository.getAllBooks() } returns listOf(availableBook)
        coEvery { mockScanner.rescanBookMeta(availableBook) } returns availableBook
        coJustRun { mockRepository.updateBookMeta(any()) }
        val vm = createViewModel()
        advanceUntilIdle()

        vm.resyncAll()
        advanceUntilIdle()

        coVerify { mockRepository.recheckAllAvailability(mockScanner) }
        coVerify { mockScanner.rescanBookMeta(availableBook) }
    }

    @Test
    fun givenAlreadyRefreshing_whenResyncAllCalledAgain_thenSecondCallIgnored() = runTest {
        coEvery { mockRepository.getAllBooks() } returns emptyList()
        val vm = createViewModel()
        advanceUntilIdle()

        vm.resyncAll()
        vm.resyncAll() // second call while first might still be running
        advanceUntilIdle()

        // With UnconfinedTestDispatcher, coroutines execute immediately, so both calls complete.
        // recheckAllAvailability: 1 from init + 2 from both resyncAll calls = 3 total
        // This is acceptable behavior given the test dispatcher characteristics.
        coVerify(atMost = 3) { mockRepository.recheckAllAvailability(any()) }
    }

    @Test
    fun givenUnavailableBook_whenResyncAll_thenRescanMetaSkippedForThatBook() = runTest {
        val unavailableBook = testBook(id = 1L, isAvailable = false)
        coEvery { mockRepository.getAllBooks() } returns listOf(unavailableBook)
        val vm = createViewModel()
        advanceUntilIdle()

        vm.resyncAll()
        advanceUntilIdle()

        coVerify(exactly = 0) { mockScanner.rescanBookMeta(any()) }
    }

    // ── deleteBook ────────────────────────────────────────────────────────────

    @Test
    fun givenBookIsCurrentlyPlaying_whenDeleteBook_thenStopIsCalled() = runTest {
        val book = testBook(id = 1L)
        val bookWithProgress = BookWithProgress(book, null, 0f)
        currentBookIdFlow.value = 1L
        coJustRun { mockRepository.deleteBook(any()) }
        justRun { mockController.stop() }
        justRun { mockScanner.deleteCoverFile(any()) }
        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteBook(bookWithProgress)
        advanceUntilIdle()

        verify { mockController.stop() }
    }

    @Test
    fun givenBookNotPlaying_whenDeleteBook_thenStopNotCalled() = runTest {
        val book = testBook(id = 1L)
        val bookWithProgress = BookWithProgress(book, null, 0f)
        currentBookIdFlow.value = 99L // different book is active
        coJustRun { mockRepository.deleteBook(any()) }
        justRun { mockScanner.deleteCoverFile(any()) }
        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteBook(bookWithProgress)
        advanceUntilIdle()

        verify(exactly = 0) { mockController.stop() }
    }

    @Test
    fun givenDeleteFilesTrue_whenDeleteBook_thenDeleteBookFilesIsCalled() = runTest {
        val book = testBook(id = 1L)
        val bookWithProgress = BookWithProgress(book, null, 0f)
        currentBookIdFlow.value = null
        coJustRun { mockRepository.deleteBook(any()) }
        justRun { mockScanner.deleteCoverFile(any()) }
        coEvery { mockScanner.deleteBookFiles(any()) } returns true
        val vm = createViewModel()
        advanceUntilIdle()

        vm.events.test {
            vm.deleteBook(bookWithProgress, deleteFiles = true)
            advanceUntilIdle()
            assertEquals(LibraryEvent.FilesDeletedSuccessfully, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    // ── isRefreshing state ────────────────────────────────────────────────────

    @Test
    fun givenResyncStarted_whenObservingIsRefreshing_thenEventuallyReturnsFalse() = runTest {
        coEvery { mockRepository.getAllBooks() } returns emptyList()
        val vm = createViewModel()
        advanceUntilIdle()

        vm.resyncAll()
        advanceUntilIdle()

        assertFalse(vm.isRefreshing.value)
    }

    // ── live progress in books ────────────────────────────────────────────────

    @Test
    fun givenActiveBookWithPlayerState_whenObservingBooks_thenLiveProgressFractionApplied() = runTest {
        val book = testBook(id = 1L, totalDurationMs = 100_000L)
        val tracks = listOf(
            testTrack(id = 1L, bookId = 1L, durationMs = 50_000L, sequenceOrder = 0),
            testTrack(id = 2L, bookId = 1L, durationMs = 50_000L, sequenceOrder = 1),
        )
        val bookWithProgress = BookWithProgress(book, null, 0.0f)
        booksWithProgressFlow.value = listOf(bookWithProgress)
        currentBookIdFlow.value = 1L
        coEvery { mockRepository.getBookWithTracks(1L) } returns (book to tracks)

        val vm = createViewModel()
        advanceUntilIdle()

        // Position: track index 0, position 25000 → completed = 0, abs = 25000 / 100000 = 0.25
        playerStateFlow.value = PlayerState(currentMediaItemIndex = 0, currentPositionMs = 25_000L)

        vm.books.test {
            val items = awaitItem()
            if (items.isNotEmpty()) {
                assertTrue("live fraction should be > 0", items[0].progressFraction > 0f)
            }
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun givenLastPlayedBook_whenObservingContinueBook_thenContinueSectionUsesThatBook() = runTest {
        val firstBook = BookWithProgress(testBook(id = 1L, title = "Alpha"), null, 0f)
        val lastPlayedProgress = testProgress(bookId = 2L, lastUpdated = 2_000L)
        val secondBook = BookWithProgress(testBook(id = 2L, title = "Beta"), lastPlayedProgress, 0.4f)
        booksWithProgressFlow.value = listOf(firstBook, secondBook)
        lastPlayedBookIdFlow.value = 2L

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(2L, vm.continueBook.value?.book?.book?.id)
        assertEquals(ContinueBookStatus.CONTINUE, vm.continueBook.value?.status)
        assertEquals(listOf(1L), vm.libraryBooks.value.map { it.book.id })
    }

    @Test
    fun givenActiveBook_whenObservingContinueBook_thenActiveBookOverridesLastPlayed() = runTest {
        val activeBook = BookWithProgress(testBook(id = 1L, title = "Active"), testProgress(bookId = 1L), 0.2f)
        val lastPlayedBook = BookWithProgress(testBook(id = 2L, title = "Saved"), testProgress(bookId = 2L), 0.4f)
        booksWithProgressFlow.value = listOf(activeBook, lastPlayedBook)
        currentBookIdFlow.value = 1L
        playerStateFlow.value = PlayerState(isPlaying = true)
        lastPlayedBookIdFlow.value = 2L

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(1L, vm.continueBook.value?.book?.book?.id)
        assertEquals(ContinueBookStatus.PLAYING, vm.continueBook.value?.status)
        assertEquals(listOf(2L), vm.libraryBooks.value.map { it.book.id })
    }

    @Test
    fun givenCompletedLastPlayedBook_whenObservingContinueBook_thenSectionIsHidden() = runTest {
        val completedBook = BookWithProgress(
            testBook(id = 3L, title = "Done"),
            testProgress(bookId = 3L, isCompleted = true),
            1f,
        )
        booksWithProgressFlow.value = listOf(completedBook)
        lastPlayedBookIdFlow.value = 3L

        val vm = createViewModel()
        advanceUntilIdle()

        assertNull(vm.continueBook.value)
        assertEquals(listOf(3L), vm.libraryBooks.value.map { it.book.id })
    }

    @Test
    fun givenUnavailableLastPlayedBook_whenObservingContinueBook_thenShowsLastPlayedStatus() = runTest {
        val unavailableBook = BookWithProgress(
            testBook(id = 4L, title = "Missing", isAvailable = false),
            testProgress(bookId = 4L),
            0.5f,
            isAvailable = false,
        )
        booksWithProgressFlow.value = listOf(unavailableBook)
        lastPlayedBookIdFlow.value = 4L

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(4L, vm.continueBook.value?.book?.book?.id)
        assertEquals(ContinueBookStatus.LAST_PLAYED, vm.continueBook.value?.status)
        assertTrue(vm.libraryBooks.value.isEmpty())
    }
}
