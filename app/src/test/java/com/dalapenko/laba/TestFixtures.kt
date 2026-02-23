package com.dalapenko.laba

import com.dalapenko.laba.core.database.entity.BookEntity
import com.dalapenko.laba.core.database.entity.ProgressEntity
import com.dalapenko.laba.core.database.entity.TrackEntity

fun testBook(
    id: Long = 1L,
    title: String = "Test Book",
    author: String? = "Test Author",
    totalDurationMs: Long = 300_000L,
    rootFolderUri: String = "content://test/tree/book1",
    coverUri: String? = null,
    isAvailable: Boolean = true,
) = BookEntity(
    id = id,
    title = title,
    author = author,
    coverUri = coverUri,
    rootFolderUri = rootFolderUri,
    totalDurationMs = totalDurationMs,
    isAvailable = isAvailable,
)

fun testTrack(
    id: Long = 1L,
    bookId: Long = 1L,
    fileUri: String = "content://test/track1.mp3",
    fileName: String = "Chapter 01.mp3",
    durationMs: Long = 100_000L,
    sequenceOrder: Int = 0,
) = TrackEntity(
    id = id,
    bookId = bookId,
    fileUri = fileUri,
    fileName = fileName,
    durationMs = durationMs,
    sequenceOrder = sequenceOrder,
)

fun testProgress(
    bookId: Long = 1L,
    lastTrackId: Long = 1L,
    lastPositionMs: Long = 50_000L,
    completedTracksMs: Long = 0L,
    lastUpdated: Long = 1_000_000L,
    isCompleted: Boolean = false,
    playbackSpeed: Float = 1f,
) = ProgressEntity(
    bookId = bookId,
    lastTrackId = lastTrackId,
    lastPositionMs = lastPositionMs,
    completedTracksMs = completedTracksMs,
    lastUpdated = lastUpdated,
    isCompleted = isCompleted,
    playbackSpeed = playbackSpeed,
)
