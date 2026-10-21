package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.VuMeterChannelData
import com.example.audio.VuMeterState
import com.example.ui.theme.Charcoal
import com.example.ui.theme.DarkGray
import com.example.ui.theme.GrayBorder
import com.example.ui.theme.LightGray
import com.example.ui.theme.MartianMonoFontFamily
import com.example.ui.theme.MidGray
import com.example.ui.theme.OffWhite
import com.example.ui.theme.PureWhite
import com.example.ui.theme.RamsRed
import com.example.ui.theme.SolidBlack

/**
 * Flat, minimalist rectangular horizontal slider in Dieter Rams style.
 * Double-tapping resets the slider value to default.
 */
@Composable
fun RamsFlatSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onDoubleTap: () -> Unit = {},
    testTag: String = "flat_slider"
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .testTag(testTag)
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onDoubleTap = { onDoubleTap() },
                    onTap = { offset ->
                        val norm = (offset.x / size.width).coerceIn(0f, 1f)
                        val newVal = valueRange.start + norm * (valueRange.endInclusive - valueRange.start)
                        onValueChange(newVal)
                    }
                )
            }
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                detectDragGestures { change, _ ->
                    change.consume()
                    val norm = (change.position.x / size.width).coerceIn(0f, 1f)
                    val newVal = valueRange.start + norm * (valueRange.endInclusive - valueRange.start)
                    onValueChange(newVal)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val rangeSpan = valueRange.endInclusive - valueRange.start
            val fraction = if (rangeSpan > 0f) ((value - valueRange.start) / rangeSpan).coerceIn(0f, 1f) else 0f

            val trackHeight = 10.dp.toPx()
            val trackY = (size.height - trackHeight) / 2f

            // Flat track background (light gray)
            drawRect(
                color = if (enabled) LightGray else OffWhite,
                topLeft = Offset(0f, trackY),
                size = Size(size.width, trackHeight)
            )

            // Flat active fill without lines at the ends
            val fillWidth = fraction * size.width
            if (fillWidth > 0f) {
                drawRect(
                    color = if (enabled) Charcoal else DarkGray,
                    topLeft = Offset(0f, trackY),
                    size = Size(fillWidth, trackHeight)
                )
            }
        }
    }
}

/**
 * Flat rectangular toggle in Dieter Rams style.
 */
@Composable
fun RamsFlatToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "flat_toggle"
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .width(52.dp)
            .height(28.dp)
            .background(if (checked) SolidBlack else LightGray, RectangleShape)
            .border(1.dp, GrayBorder, RectangleShape)
            .clickable(interactionSource = interactionSource, indication = null) {
                onCheckedChange(!checked)
            }
            .padding(3.dp)
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier
                .width(22.dp)
                .fillMaxHeight()
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .background(if (checked) PureWhite else DarkGray, RectangleShape)
        )
    }
}

/**
 * Real-time waveform amplitude visualizer moving right-to-left from origin of VU meters.
 * Always listening in light gray; turns solid black when recording!
 */
@Composable
fun RamsWaveformVisualizer(
    waveformPoints: List<Float>,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(LightGray, RectangleShape)
            .border(1.dp, GrayBorder, RectangleShape)
            .testTag("waveform_visualizer")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val centerY = height / 2f

            val totalPoints = waveformPoints.size
            if (totalPoints < 2) return@Canvas

            val colSpacing = width / totalPoints.toFloat()

            // Draw center baseline
            drawLine(
                color = GrayBorder,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 1.dp.toPx()
            )

            // Waveform line color: Light gray when listening, jet black when recording
            val waveColor = if (isRecording) SolidBlack else Color(0xFF9E9E98)

            // Draw vertical amplitude lines
            for (i in 0 until totalPoints) {
                val amp = waveformPoints[i].coerceIn(0.01f, 1.0f)
                val barHalfHeight = (amp * (centerY - 3.dp.toPx())).coerceAtLeast(1.5.dp.toPx())
                val x = i * colSpacing + colSpacing / 2f

                drawLine(
                    color = waveColor,
                    start = Offset(x, centerY - barHalfHeight),
                    end = Offset(x, centerY + barHalfHeight),
                    strokeWidth = (colSpacing - 1.dp.toPx()).coerceAtLeast(1.5.dp.toPx())
                )
            }
        }
    }
}

/**
 * Full-length audio track waveform visualizer with scrubbable playhead for playback.
 */
