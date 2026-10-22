package com.example

import com.example.audio.AppTab
import com.example.audio.AudioExporter
import com.example.audio.AudioMode
import com.example.audio.BiquadFilter
import com.example.audio.EngineStatus
import com.example.audio.VuMeterChannelData
import com.example.audio.VuMeterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AudioEngineUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun biquadFilter_identityWhenZeroGain() {
        val filter = BiquadFilter()
        filter.updateCoefficients(
            type = BiquadFilter.Type.PEAKING,
            sampleRate = 48000f,
            frequency = 1000f,
            gainDb = 0.0f
        )

        val input = 0.5f
        val outL = filter.processLeft(input)
        val outR = filter.processRight(input)

        assertEquals(input, outL, 1e-4f)
        assertEquals(input, outR, 1e-4f)
    }

    @Test
    fun biquadFilter_lowShelfBoostIncreasesLowFrequencyGain() {
        val filter = BiquadFilter()
        filter.updateCoefficients(
            type = BiquadFilter.Type.LOW_SHELF,
            sampleRate = 48000f,
            frequency = 100f,
            gainDb = 6.0f
        )

        val magAt50Hz = filter.getMagnitudeAt(50f)
        val magAt10000Hz = filter.getMagnitudeAt(10000f)

        assertTrue("Low frequency response should be boosted", magAt50Hz > 3.0f)
        assertTrue("High frequency response should remain near unity", kotlin.math.abs(magAt10000Hz) < 1.0f)
    }

    @Test
    fun biquadFilter_highShelfBoostIncreasesHighFrequencyGain() {
        val filter = BiquadFilter()
        filter.updateCoefficients(
            type = BiquadFilter.Type.HIGH_SHELF,
            sampleRate = 48000f,
            frequency = 8000f,
            gainDb = 6.0f
        )

        val magAt12000Hz = filter.getMagnitudeAt(12000f)
        val magAt100Hz = filter.getMagnitudeAt(100f)

        assertTrue("High frequency response should be boosted", magAt12000Hz > 3.0f)
        assertTrue("Low frequency response should remain near unity", kotlin.math.abs(magAt100Hz) < 1.0f)
    }

    @Test
    fun engineStatus_defaultSpecifications() {
        val status = EngineStatus()
        assertTrue("Passthrough defaults to ON", status.isPassthroughActive)
        assertEquals(0.5f, status.volumeOut, 0.01f)
        assertEquals(0.0f, status.inputGainDb, 0.01f)
        assertFalse("Limiter defaults to OFF", status.isLimiterActive)
        assertFalse("Noise filter defaults to OFF", status.isNoiseRemovalActive)
        assertEquals(AudioMode.LIVE_INPUT, status.mode)
        assertFalse("Recording is strictly OFF by default", status.isRecording)
        assertTrue("Keep screen awake defaults to ON", status.isKeepScreenAwake)
    }

    @Test
    fun appTabs_enumeration() {
        assertEquals(AppTab.RECORDER, AppTab.valueOf("RECORDER"))
        assertEquals(AppTab.GALLERY, AppTab.valueOf("GALLERY"))
        assertEquals(AppTab.PLAYBACK, AppTab.valueOf("PLAYBACK"))
    }

    @Test
    fun audioExporter_presetsEnumeration() {
        val presets = AudioExporter.ExportPreset.values()
        assertEquals(3, presets.size)

        val original = AudioExporter.ExportPreset.ORIGINAL_M4A
        assertEquals("m4a", original.extension)
        assertEquals(128, original.bitrateKbps)
        assertEquals(2, original.channels)
        assertEquals(44100, original.sampleRate)

        val mp3Stereo = AudioExporter.ExportPreset.MP3_STEREO_128K
        assertEquals("mp3", mp3Stereo.extension)
        assertEquals(128, mp3Stereo.bitrateKbps)
        assertEquals(2, mp3Stereo.channels)
        assertEquals(44100, mp3Stereo.sampleRate)

        val mp3Mono = AudioExporter.ExportPreset.MP3_MONO_64K
        assertEquals("mp3", mp3Mono.extension)
        assertEquals(64, mp3Mono.bitrateKbps)
        assertEquals(1, mp3Mono.channels)
        assertEquals(44100, mp3Mono.sampleRate)
    }

    @Test
    fun audioExporter_exportOriginalM4aCopiesFile() {
        val srcFile = tempFolder.newFile("test_source.m4a")
        srcFile.writeText("mock_m4a_data")

        val outDir = tempFolder.newFolder("exports")
        val exported = AudioExporter.export(
            sourceFile = srcFile,
            preset = AudioExporter.ExportPreset.ORIGINAL_M4A,
            targetDir = outDir
        )

        assertNotNull(exported)
        assertTrue(exported!!.exists())
        assertTrue(exported.name.endsWith(".m4a"))
        assertEquals("mock_m4a_data", exported.readText())
    }

    @Test
    fun engineStatus_muteAndSpeedDefaults() {
        val status = EngineStatus()
        assertFalse("Left channel should not be muted by default", status.isLeftMuted)
        assertFalse("Right channel should not be muted by default", status.isRightMuted)
        assertEquals(1.0f, status.playbackSpeed, 0.001f)
    }

    @Test
    fun vuMeterState_channelMuting() {
        val meterState = VuMeterState(
            left = VuMeterChannelData(isMuted = true),
            right = VuMeterChannelData(isMuted = false)
        )
        assertTrue(meterState.left.isMuted)
        assertFalse(meterState.right.isMuted)
    }

    @Test
    fun playbackVuMeter_silentWhenIdle() {
        val meterState = VuMeterState()
        assertEquals(-60f, meterState.left.peakDb, 0.01f)
        assertEquals(-60f, meterState.right.peakDb, 0.01f)
    }

    @Test
    fun engineStatus_monitoringAndHoldDefaults() {
        val status = EngineStatus()
        assertTrue("Monitoring defaults to ON", status.isMonitoring)
        assertFalse("Recording paused defaults to false", status.isRecordingPaused)
        assertFalse("Monitoring paused defaults to false", status.isMonitoringPaused)
        assertFalse("Monitoring peek defaults to false", status.isMonitoringPeek)
        assertFalse("Header button held defaults to false", status.isHeaderButtonHeld)
        assertFalse("Loading playback defaults to false", status.isLoadingPlayback)
    }
}
