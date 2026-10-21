package com.example.ui.tabs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.EngineStatus
import com.example.audio.EqSettings
import com.example.audio.RecordingItem
import com.example.audio.VuMeterState
import com.example.ui.components.RamsFlatSlider
import com.example.ui.components.RamsFlatToggle
import com.example.ui.components.RamsFullWaveformVisualizer
import com.example.ui.components.RamsVuMeter
import com.example.ui.theme.DarkGray
import com.example.ui.theme.GrayBorder
import com.example.ui.theme.LightGray
import com.example.ui.theme.MartianMonoFontFamily
import com.example.ui.theme.OffWhite
import com.example.ui.theme.PureWhite
import com.example.ui.theme.RamsRed
import com.example.ui.theme.SolidBlack
import java.util.Locale

@Composable
fun PlaybackTab(
    engineStatus: EngineStatus,
    eqSettings: EqSettings,
    vuMeterState: VuMeterState,
    recordings: List<RecordingItem>,
    onToggleLeftMute: () -> Unit,
    onToggleRightMute: () -> Unit,
    onPlayFile: (RecordingItem) -> Unit,
    onResumePlayback: () -> Unit,
    onPausePlayback: () -> Unit,
    onStopPlayback: () -> Unit,
    onSeekPlayback: (Long) -> Unit,
    onCycleReverseSpeed: () -> Unit,
    onCycleForwardSpeed: () -> Unit,
    onToggleLoop: () -> Unit,
    onLowShelfChange: (Float) -> Unit,
    onMidPeakChange: (Float) -> Unit,
    onHighShelfChange: (Float) -> Unit,
    onEqBypassChange: (Boolean) -> Unit,
    onGainChange: (Float) -> Unit,
    onVolumeOutChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentFile = engineStatus.activePlaybackFile ?: recordings.firstOrNull()
    val scrollState = rememberScrollState()

    // Compact layout structured to fit 100% dvh
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OffWhite)
            .verticalScroll(scrollState)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. TOP SECTION: Only file name on top + Full Audio Waveform & Playback-only VU Meters
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(102.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Keep just the file name on top as requested
                Text(
                    text = currentFile?.name?.lowercase() ?: "no file selected",
                    fontFamily = MartianMonoFontFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = SolidBlack,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )

                // Current speed badge if not 100%
                val speedLabel = when (engineStatus.playbackSpeed) {
                    -0.25f -> "-25%"
                    -0.50f -> "-50%"
                    -1.00f -> "-100%"
                    -2.00f -> "-200%"
                    0.25f -> "+25%"
                    0.50f -> "+50%"
                    2.00f -> "+200%"
                    else -> "100%"
                }

                if (engineStatus.playbackSpeed != 1.0f) {
                    Text(
                        text = "speed: $speedLabel",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 9.sp,
                        color = RamsRed,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // Full-length audio waveform (with tap & drag scrubbing playhead)
                val durMs = (currentFile?.durationMs ?: 1000L).coerceAtLeast(1L)
                val progress = (engineStatus.playbackPositionMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f)
                val waveformPoints = if (engineStatus.playbackFileWaveform.isNotEmpty()) {
                    engineStatus.playbackFileWaveform
                } else {
                    List(80) { 0.05f }
                }

                RamsFullWaveformVisualizer(
                    waveformPoints = waveformPoints,
                    progress = progress,
                    onScrub = { fraction ->
                        val targetMs = (fraction * durMs).toLong()
                        onSeekPlayback(targetMs)
                    },
                    modifier = Modifier
                        .weight(0.75f)
                        .fillMaxHeight()
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Dual VU meters: only move when audio file is playing, silent otherwise!
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

        // 2. PLAYBACK TRANSPORT CONTROLS & TIMELINE (with initial |< and final >| buttons)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PureWhite)
                .border(1.dp, GrayBorder, RectangleShape)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            // Time readouts
            val posSec = engineStatus.playbackPositionMs / 1000
            val durSec = (currentFile?.durationMs ?: 0L) / 1000
            val timeStr = String.format(
                Locale.US,
                "%02d:%02d / %02d:%02d",
                posSec / 60, posSec % 60,
                durSec / 60, durSec % 60
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timeStr,
                    fontFamily = MartianMonoFontFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = SolidBlack
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Full-width seek slider
            val maxDur = (currentFile?.durationMs ?: 1000L).coerceAtLeast(1000L).toFloat()
            RamsFlatSlider(
                value = engineStatus.playbackPositionMs.toFloat().coerceIn(0f, maxDur),
                onValueChange = { onSeekPlayback(it.toLong()) },
                valueRange = 0f..maxDur,
                onDoubleTap = { onSeekPlayback(0L) },
                modifier = Modifier.fillMaxWidth(),
                testTag = "playback_seek_slider"
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Single unified transport bar: all 7 buttons aligned to the same height in one line occupying full width
            // Weights: "go to beginning" (1fr), reverse (1fr), play/pause (2fr), stop (1fr), forward (1fr), loop (1fr), "go to end" (1fr)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. "go to beginning" (1fr)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(LightGray, RectangleShape)
                        .border(1.dp, GrayBorder, RectangleShape)
                        .clickable { onSeekPlayback(0L) }
                        .testTag("playback_jump_start"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "|<",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = SolidBlack
                    )
                }

                // 2. "reverse" (1fr)
                val isRevActive = engineStatus.playbackSpeed < 0f
                val revSpeedText = when (engineStatus.playbackSpeed) {
                    -0.25f -> "-25%"
                    -0.50f -> "-50%"
                    -1.00f -> "-100%"
                    -2.00f -> "-200%"
                    else -> "<<"
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (isRevActive) RamsRed else LightGray, RectangleShape)
                        .border(1.dp, GrayBorder, RectangleShape)
                        .clickable { onCycleReverseSpeed() }
                        .testTag("playback_rev_speed_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = revSpeedText,
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isRevActive) PureWhite else SolidBlack
                    )
                }

                // 3. "play/pause" (2fr)
                Box(
                    modifier = Modifier
                        .weight(2f)
                        .fillMaxHeight()
                        .background(SolidBlack, RectangleShape)
                        .clickable {
                            if (engineStatus.isPlayingFile) {
                                onPausePlayback()
                            } else {
                                onResumePlayback()
                            }
                        }
                        .testTag("playback_play_pause_button"),
                    contentAlignment = Alignment.Center
                ) {
                    if (engineStatus.isPlayingFile) {
                        // Pause symbol (two geometric rectangles)
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(modifier = Modifier.size(3.dp, 12.dp).background(PureWhite))
                            Box(modifier = Modifier.size(3.dp, 12.dp).background(PureWhite))
                        }
                    } else {
                        // Play symbol (geometric triangle)
                        Canvas(modifier = Modifier.size(12.dp)) {
                            val path = Path().apply {
                                moveTo(2.dp.toPx(), 0f)
                                lineTo(size.width, size.height / 2f)
                                lineTo(2.dp.toPx(), size.height)
                                close()
                            }
                            drawPath(path, PureWhite)
                        }
                    }
                }

                // 4. "stop" (1fr)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(LightGray, RectangleShape)
                        .border(1.dp, GrayBorder, RectangleShape)
                        .clickable { onStopPlayback() }
                        .testTag("playback_stop_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(10.dp).background(SolidBlack))
                }

                // 5. "forward" (1fr)
                val isFwdActive = engineStatus.playbackSpeed > 0f && engineStatus.playbackSpeed != 1.0f
                val fwdSpeedText = when (engineStatus.playbackSpeed) {
                    2.00f -> "+200%"
                    0.25f -> "+25%"
                    0.50f -> "+50%"
                    else -> ">>"
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (isFwdActive) SolidBlack else LightGray, RectangleShape)
                        .border(1.dp, GrayBorder, RectangleShape)
                        .clickable { onCycleForwardSpeed() }
                        .testTag("playback_fwd_speed_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = fwdSpeedText,
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isFwdActive) PureWhite else SolidBlack
                    )
                }

                // 6. [the new] "loop toggle" (1fr)
                val isLooping = engineStatus.isLooping
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (isLooping) SolidBlack else LightGray, RectangleShape)
                        .border(1.dp, GrayBorder, RectangleShape)
                        .clickable { onToggleLoop() }
                        .testTag("playback_loop_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "loop",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 9.sp,
                        fontWeight = if (isLooping) FontWeight.Bold else FontWeight.Normal,
                        color = if (isLooping) PureWhite else SolidBlack
                    )
                }

                // 7. "go to end" (1fr)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(LightGray, RectangleShape)
                        .border(1.dp, GrayBorder, RectangleShape)
                        .clickable { onSeekPlayback(maxDur.toLong()) }
                        .testTag("playback_jump_end"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = ">|",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = SolidBlack
                    )
                }
            }
        }

        // 3. 3-BAND BIQUAD EQ: 3 stacked horizontal sliders (-30 to +30 dB each)
        // Double tapping each slider or its section resets it to default 0 dB
        // No reset all button (redundant with individual double-tap reset)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PureWhite)
                .border(1.dp, GrayBorder, RectangleShape)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            val isEqActive = !eqSettings.isBypassed
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "3-band biquad eq",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SolidBlack
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isEqActive) "enabled" else "disabled",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 9.sp,
                        color = if (isEqActive) SolidBlack else DarkGray
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isEqActive) "enabled" else "disabled",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 9.sp,
                        color = if (isEqActive) SolidBlack else DarkGray
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    RamsFlatToggle(
                        checked = isEqActive,
                        onCheckedChange = { checked -> onEqBypassChange(!checked) },
                        testTag = "playback_eq_toggle"
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 1. Low Shelf 100Hz (-30 dB to +30 dB)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onLowShelfChange(0.0f) })
                    }
                    .padding(vertical = 1.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "low 100hz",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 9.sp,
                        color = if (isEqActive) SolidBlack else DarkGray
                    )
                    val valStr = String.format(Locale.US, "%+.1f db", eqSettings.lowShelfGainDb)
                    Text(
                        text = valStr,
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 9.sp,
                        color = if (isEqActive) SolidBlack else DarkGray
                    )
                }
                RamsFlatSlider(
                    value = eqSettings.lowShelfGainDb,
                    onValueChange = onLowShelfChange,
                    valueRange = -30f..30f,
                    enabled = isEqActive,
                    onDoubleTap = { onLowShelfChange(0.0f) },
                    testTag = "eq_low_slider"
                )
            }

            // 2. Mid Peak 1kHz (-30 dB to +30 dB)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onMidPeakChange(0.0f) })
                    }
                    .padding(vertical = 1.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "mid 1khz",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 9.sp,
                        color = if (isEqActive) SolidBlack else DarkGray
                    )
                    val valStr = String.format(Locale.US, "%+.1f db", eqSettings.midPeakGainDb)
                    Text(
                        text = valStr,
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 9.sp,
                        color = if (isEqActive) SolidBlack else DarkGray
                    )
                }
                RamsFlatSlider(
                    value = eqSettings.midPeakGainDb,
                    onValueChange = onMidPeakChange,
                    valueRange = -30f..30f,
                    enabled = isEqActive,
                    onDoubleTap = { onMidPeakChange(0.0f) },
                    testTag = "eq_mid_slider"
                )
            }

            // 3. High Shelf 8kHz (-30 dB to +30 dB)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onHighShelfChange(0.0f) })
                    }
                    .padding(vertical = 1.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "high 8khz",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 9.sp,
                        color = if (isEqActive) SolidBlack else DarkGray
                    )
                    val valStr = String.format(Locale.US, "%+.1f db", eqSettings.highShelfGainDb)
                    Text(
                        text = valStr,
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 9.sp,
                        color = if (isEqActive) SolidBlack else DarkGray
                    )
                }
                RamsFlatSlider(
                    value = eqSettings.highShelfGainDb,
                    onValueChange = onHighShelfChange,
                    valueRange = -30f..30f,
                    enabled = isEqActive,
                    onDoubleTap = { onHighShelfChange(0.0f) },
                    testTag = "eq_high_slider"
                )
            }
        }

        // 4. DSP GAIN & MASTER VOLUME SLIDERS
        // Double tapping resets DSP gain to 0 dB and Master volume to 50%
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PureWhite)
                .border(1.dp, GrayBorder, RectangleShape)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onGainChange(0.0f) })
                    }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "dsp gain",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = SolidBlack
                    )
                    val gainStr = String.format(Locale.US, "%+.1f db", engineStatus.inputGainDb)
                    Text(
                        text = gainStr,
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 9.sp,
                        color = DarkGray
                    )
                }
                RamsFlatSlider(
                    value = engineStatus.inputGainDb,
                    onValueChange = onGainChange,
                    valueRange = -30f..30f,
                    onDoubleTap = { onGainChange(0.0f) },
                    testTag = "playback_gain_slider"
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onVolumeOutChange(0.5f) })
                    }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "master volume",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = SolidBlack
                    )
                    Text(
                        text = "${(engineStatus.volumeOut * 100).toInt()}%",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 9.sp,
                        color = DarkGray
                    )
                }
                RamsFlatSlider(
                    value = engineStatus.volumeOut,
                    onValueChange = onVolumeOutChange,
                    valueRange = 0f..1f,
                    onDoubleTap = { onVolumeOutChange(0.5f) },
                    testTag = "playback_volume_slider"
                )
            }
        }
    }
}
