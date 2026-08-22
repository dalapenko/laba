package com.dalapenko.laba

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dalapenko.laba.core.database.AppDatabase
import com.dalapenko.laba.core.database.dao.BookDao
import com.dalapenko.laba.core.database.dao.ProgressDao
import com.dalapenko.laba.core.database.dao.TrackDao
import com.dalapenko.laba.core.database.entity.ProgressEntity
import com.dalapenko.laba.core.media.PlaybackController
import com.dalapenko.laba.feature.settings.SettingsRepository
import com.kaspersky.components.composesupport.config.withComposeSupport
import com.kaspersky.kaspresso.kaspresso.Kaspresso
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import io.github.kakaocup.compose.node.element.ComposeScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.koin.test.KoinTest
import org.koin.test.inject

/**
 * Base class for all Kaspresso UI tests.
 *
 * Provides:
 * - Compose test rule for UI testing (activity NOT auto-launched)
 * - In-memory Room database via Koin test modules (loaded by LabaTestRunner)
 * - Mocked PlaybackController
 * - DAO access for test data setup
 * - Helper extensions for cleaner test code
 * - Automatic database cleanup and activity teardown after tests
 *
 * IMPORTANT — correct lifecycle order:
 *  1. @get:Rule composeTestRule is registered but does NOT launch the activity.
 *  2. Subclass @Before: insert test data into the database.
 *  3. Subclass @Before: call launchActivity() — activity starts with data already in DB,
 *     so Room's Flow emits immediately and no waitUntil polling is needed for initial load.
 *  4. Test body runs.
 *  5. @After tearDown(): activity is closed, then the database is cleared.
 *
 * Using createEmptyComposeRule() instead of createAndroidComposeRule<Activity>() is the
 * key to this ordering. createAndroidComposeRule launches the activity inside the Rule's
 * starting() callback, which executes before any @Before methods.
 */
@RunWith(AndroidJUnit4::class)
abstract class BaseKaspressoTest : TestCase(
    // Kaspresso with Compose support and explicit timeouts for transparency
    // Default timeout for Kaspresso operations: 10 seconds
    kaspressoBuilder = Kaspresso.Builder.withComposeSupport()
), KoinTest {

    companion object {
        /**
         * Timeout for waiting on Compose recomposition after Room Flow emissions.
         * Used when waiting for database changes to propagate to UI.
         */
        const val COMPOSE_RECOMPOSITION_TIMEOUT_MS = 5_000L
    }

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    // Inject DAOs for test data setup
    protected val bookDao: BookDao by inject()
    protected val trackDao: TrackDao by inject()
    protected val progressDao: ProgressDao by inject()
    protected val database: AppDatabase by inject()

    // Inject PlaybackController for verification
    protected val playbackController: PlaybackController by inject()
    protected val settingsRepository: SettingsRepository by inject()

    private var activityScenario: ActivityScenario<MainActivity>? = null

    /**
     * Verifies that the mocked PlaybackController is properly loaded.
     * This safety check ensures no real Media3 components are created in tests.
     *
     * Called automatically in @Before to fail fast if mock setup is broken.
     */
    @Before
    fun verifyMockPlaybackController() {
        val controllerToString = playbackController.toString()

        android.util.Log.d("TEST_MOCK_VERIFY", "PlaybackController instance: $controllerToString")

        // MockK in Android instrumentation tests doesn't create proxy classes with different names.
        // Instead, it creates instances with the same class name but adds a (#N) suffix to toString().
        // Example: "PlaybackController(#1)" for mocks vs "PlaybackController@12abc" for real instances.
        val isMocked = controllerToString.matches(Regex(".*\\(#\\d+\\)"))

        if (!isMocked) {
            throw AssertionError(
                "PlaybackController is NOT mocked! " +
                "Real controller detected: $controllerToString. " +
                "Expected MockK instance with (#N) suffix. " +
                "This will cause 'File not found' errors when using fake test URIs. " +
                "Check that testPlaybackModule is loaded after mediaModule in TestLabaApp."
            )
        }

        android.util.Log.d("TEST_MOCK_VERIFY", "✓ MockK PlaybackController verified: $controllerToString")
    }

    /**
     * Launches MainActivity. Must be called at the END of the subclass @Before method,
     * after all test data has been inserted into the database.
     */
    protected fun launchActivity() {
        activityScenario = ActivityScenario.launch(MainActivity::class.java)
    }

    protected fun recreateActivity() {
        activityScenario?.recreate()
    }

    protected fun restartActivity() {
        activityScenario?.close()
        activityScenario = ActivityScenario.launch(MainActivity::class.java)
    }

    // ========== Helper Extensions ==========

    /**
     * Helper to interact with a Compose screen with automatic waitForIdle.
     * Reduces boilerplate and ensures Compose is settled before interactions.
     *
     * Usage:
     * ```
     * withScreen<PlayerScreen> {
     *     playButton.performClick()
     * }
     * ```
     */
    protected inline fun <reified T : ComposeScreen<T>> withScreen(
        noinline block: T.() -> Unit
    ) {
        composeTestRule.waitForIdle()
        ComposeScreen.onComposeScreen<T>(
            composeTestRule,
            block
        )
    }

    /**
     * Helper to insert progress data into the database.
     * Simplifies test data setup and makes progress creation more readable.
     *
     * Usage:
     * ```
     * insertProgress(
     *     bookId = 1L,
     *     lastTrackId = 2L,
     *     positionMs = 60_000L,
     *     completedMs = 180_000L,
     *     speed = 1.5f
     * )
     * ```
     */
    protected fun insertProgress(
        bookId: Long,
        lastTrackId: Long,
        positionMs: Long,
        completedMs: Long,
        speed: Float = 1.0f
    ) = runBlocking {
        progressDao.upsert(
            ProgressEntity(
                bookId = bookId,
                lastTrackId = lastTrackId,
                lastPositionMs = positionMs,
                completedTracksMs = completedMs,
                lastUpdated = System.currentTimeMillis(),
                playbackSpeed = speed,
            )
        )
    }

    @After
    fun tearDown() {
        // Close the activity before clearing tables to avoid in-flight DB operations
        activityScenario?.close()
        activityScenario = null
        database.clearAllTables()
    }
}
