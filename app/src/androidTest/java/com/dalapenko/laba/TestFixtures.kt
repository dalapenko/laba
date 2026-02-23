package com.dalapenko.laba

import com.dalapenko.laba.core.database.entity.BookEntity
import com.dalapenko.laba.core.database.entity.ProgressEntity
import com.dalapenko.laba.core.database.entity.TrackEntity

/**
 * Test fixtures for creating test audiobook data
 */
object TestFixtures {

    fun testBook(
        id: Long = 1L,
        title: String = "The Hobbit",
        author: String? = "J.R.R. Tolkien",
        rootFolderUri: String = "content://test/audiobooks/$id",
        coverUri: String? = null,
        totalDurationMs: Long = 620_000L,
        isAvailable: Boolean = true
    ) = BookEntity(
        id = id,
        title = title,
        author = author,
        rootFolderUri = rootFolderUri,
        coverUri = coverUri,
        totalDurationMs = totalDurationMs,
        isAvailable = isAvailable
    )

    fun testTrack(
        id: Long,
        bookId: Long,
        fileName: String,
        sequenceOrder: Int,
        durationMs: Long,
        fileUri: String = "content://test/track/$id"
    ) = TrackEntity(
        id = id,
        bookId = bookId,
        fileName = fileName,
        fileUri = fileUri,
        durationMs = durationMs,
        sequenceOrder = sequenceOrder
    )

    /**
     * Pre-configured test data: 3-chapter audiobook
     * @return Data class containing book, tracks, and optional progress
     */
    fun threeChapterBook() = TestBookData(
        bookEntity = testBook(
            id = 1L,
            title = "The Hobbit",
            author = "J.R.R. Tolkien",
            totalDurationMs = 620_000L
        ),
        trackEntities = listOf(
            testTrack(
                id = 1L,
                bookId = 1L,
                fileName = "Chapter 1 - An Unexpected Party.mp3",
                sequenceOrder = 0,
                durationMs = 180_000L // 3 minutes
            ),
            testTrack(
                id = 2L,
                bookId = 1L,
                fileName = "Chapter 2 - Roast Mutton.mp3",
                sequenceOrder = 1,
                durationMs = 240_000L // 4 minutes
            ),
            testTrack(
                id = 3L,
                bookId = 1L,
                fileName = "Chapter 3 - A Short Rest.mp3",
                sequenceOrder = 2,
                durationMs = 200_000L // 3.33 minutes
            )
        ),
        progressEntity = null // No initial progress
    )

    data class TestBookData(
        val bookEntity: BookEntity,
        val trackEntities: List<TrackEntity>,
        val progressEntity: ProgressEntity?
    ) {
        /**
         * Helper to get track ID by index for cleaner test code.
         * Usage: book.trackId(1) instead of book.trackEntities[1].id
         */
        fun trackId(index: Int): Long = trackEntities[index].id
    }
}
