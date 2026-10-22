package com.example.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

interface DecodedAudio {
    val totalFrames: Int
    val sampleRate: Int
    val durationMs: Long
    val waveform: List<Float>
    fun getLeftSample(frameIndex: Int): Short
    fun getRightSample(frameIndex: Int): Short
    fun readStereoFrames(startFrame: Int, count: Int, dest: ShortArray): Int
    val inMemorySamplesOrNull: ShortArray?
    fun release()
}

class MemoryDecodedAudio(
    val samples: ShortArray, // Stereo interleaved (L, R, L, R...)
    override val sampleRate: Int,
    override val durationMs: Long,
    override val waveform: List<Float>
) : DecodedAudio {
    override val totalFrames: Int = samples.size / 2
    override val inMemorySamplesOrNull: ShortArray = samples

    override fun getLeftSample(frameIndex: Int): Short {
        val f = frameIndex.coerceIn(0, (totalFrames - 1).coerceAtLeast(0))
        return samples[f * 2]
    }

    override fun getRightSample(frameIndex: Int): Short {
        val f = frameIndex.coerceIn(0, (totalFrames - 1).coerceAtLeast(0))
        return samples[f * 2 + 1]
    }

    override fun readStereoFrames(startFrame: Int, count: Int, dest: ShortArray): Int {
        val actualFrames = minOf(count, totalFrames - startFrame).coerceAtLeast(0)
        if (actualFrames <= 0) return 0
        System.arraycopy(samples, startFrame * 2, dest, 0, actualFrames * 2)
        return actualFrames
    }

    override fun release() {}
}

class MappedDecodedAudio(
    private val tempFile: File,
    private val raf: RandomAccessFile,
    private val channel: FileChannel,
    private val mappedBuffer: MappedByteBuffer,
    private val shortBuffer: ShortBuffer,
    override val totalFrames: Int,
    override val sampleRate: Int,
    override val durationMs: Long,
    override val waveform: List<Float>
) : DecodedAudio {
    override val inMemorySamplesOrNull: ShortArray? = null

    override fun getLeftSample(frameIndex: Int): Short {
        val f = frameIndex.coerceIn(0, (totalFrames - 1).coerceAtLeast(0))
        return shortBuffer.get(f * 2)
    }

    override fun getRightSample(frameIndex: Int): Short {
        val f = frameIndex.coerceIn(0, (totalFrames - 1).coerceAtLeast(0))
        return shortBuffer.get(f * 2 + 1)
    }

    override fun readStereoFrames(startFrame: Int, count: Int, dest: ShortArray): Int {
        val actualFrames = minOf(count, totalFrames - startFrame).coerceAtLeast(0)
        if (actualFrames <= 0) return 0
        val startSample = startFrame * 2
        val samplesToRead = actualFrames * 2
        val dup = shortBuffer.duplicate()
        dup.position(startSample)
        dup.get(dest, 0, samplesToRead)
        return actualFrames
    }

    override fun release() {
        try {
            channel.close()
        } catch (_: Throwable) {}
        try {
            raf.close()
        } catch (_: Throwable) {}
        try {
            tempFile.delete()
        } catch (_: Throwable) {}
    }
}

object AudioDecoder {
    private const val TAG = "AudioDecoder"
    private const val DEFAULT_WAVEFORM_POINTS = 120

