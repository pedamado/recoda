package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AppTab
import com.example.audio.AudioEngine
import com.example.audio.AudioExporter
import com.example.audio.EngineStatus
import com.example.audio.EqSettings
import com.example.audio.RecordingItem
import com.example.audio.VuMeterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AudioMonitorViewModel(application: Application) : AndroidViewModel(application) {

    val engine = AudioEngine(application.applicationContext)

    val engineStatus: StateFlow<EngineStatus> = engine.engineStatus
    val vuMeterState: StateFlow<VuMeterState> = engine.vuMeterState
    val playbackVuMeterState: StateFlow<VuMeterState> = engine.playbackVuMeterState
    val waveformState: StateFlow<List<Float>> = engine.waveformState

    private val _currentTab = MutableStateFlow(AppTab.RECORDER)
    val currentTab: StateFlow<AppTab> = _currentTab.asStateFlow()

    private val _eqSettings = MutableStateFlow(
        EqSettings(
            lowShelfGainDb = engine.settings.lowShelfGainDb.coerceIn(-30.0f, 30.0f),
            midPeakGainDb = engine.settings.midPeakGainDb.coerceIn(-30.0f, 30.0f),
            highShelfGainDb = engine.settings.highShelfGainDb.coerceIn(-30.0f, 30.0f),
            isBypassed = engine.settings.isEqBypassed
        )
    )
    val eqSettings: StateFlow<EqSettings> = _eqSettings.asStateFlow()

    private val _recordings = MutableStateFlow<List<RecordingItem>>(emptyList())
    val recordings: StateFlow<List<RecordingItem>> = _recordings.asStateFlow()

    private val _hasPermission = MutableStateFlow(false)
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private val _userMessage = MutableSharedFlow<String>()
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    init {
        loadRecordings()
    }

    fun setTab(tab: AppTab) {
        _currentTab.value = tab
    }

    fun onPermissionResult(granted: Boolean) {
        _hasPermission.value = granted
        if (granted) {
            engine.start()
        } else {
            engine.stop()
            viewModelScope.launch {
                _userMessage.emit("record_audio permission needed")
            }
        }
    }

    fun toggleEngine() {
        if (engineStatus.value.isRunning) {
            engine.stop()
        } else {
            engine.start()
        }
    }

    fun toggleLeftMute() {
        engine.toggleLeftMute()
    }

    fun toggleRightMute() {
        engine.toggleRightMute()
    }

    fun setGain(gainDb: Float) {
        val clamped = gainDb.coerceIn(-30.0f, 30.0f)
        engine.setInputGainDb(clamped)
    }

    fun toggleLimiter(active: Boolean) {
        engine.setLimiterActive(active)
    }

    fun setPassthrough(enabled: Boolean) {
        engine.setPassthrough(enabled)
    }

    fun setVolumeOut(vol: Float) {
        engine.setVolumeOut(vol)
    }

    fun toggleNoiseRemoval(active: Boolean) {
        engine.setNoiseRemovalActive(active)
    }

    fun setNoiseRemovalThreshold(db: Float) {
        engine.setNoiseRemovalThresholdDb(db)
    }

    // EQ Controls for Playback (-30 dB to +30 dB)
    fun setLowShelfGain(gainDb: Float) {
        val newSettings = _eqSettings.value.copy(lowShelfGainDb = gainDb.coerceIn(-30.0f, 30.0f))
        _eqSettings.value = newSettings
        engine.updateEq(newSettings)
    }

    fun setMidPeakGain(gainDb: Float) {
        val newSettings = _eqSettings.value.copy(midPeakGainDb = gainDb.coerceIn(-30.0f, 30.0f))
        _eqSettings.value = newSettings
        engine.updateEq(newSettings)
    }

    fun setHighShelfGain(gainDb: Float) {
        val newSettings = _eqSettings.value.copy(highShelfGainDb = gainDb.coerceIn(-30.0f, 30.0f))
        _eqSettings.value = newSettings
        engine.updateEq(newSettings)
    }

    fun setEqBypassed(bypassed: Boolean) {
        val newSettings = _eqSettings.value.copy(isBypassed = bypassed)
        _eqSettings.value = newSettings
        engine.updateEq(newSettings)
    }

    fun resetEq() {
        val flat = EqSettings(lowShelfGainDb = 0f, midPeakGainDb = 0f, highShelfGainDb = 0f, isBypassed = false)
        _eqSettings.value = flat
        engine.updateEq(flat)
    }

    // Recording Controls (Auto-recording is OFF by default)
    fun toggleRecording() {
        if (!_hasPermission.value) {
            viewModelScope.launch {
                _userMessage.emit("mic permission required")
            }
            return
        }

        if (engineStatus.value.isRecording) {
            val file = engine.stopRecording()
            if (file != null) {
                viewModelScope.launch {
                    _userMessage.emit("saved: ${file.name}")
                    loadRecordings()
                }
            }
        } else {
            if (!engineStatus.value.isRunning) {
                engine.start()
            }
            try {
                val file = engine.startRecording()
                viewModelScope.launch {
                    _userMessage.emit("recording: ${file.name}")
                }
            } catch (e: Exception) {
                viewModelScope.launch {
                    _userMessage.emit("error: ${e.message}")
                }
            }
        }
    }

    // Playback Controls
    fun playFile(item: RecordingItem) {
        _currentTab.value = AppTab.PLAYBACK
        if (engineStatus.value.isPlayingFile && engineStatus.value.activePlaybackFile?.id == item.id) {
            engine.pausePlayback()
        } else {
            engine.startPlayback(item)
        }
    }

    fun resumePlayback() {
        val active = engineStatus.value.activePlaybackFile
        if (active != null) {
            engine.startPlayback(active, engineStatus.value.playbackPositionMs)
        } else if (_recordings.value.isNotEmpty()) {
            engine.startPlayback(_recordings.value.first())
        }
    }

    fun pausePlayback() {
        engine.pausePlayback()
    }

    fun stopPlayback() {
        engine.stopPlayback()
    }

    fun seekPlayback(posMs: Long) {
        engine.seekPlayback(posMs)
    }

    fun cycleReverseSpeed() {
        engine.cycleReverseSpeed()
    }

    fun cycleForwardSpeed() {
        engine.cycleForwardSpeed()
    }

    fun toggleLoopPlayback() {
        engine.toggleLoop()
    }

    fun loadRecordings() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(getApplication<Application>().getExternalFilesDir(null), "recordings")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            // Only load files from the recordings folder (exports are in a separate exports folder!)
            val files = dir.listFiles { f ->
                val ext = f.extension.lowercase()
                ext == "wav" || ext == "mp3"
            } ?: emptyArray()

            val items = files.map { file ->
                val dataBytes = (file.length() - 44).coerceAtLeast(0L)
                val durationMs = (dataBytes * 1000L) / (48000L * 2 * 2)
                RecordingItem(
                    id = file.absolutePath,
                    name = file.name,
                    file = file,
                    durationMs = durationMs,
                    sizeBytes = file.length(),
                    timestamp = file.lastModified()
                )
            }.sortedByDescending { it.timestamp }

            withContext(Dispatchers.Main) {
                _recordings.value = items
            }
        }
    }

    fun renameRecording(item: RecordingItem, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return

        val extension = item.file.extension
        val finalName = if (trimmed.endsWith(".$extension", ignoreCase = true)) trimmed else "$trimmed.$extension"

        viewModelScope.launch(Dispatchers.IO) {
            val newFile = File(item.file.parentFile, finalName)
            val success = item.file.renameTo(newFile)
            if (success) {
                loadRecordings()
                withContext(Dispatchers.Main) {
                    _userMessage.emit("renamed to $finalName")
                }
            } else {
                withContext(Dispatchers.Main) {
                    _userMessage.emit("rename failed")
                }
            }
        }
    }

    fun deleteRecording(item: RecordingItem) {
        if (engineStatus.value.activePlaybackFile?.id == item.id) {
            engine.stopPlayback()
        }
        viewModelScope.launch(Dispatchers.IO) {
            item.file.delete()
            loadRecordings()
            withContext(Dispatchers.Main) {
                _userMessage.emit("deleted ${item.name}")
            }
        }
    }

    fun shareRecording(context: Context, item: RecordingItem) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                item.file
            )
            val mimeType = if (item.file.extension.equals("mp3", ignoreCase = true)) "audio/mpeg" else "audio/wav"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "share audio"))
        } catch (e: Exception) {
            viewModelScope.launch {
                _userMessage.emit("share error: ${e.message}")
            }
        }
    }

    fun exportRecording(
        item: RecordingItem,
        format: AudioExporter.ExportFormat,
        preset: AudioExporter.QualityPreset,
        context: Context
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // Save to dedicated exports/ folder so it never pollutes the gallery!
            val targetDir = File(getApplication<Application>().getExternalFilesDir(null), "exports")
            val exported = AudioExporter.export(item.file, format, preset, targetDir)
            if (exported != null) {
                withContext(Dispatchers.Main) {
                    _userMessage.emit("exported: ${exported.name}")
                    shareRecording(
                        context,
                        RecordingItem(
                            id = exported.absolutePath,
                            name = exported.name,
                            file = exported,
                            durationMs = item.durationMs,
                            sizeBytes = exported.length(),
                            timestamp = exported.lastModified()
                        )
                    )
                }
            } else {
                withContext(Dispatchers.Main) {
                    _userMessage.emit("export failed")
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        engine.stop()
    }
}
