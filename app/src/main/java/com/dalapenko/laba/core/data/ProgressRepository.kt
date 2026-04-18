package com.dalapenko.laba.core.data

import com.dalapenko.laba.core.database.dao.ProgressDao
import com.dalapenko.laba.core.database.entity.ProgressEntity
import kotlinx.coroutines.flow.Flow

class ProgressRepository(private val progressDao: ProgressDao) {

    suspend fun saveProgress(progress: ProgressEntity) {
        progressDao.upsert(progress)
    }

    suspend fun getProgress(bookId: Long): ProgressEntity? =
        progressDao.getByBook(bookId)

    fun observeLastPlayedBookId(): Flow<Long?> =
        progressDao.observeLastPlayedBookId()
}
