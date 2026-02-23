package com.dalapenko.laba.feature.library

import app.cash.turbine.test
import com.dalapenko.laba.core.database.dao.BookDao
import com.dalapenko.laba.core.database.dao.ProgressDao
import com.dalapenko.laba.core.database.dao.TrackDao
import com.dalapenko.laba.core.database.entity.BookEntity
import com.dalapenko.laba.core.database.entity.ProgressEntity
import com.dalapenko.laba.testBook
import com.dalapenko.laba.testProgress
import com.dalapenko.laba.testTrack
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BookRepositoryTest {

    private val bookDao = mockk<BookDao>()
    private val trackDao = mockk<TrackDao>()
    private val progressDao = mockk<ProgressDao>()
    private lateinit var repository: BookRepository

    private val booksFlow = MutableStateFlow<List<BookEntity>>(emptyList())
    private val allProgressFlow = MutableStateFlow<List<ProgressEntity>>(emptyList())

    @Before
    fun setup() {
        every { bookDao.observeAll() } returns booksFlow
        every { progressDao.observeAll() } returns allProgressFlow
        repository = BookRepository(bookDao, trackDao, progressDao)
    }

    // ── addBookIfNew ──────────────────────────────────────────────────────────

    @Test
    fun givenNewBook_whenAddBookIfNew_thenInsertsAndReturnsPositiveId() = runTest {
        val book = testBook()
        coEvery { bookDao.existsByRootUri(book.rootFolderUri) } returns false
        coEvery { bookDao.insert(book) } returns 5L
        coJustRun { trackDao.insertAll(any()) }

        val result = repository.addBookIfNew(book, emptyList())

        assertEquals(5L, result)
    }

    @Test
    fun givenExistingBook_whenAddBookIfNew_thenReturnsMinusOneAndSkipsInsert() = runTest {
        val book = testBook()
        coEvery { bookDao.existsByRootUri(book.rootFolderUri) } returns true

        val result = repository.addBookIfNew(book, emptyList())

        assertEquals(-1L, result)
        coVerify(exactly = 0) { bookDao.insert(any()) }
        coVerify(exactly = 0) { trackDao.insertAll(any()) }
    }

    // ── addBook ───────────────────────────────────────────────────────────────

    @Test
    fun givenBookWithTracks_whenAddBook_thenTracksGetAssignedReturnedBookId() = runTest {
        val book = testBook()
        val tracks = listOf(
            testTrack(id = 0, bookId = 0, sequenceOrder = 0),
            testTrack(id = 0, bookId = 0, sequenceOrder = 1),
        )
        coEvery { bookDao.insert(book) } returns 7L
        coJustRun { trackDao.insertAll(any()) }

        repository.addBook(book, tracks)

        coVerify { trackDao.insertAll(match { list -> list.all { it.bookId == 7L } }) }
    }

    // ── getBookWithTracks ─────────────────────────────────────────────────────

    @Test
    fun givenNonExistentBookId_whenGetBookWithTracks_thenReturnsNull() = runTest {
        coEvery { bookDao.getById(99L) } returns null

        val result = repository.getBookWithTracks(99L)

        assertNull(result)
    }

    @Test
    fun givenExistingBook_whenGetBookWithTracks_thenReturnsPairWithTracks() = runTest {
        val book = testBook(id = 1L)
        val tracks = listOf(testTrack(bookId = 1L))
        coEvery { bookDao.getById(1L) } returns book
        coEvery { trackDao.getByBook(1L) } returns tracks

        val result = repository.getBookWithTracks(1L)

        assertEquals(book, result?.first)
        assertEquals(tracks, result?.second)
    }

    // ── computeProgressFraction (tested indirectly via observeAllBooksWithProgress) ──

    @Test
    fun givenNoProgress_whenObservingBooksWithProgress_thenProgressFractionIsZero() = runTest {
        booksFlow.value = listOf(testBook(id = 1L, totalDurationMs = 100_000L))
        allProgressFlow.value = emptyList()

        repository.observeAllBooksWithProgress().test {
            assertEquals(0f, awaitItem()[0].progressFraction)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun givenCompletedProgress_whenObservingBooksWithProgress_thenProgressFractionIsOne() = runTest {
        booksFlow.value = listOf(testBook(id = 1L, totalDurationMs = 100_000L))
        allProgressFlow.value = listOf(testProgress(bookId = 1L, isCompleted = true))

        repository.observeAllBooksWithProgress().test {
            assertEquals(1f, awaitItem()[0].progressFraction)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun givenBookWithZeroDuration_whenObservingBooksWithProgress_thenProgressFractionIsZero() = runTest {
        booksFlow.value = listOf(testBook(id = 1L, totalDurationMs = 0L))
        allProgressFlow.value = listOf(testProgress(bookId = 1L, lastPositionMs = 50_000L))

        repository.observeAllBooksWithProgress().test {
            assertEquals(0f, awaitItem()[0].progressFraction)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun givenHalfwayProgress_whenObservingBooksWithProgress_thenProgressFractionIsHalf() = runTest {
        // completedTracksMs=30000 + lastPositionMs=20000 = 50000 out of 100000 = 0.5
        booksFlow.value = listOf(testBook(id = 1L, totalDurationMs = 100_000L))
        allProgressFlow.value = listOf(
            testProgress(bookId = 1L, completedTracksMs = 30_000L, lastPositionMs = 20_000L)
        )

        repository.observeAllBooksWithProgress().test {
            assertEquals(0.5f, awaitItem()[0].progressFraction)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun givenProgressExceedingDuration_whenObservingBooksWithProgress_thenProgressFractionClampedToOne() = runTest {
        booksFlow.value = listOf(testBook(id = 1L, totalDurationMs = 100_000L))
        allProgressFlow.value = listOf(
            testProgress(bookId = 1L, completedTracksMs = 80_000L, lastPositionMs = 30_000L)
        )

        repository.observeAllBooksWithProgress().test {
            assertEquals(1f, awaitItem()[0].progressFraction)
            cancelAndConsumeRemainingEvents()
        }
    }

    // ── recheckAllAvailability ────────────────────────────────────────────────

    @Test
    fun givenBookBecameUnavailable_whenRecheckAllAvailability_thenDaoUpdated() = runTest {
        val book = testBook(id = 1L, isAvailable = true)
        val scanner = mockk<FolderScanner>()
        coEvery { bookDao.getAll() } returns listOf(book)
        coEvery { scanner.isBookAvailable(book.rootFolderUri) } returns false
        coJustRun { bookDao.updateAvailability(any(), any()) }

        repository.recheckAllAvailability(scanner)

        coVerify { bookDao.updateAvailability(1L, false) }
    }

    @Test
    fun givenAllBooksStillAvailable_whenRecheckAllAvailability_thenNoDaoUpdates() = runTest {
        val book = testBook(id = 1L, isAvailable = true)
        val scanner = mockk<FolderScanner>()
        coEvery { bookDao.getAll() } returns listOf(book)
        coEvery { scanner.isBookAvailable(book.rootFolderUri) } returns true

        repository.recheckAllAvailability(scanner)

        coVerify(exactly = 0) { bookDao.updateAvailability(any(), any()) }
    }

    @Test
    fun givenBookRestoredFromUnavailable_whenRecheckAllAvailability_thenDaoUpdatedToAvailable() = runTest {
        val book = testBook(id = 1L, isAvailable = false)
        val scanner = mockk<FolderScanner>()
        coEvery { bookDao.getAll() } returns listOf(book)
        coEvery { scanner.isBookAvailable(book.rootFolderUri) } returns true
        coJustRun { bookDao.updateAvailability(any(), any()) }

        repository.recheckAllAvailability(scanner)

        coVerify { bookDao.updateAvailability(1L, true) }
    }
}
