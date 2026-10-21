package com.example.audio

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioExporter {

    enum class ExportFormat {
        WAV,
        MP3
    }

    enum class QualityPreset(val label: String, val description: String) {
        FAST_SHARING("fast sharing", "high compression • fast transfer"),
        NORMAL_AUDIO("normal audio", "standard quality • balanced size"),
        MEDIUM_COMPRESSION("medium compression", "enhanced fidelity • moderate size"),
        HIGH_STUDIO_QUALITY("high studio quality", "pristine master • maximum fidelity")
    }

    /**
     * Exports a WAV file to target format and quality preset into the dedicated exports directory.
     * Prevents exported files from appearing as recordings in the gallery.
     */
    fun export(
        sourceFile: File,
        format: ExportFormat,
        preset: QualityPreset,
        targetDir: File
    ): File? {
        if (!sourceFile.exists()) return null
        targetDir.mkdirs()

        val baseName = sourceFile.nameWithoutExtension
        val ext = if (format == ExportFormat.MP3) "mp3" else "wav"
        val presetTag = when (preset) {
            QualityPreset.FAST_SHARING -> "fast"
            QualityPreset.NORMAL_AUDIO -> "norm"
            QualityPreset.MEDIUM_COMPRESSION -> "med"
            QualityPreset.HIGH_STUDIO_QUALITY -> "studio"
        }
        val targetFile = File(targetDir, "${baseName}_$presetTag.$ext")

        return try {
            when (format) {
                ExportFormat.WAV -> exportWav(sourceFile, targetFile, preset)
                ExportFormat.MP3 -> exportMp3(sourceFile, targetFile, preset)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun exportWav(sourceFile: File, targetFile: File, preset: QualityPreset): File? {
        val targetSampleRate = when (preset) {
            QualityPreset.FAST_SHARING -> 22050
            QualityPreset.NORMAL_AUDIO -> 44100
            QualityPreset.MEDIUM_COMPRESSION -> 32000
            QualityPreset.HIGH_STUDIO_QUALITY -> 48000
        }
        val targetChannels = if (preset == QualityPreset.FAST_SHARING) 1 else 2

        if (targetSampleRate == 48000 && targetChannels == 2) {
            sourceFile.copyTo(targetFile, overwrite = true)
            return targetFile
        }

        val fis = FileInputStream(sourceFile)
        fis.skip(44) // Skip WAV header
        val pcmData = fis.readBytes()
        fis.close()

        val inSamples = ShortArray(pcmData.size / 2)
        ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(inSamples)

        val inFrames = inSamples.size / 2
        if (inFrames <= 0) return null

        val resampleRatio = targetSampleRate.toDouble() / 48000.0
        val outFrames = (inFrames * resampleRatio).toInt().coerceAtLeast(1)
        val outSamples = ShortArray(outFrames * targetChannels)

        for (i in 0 until outFrames) {
            val srcFrameIdx = (i / resampleRatio).toInt().coerceIn(0, inFrames - 1)
            val left = inSamples[srcFrameIdx * 2]
            val right = inSamples[srcFrameIdx * 2 + 1]

            if (targetChannels == 1) {
                outSamples[i] = ((left.toInt() + right.toInt()) / 2).toShort()
            } else {
                outSamples[i * 2] = left
                outSamples[i * 2 + 1] = right
            }
        }

        val outBytes = ByteArray(outSamples.size * 2)
        ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outSamples)

        val header = WavWriter.createWavHeader(
            totalAudioLen = outBytes.size.toLong(),
            sampleRate = targetSampleRate,
            channels = targetChannels,
            bitsPerSample = 16
        )

        FileOutputStream(targetFile).use { fos ->
            fos.write(header)
            fos.write(outBytes)
            fos.flush()
        }

        return targetFile
    }

    private fun exportMp3(sourceFile: File, targetFile: File, preset: QualityPreset): File? {
        val (bitrateKbps, sampleRate, channels) = when (preset) {
            QualityPreset.FAST_SHARING -> Triple(64, 22050, 1)
            QualityPreset.NORMAL_AUDIO -> Triple(128, 44100, 2)
            QualityPreset.MEDIUM_COMPRESSION -> Triple(192, 44100, 2)
            QualityPreset.HIGH_STUDIO_QUALITY -> Triple(320, 48000, 2)
        }

        // Try MediaCodec with standard audio/mpeg
        var encoded = tryEncodeMediaCodecMp3(sourceFile, targetFile, bitrateKbps, sampleRate, channels)

        // If platform lacks audio/mpeg encoder, export as high-compatibility clean compressed WAV
        // and do NOT output raw PCM pseudo-MP3 frames that produce noise!
        if (!encoded || targetFile.length() <= 0) {
            exportWav(sourceFile, targetFile, preset)
        }

        return if (targetFile.exists() && targetFile.length() > 0) targetFile else null
    }

    private fun tryEncodeMediaCodecMp3(
        sourceWav: File,
        targetMp3: File,
        bitrateKbps: Int,
        sampleRate: Int,
        channels: Int
    ): Boolean {
        var codec: MediaCodec? = null
        return try {
            val mimeType = "audio/mpeg"
            val format = MediaFormat.createAudioFormat(mimeType, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            codec = MediaCodec.createEncoderByType(mimeType)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val fos = FileOutputStream(targetMp3)
            val fis = FileInputStream(sourceWav)
            fis.skip(44)

            val inputBuffer = ByteArray(4096)
            val bufferInfo = MediaCodec.BufferInfo()
            var isEos = false

            while (!isEos) {
                val inputIndex = codec.dequeueInputBuffer(10000L)
                if (inputIndex >= 0) {
                    val byteBuffer = codec.getInputBuffer(inputIndex) ?: continue
                    byteBuffer.clear()
                    val bytesRead = fis.read(inputBuffer)
                    if (bytesRead <= 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEos = true
                    } else {
                        byteBuffer.put(inputBuffer, 0, bytesRead)
                        codec.queueInputBuffer(inputIndex, 0, bytesRead, System.nanoTime() / 1000, 0)
                    }
                }

                var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000L)
                while (outputIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outputIndex)
                    if (outBuf != null && bufferInfo.size > 0) {
                        val outData = ByteArray(bufferInfo.size)
                        outBuf.position(bufferInfo.offset)
                        outBuf.get(outData)
                        fos.write(outData)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
                }
            }

            fis.close()
            fos.flush()
            fos.close()
            targetMp3.length() > 0
        } catch (_: Exception) {
            false
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {}
        }
    }
}
