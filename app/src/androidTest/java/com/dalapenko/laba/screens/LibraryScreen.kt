package com.dalapenko.laba.screens

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import com.dalapenko.laba.feature.library.LibrarySortOption
import io.github.kakaocup.compose.node.element.ComposeScreen
import io.github.kakaocup.compose.node.element.KNode

/**
 * Page Object for the Library Screen.
 *
 * Provides access to all UI elements on the library screen using the Page Object pattern.
 */
class LibraryScreen(semanticsProvider: SemanticsNodeInteractionsProvider) :
    ComposeScreen<LibraryScreen>(
        semanticsProvider = semanticsProvider,
        viewBuilderAction = { hasTestTag("library_screen") }
    ) {

    val continueSection: KNode = child {
        hasTestTag("continue_section")
    }

    val continueHeader: KNode = child {
        hasTestTag("continue_header")
    }

    val sortButton: KNode = child {
        hasTestTag("sort_button")
    }

    val sortMenu: KNode = child {
        hasTestTag("sort_menu")
    }

    fun sortOption(option: LibrarySortOption): KNode = child {
        hasTestTag("sort_option_${option.name}")
    }

    /**
     * Returns a book card by book ID.
     */
    fun bookCard(bookId: Long): KNode = child {
        hasTestTag("book_card_$bookId")
    }

    fun continueBookCard(bookId: Long): KNode = child {
        hasTestTag("continue_book_card_$bookId")
    }

    /**
     * Returns the title text of a book by book ID.
     * Uses useUnmergedTree because the Text is inside a combinedClickable ElevatedCard
     * which merges all descendant semantics into itself.
     */
    fun bookTitle(bookId: Long): KNode = child {
        useUnmergedTree = true
        hasTestTag("book_title_$bookId")
    }

    /**
     * Returns the progress text of a book by book ID.
     */
    fun bookProgressText(bookId: Long): KNode = child {
        useUnmergedTree = true
        hasTestTag("book_progress_text_$bookId")
    }
}
