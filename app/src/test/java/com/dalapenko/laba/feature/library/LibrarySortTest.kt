package com.dalapenko.laba.feature.library

import com.dalapenko.laba.core.data.BookWithProgress
import com.dalapenko.laba.testBook
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LibrarySortTest {

    @Test
    fun givenBooksWithDifferentDates_whenSortingByAddedAt_thenBothDirectionsAreSupported() {
        val books = listOf(
            book(id = 1L, title = "Older", addedAt = 100L),
            book(id = 2L, title = "Newer", addedAt = 200L),
        )

        assertEquals(listOf(1L, 2L), ids(sortLibraryBooks(books, LibrarySortOption.ADDED_AT_ASC)))
        assertEquals(listOf(2L, 1L), ids(sortLibraryBooks(books, LibrarySortOption.ADDED_AT_DESC)))
    }

    @Test
    fun givenBooksWithDifferentTitles_whenSortingByTitle_thenBothDirectionsAreSupported() {
        val books = listOf(
            book(id = 1L, title = "Bravo"),
            book(id = 2L, title = "Alpha"),
        )

        assertEquals(listOf(2L, 1L), ids(sortLibraryBooks(books, LibrarySortOption.TITLE_ASC)))
        assertEquals(listOf(1L, 2L), ids(sortLibraryBooks(books, LibrarySortOption.TITLE_DESC)))
    }

    @Test
    fun givenDuplicateSortKeys_whenSorting_thenIdProvidesDeterministicTieBreaker() {
        val books = listOf(
            book(id = 3L, title = "Same", addedAt = 100L),
            book(id = 1L, title = "Same", addedAt = 100L),
            book(id = 2L, title = "Same", addedAt = 100L),
        )

        assertEquals(
            listOf(1L, 2L, 3L),
            ids(sortLibraryBooks(books, LibrarySortOption.ADDED_AT_ASC)),
        )
        assertEquals(
            listOf(3L, 2L, 1L),
            ids(sortLibraryBooks(books, LibrarySortOption.TITLE_DESC)),
        )
    }

    @Test
    fun givenTitlesDifferOnlyByCase_whenSorting_thenComparisonUsesTheRequestedLocale() {
        val books = listOf(
            book(id = 2L, title = "İstanbul"),
            book(id = 1L, title = "istanbul"),
        )

        assertEquals(
            listOf(1L, 2L),
            ids(
                sortLibraryBooks(
                    books,
                    LibrarySortOption.TITLE_ASC,
                    Locale.Builder().setLanguage("tr").build(),
                ),
            ),
        )
    }

    @Test
    fun givenEmptyOrSingleItemInput_whenSorting_thenInputShapeIsPreserved() {
        val empty = emptyList<BookWithProgress>()
        val single = listOf(book(id = 7L, title = "Only"))

        assertEquals(empty, sortLibraryBooks(empty, LibrarySortOption.TITLE_ASC))
        assertEquals(listOf(7L), ids(sortLibraryBooks(single, LibrarySortOption.ADDED_AT_DESC)))
    }

    private fun book(id: Long, title: String, addedAt: Long = id): BookWithProgress =
        BookWithProgress(testBook(id = id, title = title, addedAt = addedAt), null, 0f)

    private fun ids(books: List<BookWithProgress>): List<Long> = books.map { it.book.id }
}
