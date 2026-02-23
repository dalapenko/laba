package com.dalapenko.laba.screens

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import io.github.kakaocup.compose.node.element.ComposeScreen
import io.github.kakaocup.compose.node.element.KNode

/**
 * Page Object for the Chapter Bottom Sheet.
 * 
 * Provides access to all UI elements on the chapter selection bottom sheet.
 */
class ChapterBottomSheet(semanticsProvider: SemanticsNodeInteractionsProvider) :
    ComposeScreen<ChapterBottomSheet>(
        semanticsProvider = semanticsProvider,
        viewBuilderAction = { hasTestTag("chapter_bottom_sheet") }
    ) {

    val chaptersTitle: KNode = child {
        hasTestTag("chapters_title")
    }

    val chaptersList: KNode = child {
        hasTestTag("chapters_list")
    }

    /**
     * Returns a chapter item by index (0-based).
     */
    fun chapterItem(index: Int): KNode = child {
        hasTestTag("chapter_item_$index")
    }
}
