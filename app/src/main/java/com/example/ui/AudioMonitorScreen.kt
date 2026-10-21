package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.audio.AppTab
import com.example.ui.tabs.GalleryTab
import com.example.ui.tabs.PlaybackTab
import com.example.ui.tabs.RecorderTab
import com.example.ui.theme.DarkGray
import com.example.ui.theme.GrayBorder
import com.example.ui.theme.LightGray
import com.example.ui.theme.MartianMonoFontFamily
import com.example.ui.theme.OffWhite
import com.example.ui.theme.PureWhite
import com.example.ui.theme.RamsRed
import com.example.ui.theme.SolidBlack
import kotlinx.coroutines.flow.collectLatest

@Composable
fun AudioMonitorScreen(
    viewModel: AudioMonitorViewModel,
    onRequestPermission: () -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val hasPermission by viewModel.hasPermission.collectAsStateWithLifecycle()
    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val vuMeterState by viewModel.vuMeterState.collectAsStateWithLifecycle()
    val playbackVuMeterState by viewModel.playbackVuMeterState.collectAsStateWithLifecycle()
    val waveformPoints by viewModel.waveformState.collectAsStateWithLifecycle()
    val eqSettings by viewModel.eqSettings.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.userMessage.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = OffWhite,
        contentWindowInsets = WindowInsets.statusBars
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Dieter Rams minimalist Top Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PureWhite)
                    .border(1.dp, GrayBorder, RectangleShape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(if (engineStatus.isRecording) CircleShape else RectangleShape)
                            .background(if (engineStatus.isRecording) RamsRed else SolidBlack)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "recoda",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = SolidBlack
                    )
                }

                // Power / Engine state status text
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { viewModel.toggleEngine() }
                ) {
                    Text(
                        text = if (engineStatus.isRunning) "engine on" else "engine off",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 9.sp,
                        color = if (engineStatus.isRunning) SolidBlack else DarkGray
                    )
                }
            }

            // Minimalist Tab Navigation Bar: "recorder", "gallery" (no count), "playback"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LightGray)
                    .border(0.5.dp, GrayBorder, RectangleShape)
            ) {
                RamsTabButton(
                    title = "recorder",
                    isSelected = currentTab == AppTab.RECORDER,
                    onClick = { viewModel.setTab(AppTab.RECORDER) },
                    modifier = Modifier.weight(1f),
                    testTag = "tab_recorder"
                )

                RamsTabButton(
                    title = "gallery",
                    isSelected = currentTab == AppTab.GALLERY,
                    onClick = {
                        viewModel.loadRecordings()
                        viewModel.setTab(AppTab.GALLERY)
                    },
                    modifier = Modifier.weight(1f),
                    testTag = "tab_gallery"
                )

                RamsTabButton(
                    title = "playback",
                    isSelected = currentTab == AppTab.PLAYBACK,
                    onClick = { viewModel.setTab(AppTab.PLAYBACK) },
                    modifier = Modifier.weight(1f),
                    testTag = "tab_playback"
                )
            }

            // Permission notification banner if RECORD_AUDIO not granted
            if (!hasPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(RamsRed)
                        .clickable { onRequestPermission() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "tap here to grant record_audio permission",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = PureWhite
                    )
                }
            }

            // Tab Content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (currentTab) {
                    AppTab.RECORDER -> {
                        RecorderTab(
                            engineStatus = engineStatus,
                            vuMeterState = vuMeterState,
                            waveformPoints = waveformPoints,
                            onToggleLeftMute = { viewModel.toggleLeftMute() },
                            onToggleRightMute = { viewModel.toggleRightMute() },
                            onGainChange = { viewModel.setGain(it) },
                            onLimiterToggle = { viewModel.toggleLimiter(it) },
                            onPassthroughToggle = { viewModel.setPassthrough(it) },
                            onVolumeOutChange = { viewModel.setVolumeOut(it) },
                            onNoiseRemovalToggle = { viewModel.toggleNoiseRemoval(it) },
                            onNoiseThresholdChange = { viewModel.setNoiseRemovalThreshold(it) },
                            onToggleRecording = { viewModel.toggleRecording() }
                        )
                    }

                    AppTab.GALLERY -> {
                        GalleryTab(
                            recordings = recordings,
                            engineStatus = engineStatus,
                            onPlayRecording = { viewModel.playFile(it) },
                            onRenameRecording = { item, newName -> viewModel.renameRecording(item, newName) },
                            onShareRecording = { viewModel.shareRecording(context, it) },
                            onDeleteRecording = { viewModel.deleteRecording(it) },
                            onExportRecording = { item, fmt, preset ->
                                viewModel.exportRecording(item, fmt, preset, context)
                            },
                            onNavigateToRecorder = { viewModel.setTab(AppTab.RECORDER) }
                        )
                    }

                    AppTab.PLAYBACK -> {
                        PlaybackTab(
                            engineStatus = engineStatus,
                            eqSettings = eqSettings,
                            vuMeterState = playbackVuMeterState,
                            recordings = recordings,
                            onToggleLeftMute = { viewModel.toggleLeftMute() },
                            onToggleRightMute = { viewModel.toggleRightMute() },
                            onPlayFile = { viewModel.playFile(it) },
                            onResumePlayback = { viewModel.resumePlayback() },
                            onPausePlayback = { viewModel.pausePlayback() },
                            onStopPlayback = { viewModel.stopPlayback() },
                            onSeekPlayback = { viewModel.seekPlayback(it) },
                            onCycleReverseSpeed = { viewModel.cycleReverseSpeed() },
                            onCycleForwardSpeed = { viewModel.cycleForwardSpeed() },
                            onToggleLoop = { viewModel.toggleLoopPlayback() },
                            onLowShelfChange = { viewModel.setLowShelfGain(it) },
                            onMidPeakChange = { viewModel.setMidPeakGain(it) },
                            onHighShelfChange = { viewModel.setHighShelfGain(it) },
                            onEqBypassChange = { viewModel.setEqBypassed(it) },
                            onGainChange = { viewModel.setGain(it) },
                            onVolumeOutChange = { viewModel.setVolumeOut(it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RamsTabButton(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "tab_button"
) {
    Column(
        modifier = modifier
            .background(if (isSelected) PureWhite else LightGray, RectangleShape)
            .clickable { onClick() }
            .padding(vertical = 10.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            fontFamily = MartianMonoFontFamily,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) SolidBlack else DarkGray
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Bottom indicator line
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(2.dp)
                .background(if (isSelected) SolidBlack else Color.Transparent)
        )
    }
}
