package com.dalapenko.laba.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String? = null,
    val coverUri: String? = null,
    val rootFolderUri: String,
    val totalDurationMs: Long,
)
