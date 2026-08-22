package com.dalapenko.laba

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.LargeTest
import com.dalapenko.laba.core.database.entity.ProgressEntity
import com.dalapenko.laba.feature.library.LibrarySortOption
import com.dalapenko.laba.screens.LibraryScreen
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

@LargeTest
class LibrarySortingTest : BaseKaspressoTest() {

    private val continueBook = TestFixtures.testBook(
        id = 1L,
        title = "Zulu",
        addedAt = 100L,
    )
    private val ordinaryBook = TestFixtures.testBook(
        id = 2L,
        title = "Alpha",
        addedAt = 200L,
    )
    private val continueTrack = TestFixtures.testTrack(
        id = 1L,
        bookId = continueBook.id,
        fileName = "Chapter 1.mp3",
        sequenceOrder = 0,
        durationMs = 60_000L,
    )

    @Before
    fun setupTestData() {
        runBlocking {
            settingsRepository.setLibrarySortOption(LibrarySortOption.ADDED_AT_DESC)
            bookDao.insert(continueBook)
            bookDao.insert(ordinaryBook)
            trackDao.insertAll(listOf(continueTrack))
            progressDao.upsert(
                ProgressEntity(
                    bookId = continueBook.id,
                    lastTrackId = continueTrack.id,
                    lastPositionMs = 1_000L,
                    completedTracksMs = 0L,
                    lastUpdated = 2_000L,
                ),
            )
        }
        launchActivity()
    }

    @Test
    fun selectingSortOption_persistsAcrossScreenRecreation_andKeepsContinueSeparate() = run {
        waitForLibrary()

        withScreen<LibraryScreen> {
            sortButton.performClick()
            sortMenu.assertIsDisplayed()
            sortOption(LibrarySortOption.TITLE_ASC).performClick()
        }
        composeTestRule.waitForIdle()

        withScreen<LibraryScreen> {
            continueBookCard(continueBook.id).assertIsDisplayed()
            bookCard(ordinaryBook.id).assertIsDisplayed()
        }

        recreateActivity()
        waitForLibrary()

        withScreen<LibraryScreen> {
            sortButton.performClick()
            val selectedOption = sortOption(LibrarySortOption.TITLE_ASC)
            selectedOption.assertIsDisplayed()
            selectedOption.assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    InstrumentationRegistry.getInstrumentation().targetContext
                        .getString(R.string.sort_selected),
                )
            )
        }

        restartActivity()
        waitForLibrary()

        withScreen<LibraryScreen> {
            sortButton.performClick()
            sortOption(LibrarySortOption.TITLE_ASC).assertIsDisplayed()
        }
    }

    private fun waitForLibrary() {
        composeTestRule.waitUntil(timeoutMillis = COMPOSE_RECOMPOSITION_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag(
                "book_card_${ordinaryBook.id}",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
