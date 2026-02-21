package com.dalapenko.laba.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val bookId: Long,
    val lastTrackId: Long,
    /** Position within the current track (for seeking on resume). */
    val lastPositionMs: Long,
    /** Sum of durations of all tracks completed before the current one. */
    val completedTracksMs: Long = 0L,
    val lastUpdated: Long,
    val isCompleted: Boolean = false,
    val playbackSpeed: Float = 1f,
)
