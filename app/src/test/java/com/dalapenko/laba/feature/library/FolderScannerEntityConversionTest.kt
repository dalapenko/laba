package com.dalapenko.laba.feature.library

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FolderScannerEntityConversionTest {

    private val mockContext = mockk<Context>(relaxed = true)
    private lateinit var scanner: FolderScanner

    @Before
    fun setup() {
        scanner = FolderScanner(mockContext)
    }

    // ── toBookEntity ──────────────────────────────────────────────────────────

    @Test
    fun givenScannedBook_whenConvertingToEntity_thenAllFieldsMapped() {
        val scanned = ScannedBook(
            title = "My Audiobook",
            author = "Jane Doe",
            rootUri = "content://test/tree/book1",
            tracks = listOf(
                ScannedTrack("content://test/t1.mp3", "t1.mp3", 60_000L, 0),
                ScannedTrack("content://test/t2.mp3", "t2.mp3", 90_000L, 1),
            ),
            coverUri = "file:///data/covers/book1.jpg",
        )

        val entity = scanner.toBookEntity(scanned)

        assertEquals("My Audiobook", entity.title)
        assertEquals("Jane Doe", entity.author)
        assertEquals("content://test/tree/book1", entity.rootFolderUri)
        assertEquals("file:///data/covers/book1.jpg", entity.coverUri)
        assertEquals(150_000L, entity.totalDurationMs)
        assertTrue(entity.addedAt > 0L)
    }

    @Test
    fun givenScannedBookWithTracks_whenConvertingToEntity_thenTotalDurationIsSumOfTracks() {
        val scanned = ScannedBook(
            title = "Book",
            rootUri = "content://test/book",
            tracks = listOf(
                ScannedTrack("content://t1", "t1.mp3", 30_000L, 0),
                ScannedTrack("content://t2", "t2.mp3", 45_000L, 1),
                ScannedTrack("content://t3", "t3.mp3", 25_000L, 2),
            ),
        )

        val entity = scanner.toBookEntity(scanned)

        assertEquals(100_000L, entity.totalDurationMs)
    }

    @Test
    fun givenScannedBookWithNullAuthor_whenConvertingToEntity_thenAuthorIsNull() {
        val scanned = ScannedBook(
            title = "Book",
            author = null,
            rootUri = "content://test/book",
            tracks = listOf(ScannedTrack("content://t1", "t1.mp3", 1000L, 0)),
        )

        val entity = scanner.toBookEntity(scanned)

        assertNull(entity.author)
    }

    // ── toTrackEntities ───────────────────────────────────────────────────────

    @Test
    fun givenScannedBook_whenConvertingTracks_thenAllFieldsMapped() {
        val scanned = ScannedBook(
            title = "Book",
            rootUri = "content://test/book",
            tracks = listOf(
                ScannedTrack("content://t1.mp3", "Chapter 1.mp3", 60_000L, 0),
                ScannedTrack("content://t2.mp3", "Chapter 2.mp3", 90_000L, 1),
            ),
        )

        val tracks = scanner.toTrackEntities(scanned, bookId = 42L)

        assertEquals(2, tracks.size)
        assertEquals("content://t1.mp3", tracks[0].fileUri)
        assertEquals("Chapter 1.mp3", tracks[0].fileName)
        assertEquals(60_000L, tracks[0].durationMs)
        assertEquals(0, tracks[0].sequenceOrder)
        assertEquals(42L, tracks[0].bookId)
        assertEquals(42L, tracks[1].bookId)
        assertEquals(1, tracks[1].sequenceOrder)
    }

    @Test
    fun givenScannedBook_whenConvertingTracksWithDefaultBookId_thenBookIdIsZero() {
        val scanned = ScannedBook(
            title = "Book",
            rootUri = "content://test/book",
            tracks = listOf(ScannedTrack("content://t1.mp3", "t1.mp3", 1000L, 0)),
        )

        val tracks = scanner.toTrackEntities(scanned)

        assertEquals(0L, tracks[0].bookId)
    }

    @Test
    fun givenScannedBookWithCustomBookId_whenConvertingTracks_thenAllTracksHaveCorrectBookId() {
        val scanned = ScannedBook(
            title = "Book",
            rootUri = "content://test/book",
            tracks = listOf(
                ScannedTrack("content://t1.mp3", "t1.mp3", 1000L, 0),
                ScannedTrack("content://t2.mp3", "t2.mp3", 2000L, 1),
                ScannedTrack("content://t3.mp3", "t3.mp3", 3000L, 2),
            ),
        )

        val tracks = scanner.toTrackEntities(scanned, bookId = 99L)

        assertEquals(3, tracks.size)
        assertTrue(tracks.all { it.bookId == 99L })
    }
}
