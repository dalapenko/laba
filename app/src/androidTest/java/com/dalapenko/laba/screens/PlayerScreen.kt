package com.dalapenko.laba.screens

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import io.github.kakaocup.compose.node.element.ComposeScreen
import io.github.kakaocup.compose.node.element.KNode

/**
 * Page Object for the Player Screen.
 * 
 * Provides access to all UI elements on the player screen using the Page Object pattern.
 */
class PlayerScreen(semanticsProvider: SemanticsNodeInteractionsProvider) :
    ComposeScreen<PlayerScreen>(
        semanticsProvider = semanticsProvider,
        viewBuilderAction = { hasTestTag("player_screen") }
    ) {

    val backButton: KNode = child {
        hasTestTag("back_button")
    }

    val playerBookTitle: KNode = child {
        hasTestTag("player_book_title")
    }

    val chaptersButton: KNode = child {
        hasTestTag("chapters_button")
    }

    val playPauseButton: KNode = child {
        hasTestTag("play_pause_button")
    }

    val rewindButton: KNode = child {
        hasTestTag("rewind_button")
    }

    val forwardButton: KNode = child {
        hasTestTag("forward_button")
    }

    val previousChapterButton: KNode = child {
        hasTestTag("previous_chapter_button")
    }

    val nextChapterButton: KNode = child {
        hasTestTag("next_chapter_button")
    }

    val positionSlider: KNode = child {
        hasTestTag("position_slider")
    }

    val speedControl: KNode = child {
        hasTestTag("speed_control")
    }

    val speedText: KNode = child {
        hasTestTag("speed_text")
    }

    val speedSlider: KNode = child {
        hasTestTag("speed_slider")
    }
}
