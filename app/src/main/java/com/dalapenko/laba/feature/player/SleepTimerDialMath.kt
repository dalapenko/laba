package com.dalapenko.laba.feature.player

import kotlin.math.atan2
import kotlin.math.roundToInt

internal const val SLEEP_TIMER_MAX_MINUTES = 180
internal const val MINUTES_PER_LAP = 60

internal data class DialPosition(val lapIndex: Int, val angleDegrees: Float)

/** Number of concentric rings to draw for a given total-minutes value (1..3). */
internal fun ringCountForMinutes(totalMinutes: Int): Int =
    ((totalMinutes.coerceIn(0, SLEEP_TIMER_MAX_MINUTES) + MINUTES_PER_LAP - 1) / MINUTES_PER_LAP).coerceAtLeast(1)

/**
 * Which lap (0..2) the thumb sits on and its sweep angle within that lap (0 exclusive..360
 * inclusive, clockwise from the top). Exact multiples of 60 land on the *end* (360 deg, full
 * ring) of the completed lap rather than the start of the next one.
 */
internal fun minutesToDialPosition(totalMinutes: Int): DialPosition {
    val clamped = totalMinutes.coerceIn(0, SLEEP_TIMER_MAX_MINUTES)
    if (clamped == 0) return DialPosition(lapIndex = 0, angleDegrees = 0f)
    val lapIndex = (clamped - 1) / MINUTES_PER_LAP
    val minuteWithinLap = clamped - lapIndex * MINUTES_PER_LAP
    return DialPosition(lapIndex = lapIndex, angleDegrees = minuteWithinLap * DEGREES_PER_MINUTE)
}

/** Angle of a touch point relative to the dial center, in degrees, 0 = top, clockwise-positive. */
internal fun touchAngleDegrees(dx: Float, dy: Float): Float {
    val degrees = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
    return if (degrees < 0f) degrees + DEGREES_PER_CIRCLE else degrees
}

/** Shortest signed angular delta from [previousDegrees] to [currentDegrees], handling 0/360 wraparound. */
internal fun angleDelta(previousDegrees: Float, currentDegrees: Float): Float {
    var delta = currentDegrees - previousDegrees
    if (delta > HALF_CIRCLE_DEGREES) delta -= DEGREES_PER_CIRCLE
    if (delta < -HALF_CIRCLE_DEGREES) delta += DEGREES_PER_CIRCLE
    return delta
}

/** Converts a cumulative (possibly multi-revolution) signed drag angle into a signed minute delta. */
internal fun accumulatedAngleToMinutesDelta(accumulatedDegrees: Float): Int =
    (accumulatedDegrees / DEGREES_PER_MINUTE).roundToInt()

private const val DEGREES_PER_MINUTE = 6f
private const val DEGREES_PER_CIRCLE = 360f
private const val HALF_CIRCLE_DEGREES = 180f
