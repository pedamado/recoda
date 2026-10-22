package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Minimalist Dieter Rams & Teenage Engineering inspired favicon / status indicator.
 * A light gray square with a crisp white circular ball inside that blinks slowly when recording.
 */
@Composable
fun RecodaFavicon(
    isRecording: Boolean,
    modifier: Modifier = Modifier,
    squareSize: Dp = 18.dp,
    circleSize: Dp = 8.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rec_favicon_blink")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.20f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ball_pulse_alpha"
    )

    Box(
        modifier = modifier
            .size(squareSize)
            .background(Color(0xFFE2E2DF), RectangleShape)
            .border(1.dp, Color(0xFFC4C4C0), RectangleShape),
        contentAlignment = Alignment.Center
    ) {
        val circleColor = if (isRecording) {
            Color.White.copy(alpha = alpha)
        } else {
            Color(0xFF9E9E9B)
        }

        Box(
            modifier = Modifier
                .size(circleSize)
                .clip(CircleShape)
                .background(circleColor)
        )
    }
}
