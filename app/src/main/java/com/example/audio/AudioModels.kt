package com.example.audio

import java.io.File

data class VuMeterChannelData(
    val peakDb: Float = -60.0f,
    val rmsDb: Float = -60.0f,
    val peakHoldDb: Float = -60.0f,
    val isClipping: Boolean = false,
    val isMuted: Boolean = false
)

data class VuMeterState(
    val left: VuMeterChannelData = VuMeterChannelData(),
    val right: VuMeterChannelData = VuMeterChannelData()
)

data class EqSettings(
    val lowShelfGainDb: Float = 0.0f,  // 100 Hz (-30 dB to +30 dB)
    val midPeakGainDb: Float = 0.0f,    // 1 kHz (-30 dB to +30 dB)
    val highShelfGainDb: Float = 0.0f,  // 8 kHz (-30 dB to +30 dB)
    val isBypassed: Boolean = true      // Disabled (inactive) by default
)

data class RecordingItem(
    val id: String,
    val name: String,
    val file: File,
    val durationMs: Long,
    val sizeBytes: Long,
    val timestamp: Long
)

enum class AppTab {
    RECORDER,
    GALLERY,
    PLAYBACK
}

enum class AudioMode {
    LIVE_INPUT,
    FILE_PLAYBACK
}

data class EngineStatus(
    val isRunning: Boolean = false,
    val isPassthroughActive: Boolean = true, // Default ON
    val volumeOut: Float = 0.5f,             // Default 50%
    val isRecording: Boolean = false,
    val currentRecordingTimeMs: Long = 0L,
    val sampleRate: Int = 44100,
    val bufferSizeFrames: Int = 256,
    val inputGainDb: Float = 0.0f,           // Default 0 dB (-30 dB to +30 dB)
    val isLimiterActive: Boolean = false,    // Default OFF
    val isNoiseRemovalActive: Boolean = false, // Default OFF
    val noiseRemovalThresholdDb: Float = -42.0f,
    val inputSourceInfo: String = "in",
    val isStereoInput: Boolean = true,
    val isLeftMuted: Boolean = false,
    val isRightMuted: Boolean = false,
    val mode: AudioMode = AudioMode.LIVE_INPUT,
    val activePlaybackFile: RecordingItem? = null,
    val playbackPositionMs: Long = 0L,
    val playbackDurationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val playbackFileWaveform: List<Float> = emptyList(),
    val isPlayingFile: Boolean = false,
    val isLooping: Boolean = false,
    val errorMessage: String? = null
)
