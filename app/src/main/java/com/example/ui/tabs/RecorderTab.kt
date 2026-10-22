package com.example.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.EngineStatus
import com.example.audio.VuMeterState
import com.example.ui.components.RamsFlatSlider
import com.example.ui.components.RamsFlatToggle
import com.example.ui.components.RamsVuMeter
import com.example.ui.components.RamsWaveformVisualizer
import com.example.ui.theme.DarkGray
import com.example.ui.theme.GrayBorder
import com.example.ui.theme.MartianMonoFontFamily
import com.example.ui.theme.OffWhite
import com.example.ui.theme.PureWhite
import com.example.ui.theme.RamsRed
import com.example.ui.theme.SolidBlack
import java.util.Locale

@Composable
fun RecorderTab(
    engineStatus: EngineStatus,
    vuMeterState: VuMeterState,
    waveformPoints: List<Float>,
    onToggleLeftMute: () -> Unit,
    onToggleRightMute: () -> Unit,
    onGainChange: (Float) -> Unit,
    onLimiterToggle: (Boolean) -> Unit,
    onPassthroughToggle: (Boolean) -> Unit,
    onVolumeOutChange: (Float) -> Unit,
    onNoiseRemovalToggle: (Boolean) -> Unit,
    onNoiseThresholdChange: (Float) -> Unit,
    onKeepScreenAwakeToggle: (Boolean) -> Unit,
    onToggleRecording: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OffWhite)
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // TOP SECTION (Approx 30% height): Waveform + Dual VU Meters (flushed to top)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.30f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // Left 75%: Real-time Waveform amplitude visualizer moving right-to-left
                // Light gray when listening; turns solid black when recording
                RamsWaveformVisualizer(
                    waveformPoints = waveformPoints,
                    isRecording = engineStatus.isRecording,
                    modifier = Modifier
                        .weight(0.75f)
                        .fillMaxHeight()
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Right 25%: Dual VU meters with wide, tappable channel strips
                RamsVuMeter(
                    meterState = vuMeterState,
                    onToggleLeftMute = onToggleLeftMute,
                    onToggleRightMute = onToggleRightMute,
                    modifier = Modifier
                        .weight(0.25f)
                        .fillMaxHeight()
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // MIDDLE SECTION: Gain Limiter, Passthrough/Vol, Noise Filter
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.40f),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // 1. Input Gain & Limiter Slider (-30 dB to +30 dB, default 0 dB)
            // Double tapping empty/text area or slider resets to 0 dB
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PureWhite)
                    .border(1.dp, GrayBorder, RectangleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onGainChange(0.0f) })
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "gain",
                            fontFamily = MartianMonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SolidBlack
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val formattedGain = String.format(Locale.US, "%+.1f db", engineStatus.inputGainDb)
                        Text(
                            text = formattedGain,
                            fontFamily = MartianMonoFontFamily,
                            fontSize = 10.sp,
                            color = DarkGray
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "limiter",
                            fontFamily = MartianMonoFontFamily,
                            fontSize = 9.sp,
                            color = if (engineStatus.isLimiterActive) SolidBlack else DarkGray
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        RamsFlatToggle(
                            checked = engineStatus.isLimiterActive,
                            onCheckedChange = onLimiterToggle,
                            testTag = "limiter_toggle"
                        )
                    }
                }

                RamsFlatSlider(
                    value = engineStatus.inputGainDb,
                    onValueChange = onGainChange,
                    valueRange = -30f..30f,
                    onDoubleTap = { onGainChange(0.0f) },
                    testTag = "gain_slider"
                )
            }

            // 2. Monitor / Passthrough Toggle with Volume Out Controller (Default ON, Volume 50%)
            // Double tapping empty/text area or slider resets to 0.5f (50%)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PureWhite)
                    .border(1.dp, GrayBorder, RectangleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onVolumeOutChange(0.5f) })
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "passthrough",
                            fontFamily = MartianMonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SolidBlack
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (engineStatus.isPassthroughActive) "audible" else "muted",
                            fontFamily = MartianMonoFontFamily,
                            fontSize = 10.sp,
                            color = DarkGray
                        )
                    }

                    RamsFlatToggle(
                        checked = engineStatus.isPassthroughActive,
                        onCheckedChange = onPassthroughToggle,
                        testTag = "passthrough_toggle"
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "out volume",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 9.sp,
                        color = DarkGray
                    )
                    Text(
                        text = "${(engineStatus.volumeOut * 100).toInt()}%",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 10.sp,
                        color = SolidBlack,
                        fontWeight = FontWeight.Bold
                    )
                }

                RamsFlatSlider(
                    value = engineStatus.volumeOut,
                    onValueChange = onVolumeOutChange,
                    valueRange = 0f..1f,
                    enabled = engineStatus.isPassthroughActive,
                    onDoubleTap = { onVolumeOutChange(0.5f) },
                    testTag = "volume_out_slider"
                )
            }

            // 3. Noise Removal Filter Toggle with Horizontal dB Slider (Default OFF, 0 dB)
            // Double tapping empty/text area or slider resets to 0 dB
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PureWhite)
                    .border(1.dp, GrayBorder, RectangleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onNoiseThresholdChange(0.0f) })
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "noise suppression",
                            fontFamily = MartianMonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SolidBlack
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val formattedThresh = String.format(Locale.US, "%.0f db", engineStatus.noiseRemovalThresholdDb)
                        Text(
                            text = if (engineStatus.isNoiseRemovalActive) formattedThresh else "off",
                            fontFamily = MartianMonoFontFamily,
                            fontSize = 10.sp,
                            color = if (engineStatus.isNoiseRemovalActive) SolidBlack else DarkGray
                        )
                    }

                    RamsFlatToggle(
                        checked = engineStatus.isNoiseRemovalActive,
                        onCheckedChange = onNoiseRemovalToggle,
                        testTag = "noise_filter_toggle"
                    )
                }

                RamsFlatSlider(
                    value = engineStatus.noiseRemovalThresholdDb,
                    onValueChange = onNoiseThresholdChange,
                    valueRange = -60f..0f,
                    enabled = engineStatus.isNoiseRemovalActive,
                    onDoubleTap = { onNoiseThresholdChange(0.0f) },
                    testTag = "noise_filter_slider"
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // BOTTOM SECTION: Screen awake toggle + Big Solid Black CTA "rec"
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.24f),
            verticalArrangement = Arrangement.Bottom
        ) {
            // Keep Screen Awake while recording option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PureWhite)
                    .border(1.dp, GrayBorder, RectangleShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "screen awake",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SolidBlack
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (engineStatus.isKeepScreenAwake) "on during rec" else "off",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 10.sp,
                        color = if (engineStatus.isKeepScreenAwake) SolidBlack else DarkGray
                    )
                }

                RamsFlatToggle(
                    checked = engineStatus.isKeepScreenAwake,
                    onCheckedChange = onKeepScreenAwakeToggle,
                    testTag = "screen_awake_toggle"
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val interactionSource = remember { MutableInteractionSource() }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .background(SolidBlack, RectangleShape)
                    .clickable(interactionSource = interactionSource, indication = null) {
                        onToggleRecording()
                    }
                    .testTag("rec_button"),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(if (engineStatus.isRecording) CircleShape else RectangleShape)
                            .background(RamsRed)
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    Text(
                        text = if (engineStatus.isRecording) "stop rec" else "rec",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = PureWhite
                    )

                    if (engineStatus.isRecording) {
                        Spacer(modifier = Modifier.width(12.dp))
                        val totalSec = engineStatus.currentRecordingTimeMs / 1000
                        val min = totalSec / 60
                        val sec = totalSec % 60
                        Text(
                            text = String.format(Locale.US, "%02d:%02d", min, sec),
                            fontFamily = MartianMonoFontFamily,
                            fontSize = 13.sp,
                            color = RamsRed
                        )
                    }
                }
            }
        }
    }
}
