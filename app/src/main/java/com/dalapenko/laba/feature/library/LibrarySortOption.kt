package com.dalapenko.laba.feature.library

import com.dalapenko.laba.core.data.BookWithProgress
import java.text.Collator
import java.util.Locale

enum class LibrarySortOption {
    ADDED_AT_ASC,
    ADDED_AT_DESC,
    TITLE_ASC,
    TITLE_DESC,
}

fun sortLibraryBooks(
    books: List<BookWithProgress>,
    option: LibrarySortOption,
    locale: Locale = Locale.getDefault(),
): List<BookWithProgress> {
    val addedAtComparator = compareBy<BookWithProgress> { it.book.addedAt }
        .thenBy { it.book.id }
    val titleComparator = titleComparator(locale)

    return when (option) {
        LibrarySortOption.ADDED_AT_ASC -> books.sortedWith(addedAtComparator)
        LibrarySortOption.ADDED_AT_DESC -> books.sortedWith(addedAtComparator.reversed())
        LibrarySortOption.TITLE_ASC -> books.sortedWith(titleComparator)
        LibrarySortOption.TITLE_DESC -> books.sortedWith(titleComparator.reversed())
    }
}

private fun titleComparator(locale: Locale): Comparator<BookWithProgress> {
    val collator = Collator.getInstance(locale).apply {
        strength = Collator.PRIMARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }
    return Comparator { left, right ->
        collator.compare(left.book.title, right.book.title).takeIf { it != 0 }
            ?: left.book.id.compareTo(right.book.id)
    }
}
