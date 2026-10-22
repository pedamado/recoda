package com.example.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio exporter supporting:
 * 1. Default: Original 128 kb/s stereo 44100Hz 16-bit .m4a (pristine master)
 * 2. Medium quality: 128 kb/s stereo 44100Hz 16-bit .mp3
 * 3. Small file size: 64 kb/s mono 44100Hz 16-bit .mp3
 *
 * Uses streaming chunk-based encoding to prevent memory spikes on large recordings.
 * No WAV export or support.
 */
object AudioExporter {

    private const val TAG = "AudioExporter"

    enum class ExportPreset(
        val label: String,
        val description: String,
        val extension: String,
        val bitrateKbps: Int,
        val sampleRate: Int,
        val channels: Int
    ) {
        ORIGINAL_M4A(
            label = "original m4a (128 kb/s stereo)",
            description = "44.1kHz • 128 kb/s stereo • original master (default)",
            extension = "m4a",
            bitrateKbps = 128,
            sampleRate = 44100,
            channels = 2
        ),
        MP3_STEREO_128K(
            label = "medium quality mp3 (128 kb/s stereo)",
            description = "44.1kHz 16-bit • 128 kb/s stereo • balanced size",
            extension = "mp3",
            bitrateKbps = 128,
            sampleRate = 44100,
            channels = 2
        ),
        MP3_MONO_64K(
            label = "small file size mp3 (64 kb/s mono)",
            description = "44.1kHz 16-bit • 64 kb/s mono • compact",
            extension = "mp3",
            bitrateKbps = 64,
            sampleRate = 44100,
            channels = 1
        )
    }

