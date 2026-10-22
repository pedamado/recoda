package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Real-time low-latency audio engine for external instrument monitoring, WAV recording,
 * and synchronized variable-speed & reverse WAV playback with 3-band EQ and channel muting.
 * Pure Kotlin DSP - no C++ or NDK.
 */
class AudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "AudioEngine"
        const val SAMPLE_RATE = 44100
        private const val LOW_SHELF_FREQ = 100.0f
        private const val MID_PEAK_FREQ = 1000.0f
        private const val HIGH_SHELF_FREQ = 8000.0f

        private const val CLIP_THRESHOLD_LINEAR = 0.98855f
        private const val MIN_DB = -60.0f
        const val WAVEFORM_HISTORY_SIZE = 120
    }

    val settings = AppSettings(context)

    // Engine State - Auto-recording is strictly FALSE by default
    @Volatile private var isRunning = false
    @Volatile private var isPassthroughEnabled = settings.isPassthroughActive
    @Volatile private var volumeOut = settings.volumeOut
    @Volatile private var isRecording = false

    // Channel Muting
    @Volatile private var isLeftMuted = settings.isLeftMuted
    @Volatile private var isRightMuted = settings.isRightMuted

    private var audioThread: Thread? = null
    private var playbackThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var m4aWriter: M4aWriter? = null
    private var currentRecordingFile: File? = null
    private var activeDecodedAudio: DecodedAudio? = null

    // Format & Hardware Telemetry
    private var isStereoInput = true
    private var inputSourceUsed = "in"
    private var bufferSizeFrames = 256

    // Software Input Gain (-30 dB to +30 dB, default 0 dB)
    @Volatile private var targetGainDb: Float = settings.inputGainDb.coerceIn(-30.0f, 30.0f)
    @Volatile private var currentGainLinear: Float = 10.0f.pow(targetGainDb / 20.0f)

    // Input Limiter (default OFF)
    @Volatile private var isLimiterActive: Boolean = settings.isLimiterActive

    // Audible Noise Removal / Gate Filter (default OFF, -42 dB threshold)
    @Volatile private var isNoiseRemovalActive: Boolean = settings.isNoiseRemovalActive
    @Volatile private var noiseRemovalThresholdDb: Float = settings.noiseRemovalThresholdDb
    private var gateEnvelope = 0.0f

    // 3-Band Biquad Filters (Stereo)
    private val lowShelfFilter = BiquadFilter()
    private val midPeakFilter = BiquadFilter()
    private val highShelfFilter = BiquadFilter()
    @Volatile private var isEqBypassed = settings.isEqBypassed

    // File Playback State & Speed (-200% to +200%)
    @Volatile private var isPlayingFile = false
    @Volatile private var playbackFile: File? = null
    @Volatile private var playbackSeekRequestedMs: Long? = null
    @Volatile private var currentPlaybackMs = 0L
    @Volatile private var totalPlaybackDurationMs = 0L
    @Volatile private var playbackSpeed: Float = 1.0f
    @Volatile private var isLooping: Boolean = false
    private var currentFileWaveform: List<Float> = emptyList()

    // Metering & Waveform State
    private val _vuMeterState = MutableStateFlow(VuMeterState())
    val vuMeterState: StateFlow<VuMeterState> = _vuMeterState.asStateFlow()

    // Dedicated Playback VU Metering (only moves when audio file is playing, silent otherwise)
    private val _playbackVuMeterState = MutableStateFlow(
        VuMeterState(
            left = VuMeterChannelData(peakDb = MIN_DB, rmsDb = MIN_DB, peakHoldDb = MIN_DB, isMuted = isLeftMuted),
            right = VuMeterChannelData(peakDb = MIN_DB, rmsDb = MIN_DB, peakHoldDb = MIN_DB, isMuted = isRightMuted)
        )
    )
    val playbackVuMeterState: StateFlow<VuMeterState> = _playbackVuMeterState.asStateFlow()

    // Real-time rolling waveform: newest point is at the right, scrolling left
    private val waveformBuffer = FloatArray(WAVEFORM_HISTORY_SIZE) { 0.02f }
    private val _waveformState = MutableStateFlow(waveformBuffer.toList())
    val waveformState: StateFlow<List<Float>> = _waveformState.asStateFlow()

    // Engine Status Flow initialized with persisted settings
    private val _engineStatus = MutableStateFlow(
        EngineStatus(
            isPassthroughActive = settings.isPassthroughActive,
            volumeOut = settings.volumeOut,
            isRecording = false,
            inputGainDb = settings.inputGainDb.coerceIn(-30.0f, 30.0f),
            isLimiterActive = settings.isLimiterActive,
            isNoiseRemovalActive = settings.isNoiseRemovalActive,
            noiseRemovalThresholdDb = settings.noiseRemovalThresholdDb,
            isLeftMuted = settings.isLeftMuted,
            isRightMuted = settings.isRightMuted
        )
    )
    val engineStatus: StateFlow<EngineStatus> = _engineStatus.asStateFlow()

    // Live Peak hold decay state
    private var leftPeakHoldDb = MIN_DB
    private var rightPeakHoldDb = MIN_DB
    private var leftPeakHoldTime = 0L
    private var rightPeakHoldTime = 0L
    private var leftClipHoldTime = 0L
    private var rightClipHoldTime = 0L

    // Playback Peak hold decay state
    private var playbackLeftPeakHoldDb = MIN_DB
    private var playbackRightPeakHoldDb = MIN_DB
    private var playbackLeftPeakHoldTime = 0L
    private var playbackRightPeakHoldTime = 0L

    init {
        updateEq(
            EqSettings(
                lowShelfGainDb = settings.lowShelfGainDb.coerceIn(-30.0f, 30.0f),
                midPeakGainDb = settings.midPeakGainDb.coerceIn(-30.0f, 30.0f),
                highShelfGainDb = settings.highShelfGainDb.coerceIn(-30.0f, 30.0f),
                isBypassed = settings.isEqBypassed
            )
        )
    }

    /**
     * Starts live audio engine.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (isRunning) return

        try {
            initAudioRecord()
            initAudioTrack()

            isRunning = true
            updateStatus()

            audioThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                liveAudioLoop()
            }, "RecodaLiveAudioThread").apply {
                start()
            }
            Log.i(TAG, "Audio engine started: $inputSourceUsed, stereo=$isStereoInput")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio engine", e)
            _engineStatus.value = _engineStatus.value.copy(
                isRunning = false,
                errorMessage = e.message ?: "Failed to start audio engine"
            )
            stop()
        }
    }

    /**
     * Stops the audio engine and releases hardware handles.
     */
    @Synchronized
    fun stop() {
        isRunning = false
        stopPlayback()
        stopRecording()

        audioThread?.let {
            try {
                it.join(1000)
            } catch (_: InterruptedException) {}
        }
        audioThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioTrack", e)
        }
        audioTrack = null

        _engineStatus.value = _engineStatus.value.copy(isRunning = false)
        _vuMeterState.value = VuMeterState()
    }

    @SuppressLint("MissingPermission")
    private fun initAudioRecord() {
        val sampleRate = SAMPLE_RATE

        val sourcesToTry = listOf(
            MediaRecorder.AudioSource.DEFAULT to "in",
            MediaRecorder.AudioSource.MIC to "mic",
            MediaRecorder.AudioSource.UNPROCESSED to "unprocessed"
        )

        var record: AudioRecord? = null
        isStereoInput = true

        for ((source, label) in sourcesToTry) {
            val minStereo = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            if (minStereo > 0) {
                record = tryCreateAudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_STEREO, minStereo)
                if (record != null) {
                    isStereoInput = true
                    inputSourceUsed = label
                    break
                }
            }

            val minMono = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minMono > 0) {
                record = tryCreateAudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, minMono)
                if (record != null) {
                    isStereoInput = false
                    inputSourceUsed = "$label (mono)"
                    break
                }
            }
        }

        if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, if (isStereoInput) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            bufferSizeFrames = max(256, (minBuf / (if (isStereoInput) 4 else 2)))
            audioRecord = record
            record.startRecording()
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryCreateAudioRecord(source: Int, sampleRate: Int, channelConfig: Int, minBufferSize: Int): AudioRecord? {
        return try {
            val bufferSize = max(minBufferSize * 2, 1024)
            val record = AudioRecord(source, sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            if (record.state == AudioRecord.STATE_INITIALIZED) record else {
                record.release()
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun initAudioTrack() {
        if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) return

        val sampleRate = SAMPLE_RATE
        val outChannelConfig = AudioFormat.CHANNEL_OUT_STEREO
        val minTrackBuf = AudioTrack.getMinBufferSize(sampleRate, outChannelConfig, AudioFormat.ENCODING_PCM_16BIT)
        val trackBufSize = max(minTrackBuf, 1024)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setChannelMask(outChannelConfig)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .build()

        val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(trackBufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                audioAttributes,
                audioFormat,
                trackBufSize,
                AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        }

        if (track.state == AudioTrack.STATE_INITIALIZED) {
            track.play()
        }
        audioTrack = track
    }

    /**
     * Real-time live input DSP processing loop.
     */
    private fun liveAudioLoop() {
        val inputChannels = if (isStereoInput) 2 else 1
        val frameCount = 256
        val inShortArray = ShortArray(frameCount * inputChannels)
        val outShortArray = ShortArray(frameCount * 2)

        var lastMeterUpdateTime = 0L

        while (isRunning) {
            if (isPlayingFile) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }

            val record = audioRecord
            val framesRead: Int
            if (record != null) {
                val readSamples = record.read(inShortArray, 0, inShortArray.size)
                if (readSamples <= 0) {
                    if (readSamples == AudioRecord.ERROR_INVALID_OPERATION || readSamples == AudioRecord.ERROR_BAD_VALUE) {
                        break
                    }
                    continue
                }
                framesRead = readSamples / inputChannels
            } else {
                framesRead = frameCount
                inShortArray.fill(0)
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    break
                }
            }

            if (framesRead == 0) continue

            val targetGainLinear = 10.0f.pow(targetGainDb / 20.0f)
            val gainStep = (targetGainLinear - currentGainLinear) / framesRead
            val gateThresholdLinear = 10.0f.pow(noiseRemovalThresholdDb / 20.0f)

            var leftPeakRaw = 0.0f
            var rightPeakRaw = 0.0f
            var leftSumSq = 0.0
            var rightSumSq = 0.0

            val bypassEq = isEqBypassed
            val limiter = isLimiterActive
            val outVol = volumeOut
            val noiseRemoval = isNoiseRemovalActive
            val muteL = isLeftMuted
            val muteR = isRightMuted

            for (i in 0 until framesRead) {
                currentGainLinear += gainStep

                val rawLeft: Float
                val rawRight: Float
                if (isStereoInput) {
                    rawLeft = inShortArray[i * 2] / 32768.0f
                    rawRight = inShortArray[i * 2 + 1] / 32768.0f
                } else {
                    val mono = inShortArray[i] / 32768.0f
                    rawLeft = mono
                    rawRight = mono
                }

                // 1. Software Input Gain
                var procL = rawLeft * currentGainLinear
                var procR = rawRight * currentGainLinear

                // 2. AUDIBLE Noise Removal / Gate Filter
                if (noiseRemoval) {
                    val maxInstantAmp = max(kotlin.math.abs(procL), kotlin.math.abs(procR))
                    gateEnvelope = if (maxInstantAmp > gateEnvelope) {
                        0.7f * gateEnvelope + 0.3f * maxInstantAmp
                    } else {
                        0.95f * gateEnvelope + 0.05f * maxInstantAmp
                    }

                    val gateFactor = if (gateEnvelope < gateThresholdLinear) {
                        val ratio = (gateEnvelope / gateThresholdLinear.coerceAtLeast(1e-5f))
                        (ratio * ratio * ratio).coerceIn(0.0f, 1.0f)
                    } else {
                        1.0f
                    }

                    procL *= gateFactor
                    procR *= gateFactor
                }

                // 3. 3-Band Biquad EQ (Low Shelf 100Hz, Mid Peak 1kHz, High Shelf 8kHz)
                if (!bypassEq) {
                    procL = highShelfFilter.processLeft(midPeakFilter.processLeft(lowShelfFilter.processLeft(procL)))
                    procR = highShelfFilter.processRight(midPeakFilter.processRight(lowShelfFilter.processRight(procR)))
                }

                // 4. Limiter
                if (limiter) {
                    procL = softLimit(procL)
                    procR = softLimit(procR)
                }

                // Channel Muting (Mute left or right)
                if (muteL) procL = 0.0f
                if (muteR) procR = 0.0f

                // Accumulate metering
                val absL = kotlin.math.abs(procL)
                val absR = kotlin.math.abs(procR)
                if (absL > leftPeakRaw) leftPeakRaw = absL
                if (absR > rightPeakRaw) rightPeakRaw = absR
                leftSumSq += (procL * procL).toDouble()
                rightSumSq += (procR * procR).toDouble()

                // Final Output Scaling with Volume Out
                val outL = (procL * outVol).coerceIn(-1.0f, 1.0f)
                val outR = (procR * outVol).coerceIn(-1.0f, 1.0f)

                outShortArray[i * 2] = (outL * 32767.0f).toInt().toShort()
                outShortArray[i * 2 + 1] = (outR * 32767.0f).toInt().toShort()
            }

            val samplesToWrite = framesRead * 2

            // Audible Low-Latency Passthrough (AudioTrack)
            if (isPassthroughEnabled) {
                audioTrack?.write(outShortArray, 0, samplesToWrite)
            }

            // M4A Compressed Recording (AAC-LC 44.1kHz, 128kbps)
            if (isRecording) {
                m4aWriter?.writeSamples(outShortArray, samplesToWrite)
            }

            // Always updating live VU meters and rolling waveform
            val now = System.currentTimeMillis()
            if (now - lastMeterUpdateTime >= 25L) {
                lastMeterUpdateTime = now
                processMeteringAndWaveform(
                    frames = framesRead,
                    leftPeak = leftPeakRaw,
                    rightPeak = rightPeakRaw,
                    leftSumSq = leftSumSq,
                    rightSumSq = rightSumSq,
                    now = now
                )

                if (isRecording) {
                    val recMs = m4aWriter?.recordedDurationMs ?: 0L
                    _engineStatus.value = _engineStatus.value.copy(currentRecordingTimeMs = recMs)
                }
            }
        }
    }

    /**
     * Precomputes or retrieves full-length waveform for the audio file (.m4a, .mp3, .wav).
     */
     fun extractFullWaveform(file: File, numPoints: Int = 120): List<Float> {
        if (!file.exists()) return List(numPoints) { 0.05f }
        val active = activeDecodedAudio
        if (active != null && playbackFile == file && active.waveform.size == numPoints) {
            return active.waveform
        }
        return try {
            val decoded = AudioDecoder.decodeAudio(file, context.cacheDir, SAMPLE_RATE, numPoints)
            if (decoded != null) {
                val wf = decoded.waveform
                decoded.release()
                wf
            } else {
                List(numPoints) { 0.05f }
            }
        } catch (_: Throwable) {
            List(numPoints) { 0.05f }
        }
    }

    /**
     * Plays back a recorded audio file (.m4a, .mp3, .wav) through AudioTrack with variable speed and reverse support.
     */
    fun startPlayback(recordingItem: RecordingItem, startPositionMs: Long = 0L) {
        stopPlayback()
        initAudioTrack()

        val decoded = AudioDecoder.decodeAudio(recordingItem.file, context.cacheDir, SAMPLE_RATE)
        if (decoded == null) {
            Log.e(TAG, "Failed to decode audio file for playback: ${recordingItem.file.name}")
            stopPlayback()
            return
        }

        activeDecodedAudio = decoded
        playbackFile = recordingItem.file
        currentPlaybackMs = startPositionMs
        totalPlaybackDurationMs = if (recordingItem.durationMs > 0L) recordingItem.durationMs else decoded.durationMs
        isPlayingFile = true

        currentFileWaveform = decoded.waveform

        _engineStatus.value = _engineStatus.value.copy(
            mode = AudioMode.FILE_PLAYBACK,
            activePlaybackFile = recordingItem,
            isPlayingFile = true,
            playbackPositionMs = startPositionMs,
            playbackDurationMs = totalPlaybackDurationMs,
            playbackSpeed = playbackSpeed,
            playbackFileWaveform = currentFileWaveform,
            isLooping = isLooping
        )

        playbackThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            playbackAudioLoop(decoded, startPositionMs)
        }, "RecodaPlaybackThread").apply {
            start()
        }
    }

    fun pausePlayback() {
        isPlayingFile = false
        playbackThread?.let {
            try {
                it.join(500)
            } catch (_: InterruptedException) {}
        }
        playbackThread = null
        resetPlaybackMetering()
        _engineStatus.value = _engineStatus.value.copy(isPlayingFile = false)
    }

    fun stopPlayback() {
        isPlayingFile = false
        playbackThread?.let {
            try {
                it.join(500)
            } catch (_: InterruptedException) {}
        }
        playbackThread = null
        playbackSpeed = 1.0f
        activeDecodedAudio?.release()
        activeDecodedAudio = null
        resetPlaybackMetering()
        _engineStatus.value = _engineStatus.value.copy(
            mode = AudioMode.LIVE_INPUT,
            isPlayingFile = false,
            playbackPositionMs = 0L,
            playbackSpeed = 1.0f
        )
    }

    fun seekPlayback(positionMs: Long) {
        val clamped = positionMs.coerceIn(0L, totalPlaybackDurationMs)
        playbackSeekRequestedMs = clamped
        currentPlaybackMs = clamped
        _engineStatus.value = _engineStatus.value.copy(playbackPositionMs = clamped)
    }

    fun toggleLoop(): Boolean {
        isLooping = !isLooping
        _engineStatus.value = _engineStatus.value.copy(isLooping = isLooping)
        return isLooping
    }

    fun setLooping(enabled: Boolean) {
        isLooping = enabled
        _engineStatus.value = _engineStatus.value.copy(isLooping = isLooping)
    }

    /**
     * Reverse speed state cycle:
     * Press 1: -25%
     * Press 2: -50%
     * Press 3: -100%
     * Press 4: -200%
     * Press 5: +100% (normal)
     */
    fun cycleReverseSpeed() {
        playbackSpeed = when (playbackSpeed) {
            -0.25f -> -0.50f
            -0.50f -> -1.00f
            -1.00f -> -2.00f
            -2.00f -> 1.00f
            else -> -0.25f
        }
        _engineStatus.value = _engineStatus.value.copy(playbackSpeed = playbackSpeed)
    }

    /**
     * Forward speed state cycle:
     * Press 1: +200%
     * Press 2: +25%
     * Press 3: +50%
     * Press 4: +100% (normal)
     */
    fun cycleForwardSpeed() {
        playbackSpeed = when (playbackSpeed) {
            2.00f -> 0.25f
            0.25f -> 0.50f
            0.50f -> 1.00f
            else -> 2.00f
        }
        _engineStatus.value = _engineStatus.value.copy(playbackSpeed = playbackSpeed)
    }

    private fun playbackAudioLoop(decodedAudio: DecodedAudio, startPositionMs: Long) {
        val totalFrames = decodedAudio.totalFrames.toDouble()
        if (totalFrames <= 0.0) {
            stopPlayback()
            return
        }

        try {
            var currentFrameIndex: Double = (startPositionMs * SAMPLE_RATE.toDouble()) / 1000.0

            val frameCount = 256
            val outShortArray = ShortArray(frameCount * 2)

            var lastMeterUpdateTime = 0L

            while (isPlayingFile && isRunning) {
                val seekTarget = playbackSeekRequestedMs
                if (seekTarget != null) {
                    currentFrameIndex = (seekTarget * SAMPLE_RATE.toDouble()) / 1000.0
                    playbackSeekRequestedMs = null
                }

                val speed = playbackSpeed

                // Bounds check based on playback direction
                if (speed < 0 && currentFrameIndex <= 0.0) {
                    if (isLooping) {
                        currentFrameIndex = (totalFrames - 1.0).coerceAtLeast(0.0)
                    } else {
                        break // Reached beginning in reverse
                    }
                }
                if (speed > 0 && currentFrameIndex >= totalFrames) {
                    if (isLooping) {
                        currentFrameIndex = 0.0
                    } else {
                        break // Reached end of file
                    }
                }

                val targetGainLinear = 10.0f.pow(targetGainDb / 20.0f)
                val gainStep = (targetGainLinear - currentGainLinear) / frameCount
                val gateThresholdLinear = 10.0f.pow(noiseRemovalThresholdDb / 20.0f)

                var leftPeakRaw = 0.0f
                var rightPeakRaw = 0.0f
                var leftSumSq = 0.0
                var rightSumSq = 0.0

                val bypassEq = isEqBypassed
                val limiter = isLimiterActive
                val outVol = volumeOut
                val noiseRemoval = isNoiseRemovalActive
                val muteL = isLeftMuted
                val muteR = isRightMuted

                for (i in 0 until frameCount) {
                    val frameClamped = currentFrameIndex.toInt().coerceIn(0, (totalFrames - 1.0).toInt().coerceAtLeast(0))
                    val rawLeft = decodedAudio.getLeftSample(frameClamped) / 32768.0f
                    val rawRight = decodedAudio.getRightSample(frameClamped) / 32768.0f

                    currentGainLinear += gainStep
                    var procL = rawLeft * currentGainLinear
                    var procR = rawRight * currentGainLinear

                    if (noiseRemoval) {
                        val maxInstantAmp = max(kotlin.math.abs(procL), kotlin.math.abs(procR))
                        gateEnvelope = if (maxInstantAmp > gateEnvelope) {
                            0.7f * gateEnvelope + 0.3f * maxInstantAmp
                        } else {
                            0.95f * gateEnvelope + 0.05f * maxInstantAmp
                        }

                        val gateFactor = if (gateEnvelope < gateThresholdLinear) {
                            val ratio = (gateEnvelope / gateThresholdLinear.coerceAtLeast(1e-5f))
                            (ratio * ratio * ratio).coerceIn(0.0f, 1.0f)
                        } else 1.0f

                        procL *= gateFactor
                        procR *= gateFactor
                    }

                    if (!bypassEq) {
                        procL = highShelfFilter.processLeft(midPeakFilter.processLeft(lowShelfFilter.processLeft(procL)))
                        procR = highShelfFilter.processRight(midPeakFilter.processRight(lowShelfFilter.processRight(procR)))
                    }

                    if (limiter) {
                        procL = softLimit(procL)
                        procR = softLimit(procR)
                    }

                    if (muteL) procL = 0.0f
                    if (muteR) procR = 0.0f

                    val absL = kotlin.math.abs(procL)
                    val absR = kotlin.math.abs(procR)
                    if (absL > leftPeakRaw) leftPeakRaw = absL
                    if (absR > rightPeakRaw) rightPeakRaw = absR
                    leftSumSq += (procL * procL).toDouble()
                    rightSumSq += (procR * procR).toDouble()

                    val outL = (procL * outVol).coerceIn(-1.0f, 1.0f)
                    val outR = (procR * outVol).coerceIn(-1.0f, 1.0f)

                    outShortArray[i * 2] = (outL * 32767.0f).toInt().toShort()
                    outShortArray[i * 2 + 1] = (outR * 32767.0f).toInt().toShort()

                    currentFrameIndex += speed
                    if (currentFrameIndex < 0.0 || currentFrameIndex >= totalFrames) {
                        if (isLooping) {
                            if (currentFrameIndex < 0.0) {
                                currentFrameIndex = (totalFrames - 1.0).coerceAtLeast(0.0)
                            } else if (currentFrameIndex >= totalFrames) {
                                currentFrameIndex = 0.0
                            }
                        } else {
                            break
                        }
                    }
                }

                audioTrack?.write(outShortArray, 0, frameCount * 2)
                currentPlaybackMs = ((currentFrameIndex * 1000.0) / SAMPLE_RATE.toDouble()).toLong().coerceIn(0L, totalPlaybackDurationMs)

                val now = System.currentTimeMillis()
                if (now - lastMeterUpdateTime >= 25L) {
                    lastMeterUpdateTime = now
                    processPlaybackMetering(
                        frames = frameCount,
                        leftPeak = leftPeakRaw,
                        rightPeak = rightPeakRaw,
                        leftSumSq = leftSumSq,
                        rightSumSq = rightSumSq,
                        now = now
                    )
                    _engineStatus.value = _engineStatus.value.copy(
                        playbackPositionMs = currentPlaybackMs
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Playback loop exception", e)
        } finally {
            isPlayingFile = false
            resetPlaybackMetering()
            _engineStatus.value = _engineStatus.value.copy(isPlayingFile = false)
        }
    }

    private fun softLimit(sample: Float): Float {
        val absX = kotlin.math.abs(sample)
        if (absX <= 0.8f) return sample
        val sign = if (sample >= 0f) 1.0f else -1.0f
        val excess = absX - 0.8f
        val compressed = 0.8f + 0.2f * kotlin.math.tanh(excess * 3.0f).toFloat()
        return sign * compressed.coerceIn(0f, 1.0f)
    }

    private fun processMeteringAndWaveform(
        frames: Int,
        leftPeak: Float,
        rightPeak: Float,
        leftSumSq: Double,
        rightSumSq: Double,
        now: Long
    ) {
        val leftRms = sqrt(leftSumSq / frames).toFloat()
        val rightRms = sqrt(rightSumSq / frames).toFloat()

        val leftPeakDb = if (isLeftMuted) MIN_DB else linearToDb(leftPeak)
        val rightPeakDb = if (isRightMuted) MIN_DB else linearToDb(rightPeak)
        val leftRmsDb = if (isLeftMuted) MIN_DB else linearToDb(leftRms)
        val rightRmsDb = if (isRightMuted) MIN_DB else linearToDb(rightRms)

        val leftClipping = !isLeftMuted && leftPeak >= CLIP_THRESHOLD_LINEAR
        val rightClipping = !isRightMuted && rightPeak >= CLIP_THRESHOLD_LINEAR
        if (leftClipping) leftClipHoldTime = now
        if (rightClipping) rightClipHoldTime = now

        val isLeftClipActive = (now - leftClipHoldTime) < 1200L
        val isRightClipActive = (now - rightClipHoldTime) < 1200L

        if (leftPeakDb >= leftPeakHoldDb || now - leftPeakHoldTime > 1200L) {
            leftPeakHoldDb = leftPeakDb
            leftPeakHoldTime = now
        } else {
            leftPeakHoldDb = max(MIN_DB, leftPeakHoldDb - 0.8f)
        }

        if (rightPeakDb >= rightPeakHoldDb || now - rightPeakHoldTime > 1200L) {
            rightPeakHoldDb = rightPeakDb
            rightPeakHoldTime = now
        } else {
            rightPeakHoldDb = max(MIN_DB, rightPeakHoldDb - 0.8f)
        }

        _vuMeterState.value = VuMeterState(
            left = VuMeterChannelData(
                peakDb = leftPeakDb,
                rmsDb = leftRmsDb,
                peakHoldDb = leftPeakHoldDb,
                isClipping = isLeftClipActive,
                isMuted = isLeftMuted
            ),
            right = VuMeterChannelData(
                peakDb = rightPeakDb,
                rmsDb = rightRmsDb,
                peakHoldDb = rightPeakHoldDb,
                isClipping = isRightClipActive,
                isMuted = isRightMuted
            )
        )

        val maxPeak = max(if (isLeftMuted) 0f else leftPeak, if (isRightMuted) 0f else rightPeak).coerceIn(0.01f, 1.0f)
        System.arraycopy(waveformBuffer, 1, waveformBuffer, 0, WAVEFORM_HISTORY_SIZE - 1)
        waveformBuffer[WAVEFORM_HISTORY_SIZE - 1] = maxPeak
        _waveformState.value = waveformBuffer.toList()
    }

    private fun processPlaybackMetering(
        frames: Int,
        leftPeak: Float,
        rightPeak: Float,
        leftSumSq: Double,
        rightSumSq: Double,
        now: Long
    ) {
        val leftRms = sqrt(leftSumSq / frames).toFloat()
        val rightRms = sqrt(rightSumSq / frames).toFloat()

        val leftPeakDb = if (isLeftMuted) MIN_DB else linearToDb(leftPeak)
        val rightPeakDb = if (isRightMuted) MIN_DB else linearToDb(rightPeak)
        val leftRmsDb = if (isLeftMuted) MIN_DB else linearToDb(leftRms)
        val rightRmsDb = if (isRightMuted) MIN_DB else linearToDb(rightRms)

        val leftClipping = !isLeftMuted && leftPeak >= CLIP_THRESHOLD_LINEAR
        val rightClipping = !isRightMuted && rightPeak >= CLIP_THRESHOLD_LINEAR

        if (leftPeakDb >= playbackLeftPeakHoldDb || now - playbackLeftPeakHoldTime > 1200L) {
            playbackLeftPeakHoldDb = leftPeakDb
            playbackLeftPeakHoldTime = now
        } else {
            playbackLeftPeakHoldDb = max(MIN_DB, playbackLeftPeakHoldDb - 0.8f)
        }

        if (rightPeakDb >= playbackRightPeakHoldDb || now - playbackRightPeakHoldTime > 1200L) {
            playbackRightPeakHoldDb = rightPeakDb
            playbackRightPeakHoldTime = now
        } else {
            playbackRightPeakHoldDb = max(MIN_DB, playbackRightPeakHoldDb - 0.8f)
        }

        _playbackVuMeterState.value = VuMeterState(
            left = VuMeterChannelData(
                peakDb = leftPeakDb,
                rmsDb = leftRmsDb,
                peakHoldDb = playbackLeftPeakHoldDb,
                isClipping = leftClipping,
                isMuted = isLeftMuted
            ),
            right = VuMeterChannelData(
                peakDb = rightPeakDb,
                rmsDb = rightRmsDb,
                peakHoldDb = playbackRightPeakHoldDb,
                isClipping = rightClipping,
                isMuted = isRightMuted
            )
        )
    }

    fun resetPlaybackMetering() {
        playbackLeftPeakHoldDb = MIN_DB
        playbackRightPeakHoldDb = MIN_DB
        _playbackVuMeterState.value = VuMeterState(
            left = VuMeterChannelData(
                peakDb = MIN_DB,
                rmsDb = MIN_DB,
                peakHoldDb = MIN_DB,
                isClipping = false,
                isMuted = isLeftMuted
            ),
            right = VuMeterChannelData(
                peakDb = MIN_DB,
                rmsDb = MIN_DB,
                peakHoldDb = MIN_DB,
                isClipping = false,
                isMuted = isRightMuted
            )
        )
    }

    private fun linearToDb(linear: Float): Float {
        if (linear <= 0.001f) return MIN_DB
        val db = 20.0f * log10(linear)
        return db.coerceIn(MIN_DB, 6.0f)
    }

    // Setters with Storage Persistence
    fun setInputGainDb(gainDb: Float) {
        val clamped = gainDb.coerceIn(-30.0f, 30.0f)
        targetGainDb = clamped
        settings.inputGainDb = clamped
        updateStatus()
    }

    fun toggleLeftMute() {
        isLeftMuted = !isLeftMuted
        settings.isLeftMuted = isLeftMuted
        _vuMeterState.value = _vuMeterState.value.copy(
            left = _vuMeterState.value.left.copy(isMuted = isLeftMuted)
        )
        _playbackVuMeterState.value = _playbackVuMeterState.value.copy(
            left = _playbackVuMeterState.value.left.copy(isMuted = isLeftMuted)
        )
        updateStatus()
    }

    fun toggleRightMute() {
        isRightMuted = !isRightMuted
        settings.isRightMuted = isRightMuted
        _vuMeterState.value = _vuMeterState.value.copy(
            right = _vuMeterState.value.right.copy(isMuted = isRightMuted)
        )
        _playbackVuMeterState.value = _playbackVuMeterState.value.copy(
            right = _playbackVuMeterState.value.right.copy(isMuted = isRightMuted)
        )
        updateStatus()
    }

    fun setLimiterActive(active: Boolean) {
        isLimiterActive = active
        settings.isLimiterActive = active
        updateStatus()
    }

    fun setNoiseRemovalActive(active: Boolean) {
        isNoiseRemovalActive = active
        settings.isNoiseRemovalActive = active
        updateStatus()
    }

    fun setNoiseRemovalThresholdDb(db: Float) {
        val clamped = db.coerceIn(-60.0f, 0.0f)
        noiseRemovalThresholdDb = clamped
        settings.noiseRemovalThresholdDb = clamped
        updateStatus()
    }

    fun setPassthrough(enabled: Boolean) {
        isPassthroughEnabled = enabled
        settings.isPassthroughActive = enabled
        if (!enabled) {
            try {
                audioTrack?.flush()
            } catch (_: Exception) {}
        }
        updateStatus()
    }

    fun setVolumeOut(vol: Float) {
        val clamped = vol.coerceIn(0.0f, 1.0f)
        volumeOut = clamped
        settings.volumeOut = clamped
        updateStatus()
    }

    fun updateEq(eq: EqSettings) {
        isEqBypassed = eq.isBypassed
        val lowClamped = eq.lowShelfGainDb.coerceIn(-30.0f, 30.0f)
        val midClamped = eq.midPeakGainDb.coerceIn(-30.0f, 30.0f)
        val highClamped = eq.highShelfGainDb.coerceIn(-30.0f, 30.0f)

        settings.lowShelfGainDb = lowClamped
        settings.midPeakGainDb = midClamped
        settings.highShelfGainDb = highClamped
        settings.isEqBypassed = eq.isBypassed

        lowShelfFilter.updateCoefficients(
            type = BiquadFilter.Type.LOW_SHELF,
            sampleRate = SAMPLE_RATE.toFloat(),
            frequency = LOW_SHELF_FREQ,
            gainDb = lowClamped,
            q = 0.707f
        )
        midPeakFilter.updateCoefficients(
            type = BiquadFilter.Type.PEAKING,
            sampleRate = SAMPLE_RATE.toFloat(),
            frequency = MID_PEAK_FREQ,
            gainDb = midClamped,
            q = 1.0f
        )
        highShelfFilter.updateCoefficients(
            type = BiquadFilter.Type.HIGH_SHELF,
            sampleRate = SAMPLE_RATE.toFloat(),
            frequency = HIGH_SHELF_FREQ,
            gainDb = highClamped,
            q = 0.707f
        )
    }

    @Synchronized
    fun startRecording(): File {
        if (isRecording) {
            return currentRecordingFile ?: throw IllegalStateException("Already recording")
        }

        val recordingsDir = File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(recordingsDir, "rec_$timeStamp.m4a")

        m4aWriter = M4aWriter(
            outputFile = file,
            sampleRate = SAMPLE_RATE,
            numChannels = 2, // Stereo 128kb/s 44100Hz 16-bit
            bitRate = 128000 // 128 kbps (lightest CPU load, native AAC-LC)
        )
        currentRecordingFile = file
        isRecording = true
        updateStatus()
        return file
    }

    @Synchronized
    fun stopRecording(): File? {
        if (!isRecording) return null
        isRecording = false

        val writer = m4aWriter
        m4aWriter = null
        writer?.close()

        val recordedFile = currentRecordingFile
        currentRecordingFile = null
        updateStatus()
        return if (recordedFile != null && recordedFile.exists() && recordedFile.length() > 0) {
            recordedFile
        } else {
            null
        }
    }

    private fun updateStatus() {
        _engineStatus.value = _engineStatus.value.copy(
            isRunning = isRunning,
            isPassthroughActive = isPassthroughEnabled,
            volumeOut = volumeOut,
            isRecording = isRecording,
            sampleRate = SAMPLE_RATE,
            bufferSizeFrames = bufferSizeFrames,
            inputGainDb = targetGainDb,
            isLimiterActive = isLimiterActive,
            isNoiseRemovalActive = isNoiseRemovalActive,
            noiseRemovalThresholdDb = noiseRemovalThresholdDb,
            inputSourceInfo = inputSourceUsed,
            isStereoInput = isStereoInput,
            isLeftMuted = isLeftMuted,
            isRightMuted = isRightMuted,
            playbackSpeed = playbackSpeed,
            playbackFileWaveform = currentFileWaveform,
            isLooping = isLooping,
            errorMessage = null
        )
    }
}
