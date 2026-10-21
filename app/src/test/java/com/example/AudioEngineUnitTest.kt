package com.example

import com.example.audio.AppTab
import com.example.audio.AudioExporter
import com.example.audio.AudioMode
import com.example.audio.BiquadFilter
import com.example.audio.EngineStatus
import com.example.audio.VuMeterChannelData
import com.example.audio.VuMeterState
import com.example.audio.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    fun wavWriter_headerStructureIsValid() {
        val audioByteCount = 96000L // 0.5s of 48kHz 16-bit stereo
        val header = WavWriter.createWavHeader(
            totalAudioLen = audioByteCount,
            sampleRate = 48000,
            channels = 2,
            bitsPerSample = 16
        )

        assertEquals(44, header.size)

        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // "RIFF"
        val riff = byteArrayOf(buf.get(), buf.get(), buf.get(), buf.get())
        assertEquals("RIFF", String(riff))

        val chunkSize = buf.getInt()
        assertEquals((audioByteCount + 36).toInt(), chunkSize)

        // "WAVE"
        val wave = byteArrayOf(buf.get(), buf.get(), buf.get(), buf.get())
        assertEquals("WAVE", String(wave))

        // "fmt "
        val fmt = byteArrayOf(buf.get(), buf.get(), buf.get(), buf.get())
        assertEquals("fmt ", String(fmt))

        val subchunk1Size = buf.getInt()
        assertEquals(16, subchunk1Size)

        val audioFormat = buf.getShort()
        assertEquals(1.toShort(), audioFormat) // PCM

        val channels = buf.getShort()
        assertEquals(2.toShort(), channels)

        val sampleRate = buf.getInt()
        assertEquals(48000, sampleRate)

        val byteRate = buf.getInt()
        assertEquals(48000 * 2 * 2, byteRate) // 192000

        val blockAlign = buf.getShort()
        assertEquals(4.toShort(), blockAlign)

        val bitsPerSample = buf.getShort()
        assertEquals(16.toShort(), bitsPerSample)

        // "data"
        val data = byteArrayOf(buf.get(), buf.get(), buf.get(), buf.get())
        assertEquals("data", String(data))

        val dataSize = buf.getInt()
        assertEquals(audioByteCount.toInt(), dataSize)
    }

    @Test
    fun engineStatus_defaultSpecifications() {
        val status = EngineStatus()
        assertTrue("Passthrough defaults to ON", status.isPassthroughActive)
        assertEquals(0.5f, status.volumeOut, 0.01f)
        assertEquals(0.0f, status.inputGainDb, 0.01f)
        assertFalse("Limiter defaults to OFF", status.isLimiterActive)
        assertFalse("Noise filter defaults to OFF", status.isNoiseRemovalActive)
        assertEquals(0.0f, status.noiseRemovalThresholdDb, 0.01f)
        assertEquals(AudioMode.LIVE_INPUT, status.mode)
        assertFalse("Recording is strictly OFF by default", status.isRecording)
    }

    @Test
    fun appTabs_enumeration() {
        assertEquals(AppTab.RECORDER, AppTab.valueOf("RECORDER"))
        assertEquals(AppTab.GALLERY, AppTab.valueOf("GALLERY"))
        assertEquals(AppTab.PLAYBACK, AppTab.valueOf("PLAYBACK"))
    }

    @Test
    fun audioExporter_presetsAndExportWav() {
        assertEquals(4, AudioExporter.QualityPreset.values().size)
        assertEquals(2, AudioExporter.ExportFormat.values().size)

        // Create a dummy 48kHz stereo WAV file
        val srcFile = tempFolder.newFile("test_source.wav")
        val sampleCount = 4800 // 0.1s
        val pcmData = ByteArray(sampleCount * 4) // stereo 16-bit
        val header = WavWriter.createWavHeader(pcmData.size.toLong(), 48000, 2, 16)
        srcFile.writeBytes(header + pcmData)

        val outDir = tempFolder.newFolder("exports")
        val exported = AudioExporter.export(
            sourceFile = srcFile,
            format = AudioExporter.ExportFormat.WAV,
            preset = AudioExporter.QualityPreset.FAST_SHARING,
            targetDir = outDir
        )

        assertNotNull(exported)
        assertTrue(exported!!.exists())
        assertTrue(exported.length() > 44)
    }

    @Test
    fun audioExporter_exportMp3ProducesFile() {
        val srcFile = tempFolder.newFile("test_source_mp3.wav")
        val sampleCount = 4800
        val pcmData = ByteArray(sampleCount * 4)
        val header = WavWriter.createWavHeader(pcmData.size.toLong(), 48000, 2, 16)
        srcFile.writeBytes(header + pcmData)

        val outDir = tempFolder.newFolder("mp3_exports")
        val exported = AudioExporter.export(
            sourceFile = srcFile,
            format = AudioExporter.ExportFormat.MP3,
            preset = AudioExporter.QualityPreset.NORMAL_AUDIO,
            targetDir = outDir
        )

        assertNotNull(exported)
        assertTrue(exported!!.exists())
        assertTrue(exported.name.endsWith(".mp3"))
        assertTrue(exported.length() > 0)
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
}
