package com.dalapenko.laba.feature.library

import com.dalapenko.laba.core.database.dao.BookDao
import com.dalapenko.laba.core.database.dao.ProgressDao
import com.dalapenko.laba.core.database.dao.TrackDao
import com.dalapenko.laba.core.database.entity.BookEntity
import com.dalapenko.laba.core.database.entity.ProgressEntity
import com.dalapenko.laba.core.database.entity.TrackEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class BookWithProgress(
    val book: BookEntity,
    val progress: ProgressEntity?,
    val progressFraction: Float,
    val isAvailable: Boolean = book.isAvailable,
)

class BookRepository(
    private val bookDao: BookDao,
    private val trackDao: TrackDao,
    private val progressDao: ProgressDao,
) {
    fun observeAllBooksWithProgress(): Flow<List<BookWithProgress>> =
        combine(bookDao.observeAll(), progressDao.observeAll()) { books, allProgress ->
            val progressMap = allProgress.associateBy { it.bookId }
            books.map { book ->
                val progress = progressMap[book.id]
                BookWithProgress(
                    book = book,
                    progress = progress,
                    progressFraction = computeProgressFraction(book, progress),
                    isAvailable = book.isAvailable,
                )
            }
        }

    private fun computeProgressFraction(book: BookEntity, progress: ProgressEntity?): Float {
        if (progress == null) return 0f
        if (progress.isCompleted) return 1f
        if (book.totalDurationMs <= 0) return 0f
        val absolute = progress.completedTracksMs + progress.lastPositionMs
        return (absolute.toFloat() / book.totalDurationMs).coerceIn(0f, 1f)
    }

    suspend fun getBookWithTracks(bookId: Long): Pair<BookEntity, List<TrackEntity>>? {
        val book = bookDao.getById(bookId) ?: return null
        val tracks = trackDao.getByBook(bookId)
        return book to tracks
    }

    suspend fun addBook(book: BookEntity, tracks: List<TrackEntity>): Long {
        val bookId = bookDao.insert(book)
        val tracksWithBookId = tracks.map { it.copy(bookId = bookId) }
        trackDao.insertAll(tracksWithBookId)
        return bookId
    }

    suspend fun addBookIfNew(book: BookEntity, tracks: List<TrackEntity>): Long {
        if (bookDao.existsByRootUri(book.rootFolderUri)) return -1L
        return addBook(book, tracks)
    }

    suspend fun saveProgress(progress: ProgressEntity) {
        progressDao.upsert(progress)
    }

    suspend fun getProgress(bookId: Long): ProgressEntity? =
        progressDao.getByBook(bookId)

    suspend fun getLastPlayedBookId(): Long? =
        progressDao.getLastPlayed()?.bookId

    suspend fun getAllBooks(): List<BookEntity> = bookDao.getAll()

    suspend fun getBooksWithoutCover(): List<BookEntity> = bookDao.getBooksWithoutCover()

    suspend fun updateBookMeta(book: BookEntity) = bookDao.update(book)

    suspend fun deleteBook(book: BookEntity) {
        bookDao.delete(book)
    }

    suspend fun getBookById(bookId: Long): BookEntity? = bookDao.getById(bookId)

    suspend fun setBookAvailability(bookId: Long, isAvailable: Boolean) =
        bookDao.updateAvailability(bookId, isAvailable)

    suspend fun recheckAllAvailability(scanner: FolderScanner) {
        val books = bookDao.getAll()
        for (book in books) {
            val available = scanner.isBookAvailable(book.rootFolderUri)
            if (available != book.isAvailable) {
                bookDao.updateAvailability(book.id, available)
            }
        }
    }
}
