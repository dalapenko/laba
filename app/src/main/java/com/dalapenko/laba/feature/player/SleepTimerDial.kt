package com.dalapenko.laba.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val DIAL_DIAMETER = 220.dp
private val LABEL_AREA = 32.dp
private val TOTAL_DIAMETER = DIAL_DIAMETER + LABEL_AREA * 2
private val RING_STROKE_WIDTH = 6.dp
private val RING_SPACING = 22.dp
private val THUMB_RADIUS = 9.dp

/** Round drag-to-set dial for the sleep timer: 0..180 minutes across up to 3 concentric 60-minute laps. */
@Composable
fun SleepTimerDial(
    minutes: Int,
    onMinutesChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentMinutes by rememberUpdatedState(minutes)
    val onMinutesChangedState by rememberUpdatedState(onMinutesChanged)

    var dragStartAngle by remember { mutableFloatStateOf(0f) }
    var accumulatedAngle by remember { mutableFloatStateOf(0f) }
    var valueAtDragStart by remember { mutableIntStateOf(minutes) }

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val activeColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .size(TOTAL_DIAMETER)
            .testTag("sleep_timer_dial"),
        contentAlignment = Alignment.Center,
    ) {
        SleepTimerDialLabels(radius = DIAL_DIAMETER / 2 + LABEL_AREA / 2)

        Canvas(
            modifier = Modifier
                .size(DIAL_DIAMETER)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val center = Offset(size.width / 2f, size.height / 2f)
                            dragStartAngle = touchAngleDegrees(offset.x - center.x, offset.y - center.y)
                            accumulatedAngle = 0f
                            valueAtDragStart = currentMinutes
                        },
                        onDrag = { change, _ ->
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val currentAngle = touchAngleDegrees(
                                change.position.x - center.x,
                                change.position.y - center.y,
                            )
                            accumulatedAngle += angleDelta(dragStartAngle, currentAngle)
                            dragStartAngle = currentAngle
                            val newValue = (valueAtDragStart + accumulatedAngleToMinutesDelta(accumulatedAngle))
                                .coerceIn(0, SLEEP_TIMER_MAX_MINUTES)
                            onMinutesChangedState(newValue)
                        },
                    )
                },
        ) {
            drawSleepTimerDial(minutes = currentMinutes, trackColor = trackColor, activeColor = activeColor)
        }

        Text(
            text = String.format(Locale.ENGLISH, "%d:00", minutes),
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.testTag("sleep_timer_dial_value"),
        )
    }
}

private fun DrawScope.drawSleepTimerDial(minutes: Int, trackColor: Color, activeColor: Color) {
    val position = minutesToDialPosition(minutes)
    val ringCount = ringCountForMinutes(minutes)
    val maxRadius = min(size.width, size.height) / 2f
    val spacingPx = RING_SPACING.toPx()
    val stroke = Stroke(width = RING_STROKE_WIDTH.toPx(), cap = StrokeCap.Round)
    val center = Offset(size.width / 2f, size.height / 2f)

    fun radiusForLap(lap: Int) = maxRadius - (MAX_LAP_INDEX - lap) * spacingPx

    for (lap in 0 until ringCount) {
        drawCircle(color = trackColor, radius = radiusForLap(lap), center = center, style = stroke)
    }

    if (minutes <= 0) return

    for (lap in 0..position.lapIndex) {
        val radius = radiusForLap(lap)
        val sweep = if (lap < position.lapIndex) FULL_CIRCLE_DEGREES else position.angleDegrees
        drawArc(
            color = activeColor,
            startAngle = ARC_START_ANGLE,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = center - Offset(radius, radius),
            size = Size(radius * 2f, radius * 2f),
            style = stroke,
        )
    }

    val thumbRadius = radiusForLap(position.lapIndex)
    val thumbAngleRad = Math.toRadians((position.angleDegrees + ARC_START_ANGLE).toDouble())
    val thumbCenter = center + Offset(
        (thumbRadius * cos(thumbAngleRad)).toFloat(),
        (thumbRadius * sin(thumbAngleRad)).toFloat(),
    )
    drawCircle(color = activeColor, radius = THUMB_RADIUS.toPx(), center = thumbCenter)
}

@Composable
private fun SleepTimerDialLabels(radius: Dp, modifier: Modifier = Modifier) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier = modifier.size(TOTAL_DIAMETER)) {
        for (i in 0 until LABELS_PER_LAP) {
            val minuteLabel = (i * LABEL_STEP_MINUTES) % MINUTES_PER_LAP
            val angleRad = Math.toRadians((i * (FULL_CIRCLE_DEGREES / LABELS_PER_LAP) + ARC_START_ANGLE).toDouble())
            val dx = (radius.value * cos(angleRad)).toFloat().dp
            val dy = (radius.value * sin(angleRad)).toFloat().dp
            Text(
                text = minuteLabel.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = dx, y = dy),
            )
        }
    }
}

private const val LABEL_STEP_MINUTES = 5
private const val LABELS_PER_LAP = MINUTES_PER_LAP / LABEL_STEP_MINUTES
private const val MAX_LAP_INDEX = 2 // SLEEP_TIMER_MAX_MINUTES / MINUTES_PER_LAP - 1
private const val ARC_START_ANGLE = -90f
private const val FULL_CIRCLE_DEGREES = 360f
