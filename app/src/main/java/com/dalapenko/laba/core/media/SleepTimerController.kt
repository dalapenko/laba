package com.dalapenko.laba.core.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

data class SleepTimerState(
    val isActive: Boolean = false,
    val remainingMs: Long = 0L,
    val isEndOfChapterMode: Boolean = false,
)

/**
 * Singleton controller for the Player screen's sleep timer.
 *
 * Lifecycle: registered alongside [PlaybackController] in the app-scoped Koin graph, so the
 * countdown keeps running - and can still pause playback - after the user navigates away from
 * the Player screen back to the Library. Must not be owned by a ViewModel, whose scope would be
 * cancelled on that navigation.
 */
class SleepTimerController(
    private val playbackController: PlaybackController,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {

    private var timerJob: Job? = null

    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    /** Wall-clock countdown, independent of play/pause state - pauses playback when it elapses. */
    fun startFixedDuration(durationMs: Long) {
        timerJob?.cancel()
        val clamped = durationMs.coerceAtLeast(0L)
        _state.value = SleepTimerState(isActive = true, remainingMs = clamped, isEndOfChapterMode = false)
        timerJob = scope.launch {
            var remaining = clamped
            while (remaining > 0) {
                delay(TICK_INTERVAL_MS.milliseconds)
                remaining = (remaining - TICK_INTERVAL_MS).coerceAtLeast(0L)
                _state.value = _state.value.copy(remainingMs = remaining)
            }
            playbackController.pause()
            _state.value = SleepTimerState()
        }
    }

    /** Pauses playback as soon as the current track/chapter ends. Remaining time self-corrects on seeks. */
    fun startEndOfChapter() {
        timerJob?.cancel()
        val initial = playbackController.playerState.value
        val startBookId = playbackController.currentBookId.value
        val startIndex = initial.currentMediaItemIndex
        _state.value = SleepTimerState(
            isActive = true,
            remainingMs = (initial.durationMs - initial.currentPositionMs).coerceAtLeast(0L),
            isEndOfChapterMode = true,
        )
        timerJob = scope.launch {
            while (true) {
                delay(TICK_INTERVAL_MS.milliseconds)

                // The active book changing under us (user closed this book and opened another)
                // means this timer no longer applies - don't pause the new playback session,
                // just quietly drop the stale timer.
                val bookChanged = playbackController.currentBookId.value != startBookId
                val current = playbackController.playerState.value
                val chapterChanged = current.currentMediaItemIndex != startIndex
                val chapterNaturallyEnded = !chapterChanged &&
                    current.durationMs > 0 &&
                    current.currentPositionMs >= current.durationMs - COMPLETION_THRESHOLD_MS &&
                    !current.isPlaying

                if (bookChanged || chapterChanged || chapterNaturallyEnded) {
                    if (!bookChanged) playbackController.pause()
                    break
                }

                _state.value = _state.value.copy(
                    remainingMs = (current.durationMs - current.currentPositionMs).coerceAtLeast(0L),
                )
            }
            _state.value = SleepTimerState()
        }
    }

    fun cancel() {
        timerJob?.cancel()
        timerJob = null
        _state.value = SleepTimerState()
    }
}

private const val TICK_INTERVAL_MS = 1_000L
private const val COMPLETION_THRESHOLD_MS = 1_000L