    /**
     * Exports an audio recording to the selected preset in the dedicated exports directory.
     * Prevents exported files from appearing as recordings in the gallery.
     */
    fun export(
        sourceFile: File,
        preset: ExportPreset,
        targetDir: File
    ): File? {
        if (!sourceFile.exists()) return null
        targetDir.mkdirs()

        val baseName = sourceFile.nameWithoutExtension
        val presetTag = when (preset) {
            ExportPreset.ORIGINAL_M4A -> "master"
            ExportPreset.MP3_STEREO_128K -> "128k_stereo"
            ExportPreset.MP3_MONO_64K -> "64k_mono"
        }
        val targetFile = File(targetDir, "${baseName}_$presetTag.${preset.extension}")

        return try {
            when (preset) {
                ExportPreset.ORIGINAL_M4A -> exportOriginalM4a(sourceFile, targetFile)
                ExportPreset.MP3_STEREO_128K,
                ExportPreset.MP3_MONO_64K -> exportMp3(sourceFile, targetFile, preset)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Export failed for preset $preset", e)
            null
        }
    }

    private fun exportOriginalM4a(sourceFile: File, targetFile: File): File? {
        if (sourceFile.extension.equals("m4a", ignoreCase = true)) {
            sourceFile.copyTo(targetFile, overwrite = true)
            return targetFile
        }
        // If source is not m4a, transcode to 128k stereo m4a using streaming chunks
        val decoded = AudioDecoder.decodeAudio(sourceFile, targetFile.parentFile, 44100) ?: return null
        val writer = M4aWriter(
            outputFile = targetFile,
            sampleRate = 44100,
            numChannels = 2,
            bitRate = 128000
        )
        try {
            val chunkFrames = 2048
            val chunkShorts = ShortArray(chunkFrames * 2)
            var currentFrame = 0
            while (currentFrame < decoded.totalFrames) {
                val framesRead = decoded.readStereoFrames(currentFrame, chunkFrames, chunkShorts)
                if (framesRead <= 0) break
                writer.writeSamples(chunkShorts, framesRead * 2)
                currentFrame += framesRead
            }
        } finally {
            writer.close()
            decoded.release()
        }
        return if (targetFile.exists() && targetFile.length() > 0) targetFile else null
    }

    private fun exportMp3(sourceFile: File, targetFile: File, preset: ExportPreset): File? {
        val encoded = tryEncodeMediaCodecMp3(
            sourceFile = sourceFile,
            targetMp3 = targetFile,
            bitrateKbps = preset.bitrateKbps,
            sampleRate = preset.sampleRate,
            channels = preset.channels
        )

        if (encoded && targetFile.exists() && targetFile.length() > 0) {
            return targetFile
        }

        // Clean fallback if hardware encoder lacks audio/mpeg: transcode to efficient AAC (.m4a) at that bitrate
        val fallbackFile = File(targetFile.parentFile, "${targetFile.nameWithoutExtension}.m4a")
        val decoded = AudioDecoder.decodeAudio(sourceFile, targetFile.parentFile, preset.sampleRate) ?: return null
        val writer = M4aWriter(
            outputFile = fallbackFile,
            sampleRate = preset.sampleRate,
            numChannels = preset.channels,
            bitRate = preset.bitrateKbps * 1000
        )
        try {
            val chunkFrames = 2048
            val chunkShorts = ShortArray(chunkFrames * 2)
            var currentFrame = 0
            while (currentFrame < decoded.totalFrames) {
                val framesRead = decoded.readStereoFrames(currentFrame, chunkFrames, chunkShorts)
                if (framesRead <= 0) break
                writer.writeSamples(chunkShorts, framesRead * 2)
                currentFrame += framesRead
            }
        } finally {
            writer.close()
            decoded.release()
        }
        return if (fallbackFile.exists() && fallbackFile.length() > 0) fallbackFile else null
    }

    private fun tryEncodeMediaCodecMp3(
        sourceFile: File,
        targetMp3: File,
        bitrateKbps: Int,
        sampleRate: Int,
        channels: Int
    ): Boolean {
        var codec: MediaCodec? = null
        var decoded: DecodedAudio? = null
        return try {
            val mimeType = "audio/mpeg"
            val format = MediaFormat.createAudioFormat(mimeType, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            codec = MediaCodec.createEncoderByType(mimeType)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            decoded = AudioDecoder.decodeAudio(sourceFile, targetMp3.parentFile, sampleRate) ?: return false
            val totalFrames = decoded.totalFrames

            val fos = FileOutputStream(targetMp3)
            val bufferInfo = MediaCodec.BufferInfo()

            val chunkFrames = 2048
            val stereoChunk = ShortArray(chunkFrames * 2)
            val pcmBytes = ByteArray(chunkFrames * channels * 2)
            val pcmBb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)

            var frameCursor = 0
            var isEos = false

            while (!isEos) {
                val inputIndex = codec.dequeueInputBuffer(10000L)
                if (inputIndex >= 0) {
                    val byteBuffer = codec.getInputBuffer(inputIndex) ?: continue
                    byteBuffer.clear()

                    if (frameCursor >= totalFrames) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEos = true
                    } else {
                        val toRead = minOf(chunkFrames, totalFrames - frameCursor)
                        val framesRead = decoded.readStereoFrames(frameCursor, toRead, stereoChunk)
                        frameCursor += framesRead

                        pcmBb.clear()
                        if (channels == 1) {
                            for (f in 0 until framesRead) {
                                val l = stereoChunk[f * 2].toInt()
                                val r = stereoChunk[f * 2 + 1].toInt()
                                pcmBb.putShort(((l + r) / 2).toShort())
                            }
                        } else {
                            for (f in 0 until framesRead) {
                                pcmBb.putShort(stereoChunk[f * 2])
                                pcmBb.putShort(stereoChunk[f * 2 + 1])
                            }
                        }
                        val bytesReady = framesRead * channels * 2
                        byteBuffer.put(pcmBytes, 0, bytesReady)

                        val flags = if (frameCursor >= totalFrames) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        val ptsUs = (frameCursor.toLong() * 1_000_000L) / sampleRate.toLong()
                        codec.queueInputBuffer(inputIndex, 0, bytesReady, ptsUs, flags)
                        if (flags != 0) isEos = true
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

            fos.flush()
            fos.close()
            targetMp3.length() > 0
        } catch (_: Throwable) {
            false
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Throwable) {}
            decoded?.release()
        }
    }
}
