package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.PureWhite
import com.example.ui.theme.RamsRed
import com.example.ui.theme.SolidBlack

/**
 * Dieter Rams inspired Header Toggle & Momentary Peek/Pause Button.
 * A light gray square with an interactive circular indicator inside:
 *
 * - Monitoring audio in (default ON, not recording): Slowly pulsating Solid Black circle.
 * - Monitoring audio in OFF (not recording): Slowly pulsating Pure White circle.
 * - Recording ON: Slowly pulsating #E60000 Rams Red circle.
 *
 * Gestures:
 * - Tap (<350ms): Toggles audio in listening on/off, updating VU meters and waveform.
 * - Long press / Hold (>=350ms):
 *   - Circle stops pulsing (becomes solid visible without blinking).
 *   - If monitoring was OFF: activates momentary peek (turns on VU meters & waveform live). Circle is solid Black.
 *   - If monitoring was ON (not recording): activates momentary mute (pauses audio in & VU meters). Circle is solid White.
 *   - If recording was ON: pauses recording and freezes waveform. Circle is solid #E60000 Red.
 *   - Releasing restores original active state and resumes ease-in-out pulsation.
 */
@Composable
fun RecodaHeaderButton(
    isMonitoring: Boolean,
    isRecording: Boolean,
    isHeld: Boolean,
    onButtonDown: () -> Unit,
    onButtonUp: (durationMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    squareSize: Dp = 24.dp,
    circleSize: Dp = 10.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recoda_header_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    // Local hold tracking to immediately reflect pointer state
    var localHolding by remember { mutableStateOf(false) }
    val effectiveHeld = isHeld || localHolding

    // Compute circle color according to user specification:
    // When held: solid without pulse
    // - Recording -> Red
    // - Monitoring ON -> White (momentary mute)
    // - Monitoring OFF -> Black (momentary peek)
    // When not held: pulsating
    // - Recording -> Red
    // - Monitoring ON -> Black
    // - Monitoring OFF -> White
    val baseColor = when {
        isRecording -> RamsRed
        effectiveHeld -> if (isMonitoring) PureWhite else SolidBlack
        isMonitoring -> SolidBlack
        else -> PureWhite
    }

    val displayAlpha = if (effectiveHeld) 1.0f else pulseAlpha
    val displayScale = if (effectiveHeld) 1.0f else pulseScale

    // Minimum 48dp touch target container
    Box(
        modifier = modifier
            .size(48.dp)
            .testTag("recoda_header_button")
            .pointerInput(isMonitoring, isRecording) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val startTime = System.currentTimeMillis()
                    var isReleased = false
                    var holdTriggered = false

                    while (!isReleased) {
                        val event = awaitPointerEvent()
                        val elapsed = System.currentTimeMillis() - startTime

                        if (!holdTriggered && elapsed >= 300L) {
                            holdTriggered = true
                            localHolding = true
                            onButtonDown()
                        }

                        val upOrCancel = event.changes.all { !it.pressed }
                        if (upOrCancel) {
                            isReleased = true
                            event.changes.forEach { it.consume() }
                        }
                    }

                    val totalDuration = System.currentTimeMillis() - startTime
                    localHolding = false
                    onButtonUp(totalDuration)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Light gray square with border (Dieter Rams sharp rectangular housing)
        Box(
            modifier = Modifier
                .size(squareSize)
                .background(Color(0xFFDCDCDA), RectangleShape)
                .border(1.dp, Color(0xFFBCBCB8), RectangleShape),
            contentAlignment = Alignment.Center
        ) {
            // Interactive circle with ease-in-out pulsation or steady solid state
            Box(
                modifier = Modifier
                    .size(circleSize)
                    .scale(displayScale)
                    .clip(CircleShape)
                    .background(baseColor.copy(alpha = displayAlpha))
            )
        }
    }
}