@Composable
fun RamsFullWaveformVisualizer(
    waveformPoints: List<Float>,
    progress: Float, // 0.0f..1.0f
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(LightGray, RectangleShape)
            .border(1.dp, GrayBorder, RectangleShape)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val norm = (offset.x / size.width).coerceIn(0f, 1f)
                    onScrub(norm)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val norm = (change.position.x / size.width).coerceIn(0f, 1f)
                    onScrub(norm)
                }
            }
            .testTag("full_waveform_visualizer")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val centerY = height / 2f

            val totalPoints = waveformPoints.size
            if (totalPoints < 2) return@Canvas

            val colSpacing = width / totalPoints.toFloat()

            // Baseline
            drawLine(
                color = GrayBorder,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 1.dp.toPx()
            )

            // Draw full-length static audio waveform
            for (i in 0 until totalPoints) {
                val amp = waveformPoints[i].coerceIn(0.01f, 1.0f)
                val barHalfHeight = (amp * (centerY - 4.dp.toPx())).coerceAtLeast(1.5.dp.toPx())
                val x = i * colSpacing + colSpacing / 2f

                val isPlayed = (x / width) <= progress
                drawLine(
                    color = if (isPlayed) SolidBlack else Color(0xFF9E9E98),
                    start = Offset(x, centerY - barHalfHeight),
                    end = Offset(x, centerY + barHalfHeight),
                    strokeWidth = (colSpacing - 1.dp.toPx()).coerceAtLeast(1.5.dp.toPx())
                )
            }

            // Draw playhead vertical scrubbing line
            val playheadX = (progress * width).coerceIn(0f, width)
            drawLine(
                color = RamsRed,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, height),
                strokeWidth = 2.5.dp.toPx()
            )
        }
    }
}

/**
 * Flat dual VU meters with wide, tappable channels that toggle mute when clicked.
 * No "l" or "r" labels below each channel.
 */
@Composable
fun RamsVuMeter(
    meterState: VuMeterState,
    onToggleLeftMute: () -> Unit,
    onToggleRightMute: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(LightGray, RectangleShape)
            .border(1.dp, GrayBorder, RectangleShape)
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .testTag("flat_vu_meter"),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RamsSingleMeterStrip(
            channelData = meterState.left,
            onClick = onToggleLeftMute,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )

        Spacer(modifier = Modifier.width(4.dp))

        RamsSingleMeterStrip(
            channelData = meterState.right,
            onClick = onToggleRightMute,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
    }
}

@Composable
private fun RamsSingleMeterStrip(
    channelData: VuMeterChannelData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (channelData.isMuted) Color(0xFFDCDCD8) else PureWhite, RectangleShape)
            .clickable(interactionSource = interactionSource, indication = null) {
                onClick()
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            if (channelData.isMuted) {
                // Dimmed channel with "MUTE" diagonal slash
                drawLine(
                    color = DarkGray,
                    start = Offset(0f, height),
                    end = Offset(width, 0f),
                    strokeWidth = 2.dp.toPx()
                )
                return@Canvas
            }

            val normPeak = dbToNorm(channelData.peakDb)
            val normHold = dbToNorm(channelData.peakHoldDb)

            // Fill level bar (charcoal / dark gray)
            val fillHeight = normPeak * height
            val topY = height - fillHeight

            drawRect(
                color = MidGray,
                topLeft = Offset(0f, topY),
                size = Size(width, fillHeight)
            )

            // Top line of level (Dark Gray)
            if (normPeak > 0.02f) {
                drawRect(
                    color = DarkGray,
                    topLeft = Offset(0f, topY),
                    size = Size(width, 2.dp.toPx())
                )
            }

            // Peak hold line
            val holdY = (height - normHold * height).coerceIn(0f, height - 2.dp.toPx())
            drawRect(
                color = SolidBlack,
                topLeft = Offset(0f, holdY),
                size = Size(width, 1.5.dp.toPx())
            )

            // Max peak line at top: Red if clipping, faint if normal
            drawRect(
                color = if (channelData.isClipping) RamsRed else GrayBorder,
                topLeft = Offset(0f, 0f),
                size = Size(width, 2.5.dp.toPx())
            )
        }

        if (channelData.isMuted) {
            Text(
                text = "mute",
                fontFamily = MartianMonoFontFamily,
                fontSize = 8.sp,
                color = DarkGray,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
            )
        }
    }
}

private fun dbToNorm(db: Float): Float {
    if (db <= -60f) return 0f
    if (db >= 0f) return 1f
    return (db + 60f) / 60f
}
