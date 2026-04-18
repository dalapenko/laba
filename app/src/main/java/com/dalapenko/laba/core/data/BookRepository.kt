package com.dalapenko.laba.core.data

import androidx.room.withTransaction
import com.dalapenko.laba.core.database.AppDatabase
import com.dalapenko.laba.core.database.dao.BookDao
import com.dalapenko.laba.core.database.dao.TrackDao
import com.dalapenko.laba.core.database.entity.BookEntity
import com.dalapenko.laba.core.database.entity.ProgressEntity
import com.dalapenko.laba.core.database.entity.TrackEntity
import com.dalapenko.laba.feature.library.FolderScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class BookWithProgress(
    val book: BookEntity,
    val progress: ProgressEntity?,
    val progressFraction: Float,
    val isAvailable: Boolean = book.isAvailable,
)

class BookRepository(
    private val database: AppDatabase,
    private val bookDao: BookDao,
    private val trackDao: TrackDao,
) {
    fun observeAllBooksWithProgress(): Flow<List<BookWithProgress>> {
        val progressDao = database.progressDao()
        return combine(bookDao.observeAll(), progressDao.observeAll()) { books, allProgress ->
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
    }

    private fun computeProgressFraction(book: BookEntity, progress: ProgressEntity?): Float =
        when {
            progress == null -> 0f
            progress.isCompleted -> 1f
            book.totalDurationMs <= 0 -> 0f
            else -> {
                val absolute = progress.completedTracksMs + progress.lastPositionMs
                (absolute.toFloat() / book.totalDurationMs).coerceIn(0f, 1f)
            }
        }

    suspend fun getBookWithTracks(bookId: Long): Pair<BookEntity, List<TrackEntity>>? {
        val book = bookDao.getById(bookId) ?: return null
        val tracks = trackDao.getByBook(bookId)
        return book to tracks
    }

    suspend fun addBook(book: BookEntity, tracks: List<TrackEntity>): Long =
        database.withTransaction {
            val bookId = bookDao.insert(book)
            val tracksWithBookId = tracks.map { it.copy(bookId = bookId) }
            trackDao.insertAll(tracksWithBookId)
            bookId
        }

    suspend fun addBookIfNew(book: BookEntity, tracks: List<TrackEntity>): Long? =
        database.withTransaction {
            // existsByRootUri check MUST be inside the same transaction as insert to prevent
            // a TOCTOU race: two concurrent calls could both pass the check and then both
            // attempt the insert, triggering UNIQUE constraint violation on rootFolderUri.
            if (bookDao.existsByRootUri(book.rootFolderUri)) return@withTransaction null
            val bookId = bookDao.insert(book)
            trackDao.insertAll(tracks.map { it.copy(bookId = bookId) })
            bookId
        }

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