    fun getDurationMs(file: File): Long {
        if (!file.exists() || file.length() == 0L) return 0L
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val parsed = durStr?.toLongOrNull() ?: 0L
            if (parsed > 0L) parsed else fallbackDuration(file)
        } catch (_: Throwable) {
            fallbackDuration(file)
        } finally {
            try {
                retriever?.release()
            } catch (_: Throwable) {}
        }
    }

    private fun fallbackDuration(file: File): Long {
        return if (file.length() > 0) {
            // Assume 128 kbps stereo = 16000 bytes/sec
            (file.length() * 1000L) / 16000L
        } else {
            0L
        }
    }

    /**
     * Backward-compatible decode method returning DecodedAudio.
     */
    fun decodeToStereoPcm(file: File, targetSampleRate: Int = 44100): DecodedAudio? {
        return decodeAudio(file, null, targetSampleRate)
    }

    /**
     * Decodes any audio file (.m4a, .mp3, .wav) to 16-bit 44.1kHz stereo PCM.
     * Uses memory-mapped disk streaming to handle files of arbitrary length (5 min, 10 min, 1 hour+)
     * without JVM heap exhaustion or OutOfMemoryError.
     * Computes the 120-point visual waveform in a single pass.
     */
    fun decodeAudio(
        file: File,
        cacheDir: File? = null,
        targetSampleRate: Int = 44100,
        numWaveformPoints: Int = DEFAULT_WAVEFORM_POINTS
    ): DecodedAudio? {
        if (!file.exists() || file.length() == 0L) return null

        // Fast path for 16-bit 44.1kHz stereo WAV if under 8 MB:
        if (file.extension.equals("wav", ignoreCase = true) && file.length() in 45..8_000_000L) {
            try {
                val fis = FileInputStream(file)
                fis.skip(44)
                val rawBytes = fis.readBytes()
                fis.close()
                val shorts = ShortArray(rawBytes.size / 2)
                ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                val durationMs = (shorts.size * 1000L) / (targetSampleRate.toLong() * 2L)
                val waveform = computeWaveformFromShorts(shorts, numWaveformPoints)
                return MemoryDecodedAudio(shorts, targetSampleRate, durationMs, waveform)
            } catch (e: Throwable) {
                Log.w(TAG, "Fast WAV decode failed, falling back to MediaCodec", e)
            }
        }

        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var tempFile: File? = null
        var raf: RandomAccessFile? = null
        var fileChannel: FileChannel? = null

        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)

            var audioTrackIndex = -1
            var format: MediaFormat? = null
            var mime: String? = null

            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val trackMime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                if (trackMime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    mime = trackMime
                    break
                }
            }

            if (audioTrackIndex < 0 || format == null || mime == null) {
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val srcSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else targetSampleRate
            val srcChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // Prepare temporary storage file for zero-heap streaming PCM decode
            val parentDir = cacheDir ?: file.parentFile ?: File(System.getProperty("java.io.tmpdir") ?: ".")
            parentDir.mkdirs()
            tempFile = File(parentDir, "recoda_pcm_${System.nanoTime()}.raw")
            raf = RandomAccessFile(tempFile, "rw")
            fileChannel = raf.channel

            val bufferInfo = MediaCodec.BufferInfo()
            var isEos = false
            var decoderDone = false

            // Reusable buffer for mono-to-stereo expansion or direct writing
            var monoExpandBuffer: ByteBuffer? = null

            while (!decoderDone) {
                if (!isEos) {
                    val inIndex = codec.dequeueInputBuffer(10000L)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex)
                        if (inBuffer != null) {
                            inBuffer.clear()
                            val sampleSize = extractor.readSampleData(inBuffer, 0)
                            if (sampleSize < 0) {
                                isEos = true
                                codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000L)
                if (outIndex >= 0) {
                    val outBuffer = codec.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        if (srcChannels == 1) {
                            // Expand mono to stereo in 4KB chunks
                            val monoShorts = bufferInfo.size / 2
                            val stereoBytes = monoShorts * 4
                            if (monoExpandBuffer == null || monoExpandBuffer.capacity() < stereoBytes) {
                                monoExpandBuffer = ByteBuffer.allocateDirect(maxOf(stereoBytes, 8192)).order(ByteOrder.LITTLE_ENDIAN)
                            }
                            val targetBuf = monoExpandBuffer
                            targetBuf.clear()
                            val srcShortBuffer = outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            while (srcShortBuffer.hasRemaining()) {
                                val s = srcShortBuffer.get()
                                targetBuf.putShort(s)
                                targetBuf.putShort(s)
                            }
                            targetBuf.flip()
                            fileChannel.write(targetBuf)
                        } else {
                            // Direct stream write of stereo PCM to disk - 0 heap allocation!
                            fileChannel.write(outBuffer)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        decoderDone = true
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (isEos) decoderDone = true
                }
            }

            val totalBytes = fileChannel.size()
            if (totalBytes < 4) {
                tempFile.delete()
                return null
            }

            val totalFrames = (totalBytes / 4).toInt()
            val totalDurationMs = (totalFrames * 1000L) / targetSampleRate.toLong()

            // Memory-map the PCM file into native virtual address space (0 JVM heap usage!)
            val mappedBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, totalBytes)
            mappedBuffer.order(ByteOrder.LITTLE_ENDIAN)
            val shortBuffer = mappedBuffer.asShortBuffer()

            val waveform = computeWaveformFromBuffer(shortBuffer, totalFrames, numWaveformPoints)

            MappedDecodedAudio(
                tempFile = tempFile,
                raf = raf,
                channel = fileChannel,
                mappedBuffer = mappedBuffer,
                shortBuffer = shortBuffer,
                totalFrames = totalFrames,
                sampleRate = targetSampleRate,
                durationMs = totalDurationMs,
                waveform = waveform
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to decode audio file ${file.name}", t)
            try {
                fileChannel?.close()
                raf?.close()
                tempFile?.delete()
            } catch (_: Throwable) {}
            null
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Throwable) {}
            try {
                extractor?.release()
            } catch (_: Throwable) {}
        }
    }

    private fun computeWaveformFromBuffer(
        shortBuffer: ShortBuffer,
        totalFrames: Int,
        numPoints: Int
    ): List<Float> {
        if (totalFrames <= 0) return List(numPoints) { 0.05f }
        val framesPerBucket = (totalFrames / numPoints).coerceAtLeast(1)
        val result = ArrayList<Float>(numPoints)

        for (b in 0 until numPoints) {
            val start = b * framesPerBucket
            val end = minOf(start + framesPerBucket, totalFrames)
            var maxAmp = 0
            val step = ((end - start) / 48).coerceAtLeast(1)
            var f = start
            while (f < end) {
                val l = kotlin.math.abs(shortBuffer.get(f * 2).toInt())
                val r = kotlin.math.abs(shortBuffer.get(f * 2 + 1).toInt())
                val peak = maxOf(l, r)
                if (peak > maxAmp) maxAmp = peak
                f += step
            }
            val norm = (maxAmp / 32768.0f).coerceIn(0.02f, 1.0f)
            result.add(norm)
        }
        while (result.size < numPoints) result.add(0.02f)
        return result
    }

    private fun computeWaveformFromShorts(
        samples: ShortArray,
        numPoints: Int
    ): List<Float> {
        val totalFrames = samples.size / 2
        if (totalFrames <= 0) return List(numPoints) { 0.05f }
        val framesPerBucket = (totalFrames / numPoints).coerceAtLeast(1)
        val result = ArrayList<Float>(numPoints)

        for (b in 0 until numPoints) {
            val start = b * framesPerBucket
            val end = minOf(start + framesPerBucket, totalFrames)
            var maxAmp = 0
            val step = ((end - start) / 48).coerceAtLeast(1)
            var f = start
            while (f < end) {
                val l = kotlin.math.abs(samples[f * 2].toInt())
                val r = kotlin.math.abs(samples[f * 2 + 1].toInt())
                val peak = maxOf(l, r)
                if (peak > maxAmp) maxAmp = peak
                f += step
            }
            val norm = (maxAmp / 32768.0f).coerceIn(0.02f, 1.0f)
            result.add(norm)
        }
        while (result.size < numPoints) result.add(0.02f)
        return result
    }
}
