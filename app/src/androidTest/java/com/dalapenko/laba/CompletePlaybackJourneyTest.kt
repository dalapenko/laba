package com.dalapenko.laba

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.filters.LargeTest
import com.dalapenko.laba.screens.ChapterBottomSheet
import com.dalapenko.laba.screens.LibraryScreen
import com.dalapenko.laba.screens.PlayerScreen
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/**
 * Test Case 1: Complete Audiobook Playback Journey
 *
 * This test validates the entire user flow from library to player, including:
 * - Library book display with 0% progress
 * - Opening book in player (with autoPlay automatically starting playback)
 * - Speed adjustment
 * - Chapter navigation (next/previous buttons and chapter list)
 * - Seek controls (forward/rewind)
 * - Library correctly displaying pre-saved progress (simulates MediaSessionService writes)
 * - Speed restoration from ProgressEntity.playbackSpeed on re-open
 */
@LargeTest
class CompletePlaybackJourneyTest : BaseKaspressoTest() {

    private val book = TestFixtures.threeChapterBook()

    @Before
    fun setupTestData() {
        // Insert test data into the in-memory DB, then launch the activity.
        // Because launchActivity() is called AFTER insertion, Room's Flow emits
        // the book immediately on first collection — no 10-second polling needed.
        runBlocking {
            bookDao.insert(book.bookEntity)
            trackDao.insertAll(book.trackEntities)
        }
        launchActivity()
    }

    @Test
    fun completePlaybackJourney() = run {
        val bookId = book.bookEntity.id

        step("1. Verify book is displayed in library with 0% progress") {
            // Short wait for the initial Compose recomposition after Room Flow emits
            composeTestRule.waitUntil(timeoutMillis = COMPOSE_RECOMPOSITION_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithTag("book_card_$bookId", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }

            withScreen<LibraryScreen> {
                flakySafely { bookCard(bookId).assertIsDisplayed() }
                bookTitle(bookId).assertTextContains(book.bookEntity.title)
                bookProgressText(bookId).assertTextContains("0", substring = true)
            }
        }

        step("2. Open book in player (autoPlay starts playback)") {
            withScreen<LibraryScreen> {
                bookCard(bookId).performClick()
            }

            // Wait for the slideInVertically + fadeIn navigation animation to complete
            // and for PlayerViewModel.loadBook() to finish (isInitializing = false).
            // The app automatically starts playback (autoPlay=true by default).
            withScreen<PlayerScreen> {
                flakySafely { playerBookTitle.assertIsDisplayed() }
                playerBookTitle.assertTextContains(book.bookEntity.title)
            }
            
            // Verify playback has started automatically
            check(playbackController.playerState.value.isPlaying) {
                "Expected playback to start automatically (autoPlay=true), but isPlaying=false"
            }
        }

        step("3. Adjust playback speed to 1.5×") {
            withScreen<PlayerScreen> {
                flakySafely { speedSlider.assertIsDisplayed() }
                speedSlider.performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
                speedText.assertTextContains("1.5", substring = true)
            }
        }

        step("4. Navigate to next chapter") {
            val trackIndexBefore = playbackController.playerState.value.currentMediaItemIndex
            
            withScreen<PlayerScreen> {
                flakySafely { nextChapterButton.assertIsDisplayed() }
                nextChapterButton.performClick()
            }
            
            // Verify we moved to the next track
            val trackIndexAfter = playbackController.playerState.value.currentMediaItemIndex
            check(trackIndexAfter == trackIndexBefore + 1) {
                "Expected track index to increase from $trackIndexBefore to ${trackIndexBefore + 1}, " +
                "but got $trackIndexAfter"
            }
        }

        step("5. Navigate to previous chapter") {
            val trackIndexBefore = playbackController.playerState.value.currentMediaItemIndex
            
            withScreen<PlayerScreen> {
                flakySafely { previousChapterButton.assertIsDisplayed() }
                previousChapterButton.performClick()
            }
            
            // Verify we moved to the previous track
            val trackIndexAfter = playbackController.playerState.value.currentMediaItemIndex
            check(trackIndexAfter == trackIndexBefore - 1) {
                "Expected track index to decrease from $trackIndexBefore to ${trackIndexBefore - 1}, " +
                "but got $trackIndexAfter"
            }
        }

        step("6. Open chapter list and jump to chapter 3") {
            withScreen<PlayerScreen> {
                chaptersButton.performClick()
            }

            withScreen<ChapterBottomSheet> {
                flakySafely { chapterItem(2).assertIsDisplayed() }
                chapterItem(2).performClick()
            }
            
            // Verify we jumped to chapter 3 (index 2)
            val trackIndexAfter = playbackController.playerState.value.currentMediaItemIndex
            check(trackIndexAfter == 2) {
                "Expected to jump to chapter 3 (index 2), but got index $trackIndexAfter"
            }
        }

        step("7. Test seek controls (forward/rewind)") {
            val positionBefore = playbackController.playerState.value.currentPositionMs
            
            withScreen<PlayerScreen> {
                flakySafely { forwardButton.assertIsDisplayed() }
                forwardButton.performClick()
            }
            
            // Verify position increased (forward seek is typically +10s = 10,000ms)
            val positionAfterForward = playbackController.playerState.value.currentPositionMs
            check(positionAfterForward > positionBefore) {
                "Expected position to increase after forward seek, " +
                "but was $positionBefore before and $positionAfterForward after"
            }

            withScreen<PlayerScreen> {
                flakySafely { rewindButton.assertIsDisplayed() }
                rewindButton.performClick()
            }
            
            // Verify position decreased (rewind should bring us back or close to original position)
            val positionAfterRewind = playbackController.playerState.value.currentPositionMs
            check(positionAfterRewind < positionAfterForward) {
                "Expected position to decrease after rewind, " +
                "but was $positionAfterForward before and $positionAfterRewind after"
            }
        }

        step("8. Simulate progress save, return to library, verify progress is displayed") {
            // First, navigate back to library
            withScreen<PlayerScreen> {
                backButton.performClick()
            }

            // Stop playback to clear currentBookId. This ensures LibraryViewModel
            // will read progress from DB instead of live player state.
            // In production, this simulates the user closing the app or stopping playback.
            playbackController.stop()

            // Write the progress to DB to simulate MediaSessionService periodic saves.
            // chapter 1 done (180 000ms) + 60 000ms into chapter 2 over 620 000ms total
            // = 240 000 / 620 000 ≈ 38%
            insertProgress(
                bookId = bookId,
                lastTrackId = book.trackId(1),  // Chapter 2
                positionMs = 60_000L,
                completedMs = 180_000L,
                speed = 1.5f
            )

            // Wait for Room's Flow to re-emit with the updated ProgressEntity
            composeTestRule.waitForIdle()

            // Verify the library displays the DB progress (38%)
            withScreen<LibraryScreen> {
                flakySafely { bookCard(bookId).assertIsDisplayed() }
                bookProgressText(bookId).assertTextContains("38", substring = true)
            }
        }

        step("9. Re-open book and verify playback speed 1.5× is restored from database") {
            // PlaybackPreparer.setupPlayback() reads ProgressEntity.playbackSpeed from DB
            // and calls setInitialState(speed = 1.5f) on the controller.
            withScreen<LibraryScreen> {
                bookCard(bookId).performClick()
            }

            withScreen<PlayerScreen> {
                flakySafely { playerBookTitle.assertIsDisplayed() }
                speedText.assertTextContains("1.5", substring = true)
            }
        }
    }
}
