package com.dalapenko.laba.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.dalapenko.laba.core.database.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE bookId = :bookId ORDER BY sequenceOrder ASC")
    fun observeByBook(bookId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE bookId = :bookId ORDER BY sequenceOrder ASC")
    suspend fun getByBook(bookId: Long): List<TrackEntity>

    @Insert
    suspend fun insertAll(tracks: List<TrackEntity>)
}
