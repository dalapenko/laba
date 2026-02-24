package com.dalapenko.laba.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "books",
    indices = [Index(value = ["rootFolderUri"], unique = true)],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String? = null,
    val coverUri: String? = null,
    val rootFolderUri: String,
    val totalDurationMs: Long,
    val isAvailable: Boolean = true,
)
