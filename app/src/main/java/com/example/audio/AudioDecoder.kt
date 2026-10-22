package com.example.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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

/**
 * PagedFileDecodedAudio handles audio of arbitrary duration (15 min, 1 hour, etc.)
 * by streaming blocks from disk using a lightweight sliding cache without memory mapping.
 * Prevents OS SIGBUS crashes, out-of-virtual-memory errors, and heap exhaustion.
 */
class PagedFileDecodedAudio(
    private val tempFile: File,
    private val raf: RandomAccessFile,
    private val channel: FileChannel,
    override val totalFrames: Int,
    override val sampleRate: Int,
    override val durationMs: Long,
    override val waveform: List<Float>
) : DecodedAudio {
    override val inMemorySamplesOrNull: ShortArray? = null

    private val cacheLock = Any()
    private val cacheFrames = 16384
    private var cachedStartFrame = -1
    private var cachedFramesCount = 0
    private val cacheBuffer = ShortArray(cacheFrames * 2)
    private val byteTransferBuffer = ByteBuffer.allocate(cacheFrames * 4).order(ByteOrder.LITTLE_ENDIAN)

    private fun ensureFrameLoaded(frameIndex: Int) {
        if (frameIndex in cachedStartFrame until (cachedStartFrame + cachedFramesCount)) {
            return
        }
        val start = (frameIndex - (cacheFrames / 4)).coerceIn(0, (totalFrames - 1).coerceAtLeast(0))
        val count = minOf(cacheFrames, totalFrames - start).coerceAtLeast(0)
        if (count <= 0) return

        try {
            byteTransferBuffer.clear()
            byteTransferBuffer.limit(count * 4)
            channel.position(start.toLong() * 4L)
            var bytesRead = 0
            while (bytesRead < count * 4 && channel.position() < channel.size()) {
                val r = channel.read(byteTransferBuffer)
                if (r <= 0) break
                bytesRead += r
            }
            byteTransferBuffer.flip()
            val shortCount = bytesRead / 2
            byteTransferBuffer.asShortBuffer().get(cacheBuffer, 0, shortCount)
            cachedStartFrame = start
            cachedFramesCount = shortCount / 2
        } catch (e: Exception) {
            Log.w("PagedAudio", "Error reading frame cache", e)
        }
    }

    override fun getLeftSample(frameIndex: Int): Short = synchronized(cacheLock) {
        val f = frameIndex.coerceIn(0, (totalFrames - 1).coerceAtLeast(0))
        ensureFrameLoaded(f)
        val offset = (f - cachedStartFrame) * 2
        if (offset in 0 until (cachedFramesCount * 2)) cacheBuffer[offset] else 0
    }

    override fun getRightSample(frameIndex: Int): Short = synchronized(cacheLock) {
        val f = frameIndex.coerceIn(0, (totalFrames - 1).coerceAtLeast(0))
        ensureFrameLoaded(f)
        val offset = (f - cachedStartFrame) * 2 + 1
        if (offset in 0 until (cachedFramesCount * 2)) cacheBuffer[offset] else 0
    }

    override fun readStereoFrames(startFrame: Int, count: Int, dest: ShortArray): Int = synchronized(cacheLock) {
        val actualFrames = minOf(count, totalFrames - startFrame).coerceAtLeast(0)
        if (actualFrames <= 0) return 0

        // If request fits within current sliding window cache:
        if (startFrame >= cachedStartFrame && (startFrame + actualFrames) <= (cachedStartFrame + cachedFramesCount)) {
            val offset = (startFrame - cachedStartFrame) * 2
            System.arraycopy(cacheBuffer, offset, dest, 0, actualFrames * 2)
            return actualFrames
        }

        // Direct read from channel
        try {
            val byteCount = actualFrames * 4
            val directBuf = ByteBuffer.allocate(byteCount).order(ByteOrder.LITTLE_ENDIAN)
            channel.position(startFrame.toLong() * 4L)
            var totalRead = 0
            while (totalRead < byteCount && channel.position() < channel.size()) {
                val r = channel.read(directBuf)
                if (r <= 0) break
                totalRead += r
            }
            directBuf.flip()
            val shortsRead = totalRead / 2
            directBuf.asShortBuffer().get(dest, 0, shortsRead)
            return shortsRead / 2
        } catch (e: Exception) {
            Log.w("PagedAudio", "Direct read error", e)
            return 0
        }
    }

    override fun release() {
        try { channel.close() } catch (_: Throwable) {}
        try { raf.close() } catch (_: Throwable) {}
        try { tempFile.delete() } catch (_: Throwable) {}
    }
}

object AudioDecoder {
    private const val TAG = "AudioDecoder"
    private const val DEFAULT_WAVEFORM_POINTS = 120
    private const val MAX_MAPPED_SIZE_BYTES = 32L * 1024L * 1024L // 32MB max for mmap, larger files use paged

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
            (file.length() * 1000L) / 16000L
        } else {
            0L
        }
    }

    fun decodeToStereoPcm(file: File, targetSampleRate: Int = 44100): DecodedAudio? {
        return decodeAudio(file, null, targetSampleRate)
    }

    /**
     * Decodes any audio file (.m4a, .mp3, .wav) to 16-bit 44.1kHz stereo PCM.
     * Uses buffered streaming and paged file streaming to handle files of arbitrary length
     * without JVM heap exhaustion or Android OS ANR / bus errors.
     */
    fun decodeAudio(
        file: File,
        cacheDir: File? = null,
        targetSampleRate: Int = 44100,
        numWaveformPoints: Int = DEFAULT_WAVEFORM_POINTS
    ): DecodedAudio? {
        if (!file.exists() || file.length() == 0L) return null

        // Fast path for small 16-bit 44.1kHz stereo WAV:
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
        var bufferedOutput: BufferedOutputStream? = null

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
            val srcChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // Prepare temporary storage file with 64KB buffer for fast writing
            val parentDir = cacheDir ?: file.parentFile ?: File(System.getProperty("java.io.tmpdir") ?: ".")
            parentDir.mkdirs()
            tempFile = File(parentDir, "recoda_pcm_${System.nanoTime()}.raw")
            bufferedOutput = BufferedOutputStream(FileOutputStream(tempFile), 65536)

            val bufferInfo = MediaCodec.BufferInfo()
            var isEos = false
            var decoderDone = false

            // Reusable buffers
            var monoShortBuffer: ShortArray? = null
            var monoExpandedBytes: ByteArray? = null
            var stereoTransferBytes: ByteArray? = null

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
                            val monoShorts = bufferInfo.size / 2
                            if (monoShortBuffer == null || monoShortBuffer.size < monoShorts) {
                                monoShortBuffer = ShortArray(monoShorts)
                            }
                            outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(monoShortBuffer, 0, monoShorts)

                            val stereoByteCount = monoShorts * 4
                            if (monoExpandedBytes == null || monoExpandedBytes.size < stereoByteCount) {
                                monoExpandedBytes = ByteArray(stereoByteCount)
                            }
                            val b = monoExpandedBytes
                            var byteIdx = 0
                            for (sIdx in 0 until monoShorts) {
                                val s = monoShortBuffer[sIdx]
                                val low = (s.toInt() and 0xFF).toByte()
                                val high = ((s.toInt() shr 8) and 0xFF).toByte()
                                b[byteIdx++] = low
                                b[byteIdx++] = high
                                b[byteIdx++] = low
                                b[byteIdx++] = high
                            }
                            bufferedOutput.write(b, 0, stereoByteCount)
                        } else {
                            val byteCount = bufferInfo.size
                            if (stereoTransferBytes == null || stereoTransferBytes.size < byteCount) {
                                stereoTransferBytes = ByteArray(maxOf(byteCount, 16384))
                            }
                            outBuffer.get(stereoTransferBytes, 0, byteCount)
                            bufferedOutput.write(stereoTransferBytes, 0, byteCount)
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

            bufferedOutput.flush()
            bufferedOutput.close()
            bufferedOutput = null

            val totalBytes = tempFile.length()
            if (totalBytes < 4) {
                tempFile.delete()
                return null
            }

            val totalFrames = (totalBytes / 4).toInt()
            val totalDurationMs = (totalFrames * 1000L) / targetSampleRate.toLong()

            val raf = RandomAccessFile(tempFile, "r")
            val fileChannel = raf.channel

            // For files under MAX_MAPPED_SIZE_BYTES (~32MB), try memory mapping.
            // For larger files (e.g. 15-minute tracks ~160MB), use PagedFileDecodedAudio
            // to completely avoid address space limits and SIGBUS errors.
            if (totalBytes <= MAX_MAPPED_SIZE_BYTES) {
                try {
                    val mappedBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, totalBytes)
                    mappedBuffer.order(ByteOrder.LITTLE_ENDIAN)
                    val shortBuffer = mappedBuffer.asShortBuffer()
                    val waveform = computeWaveformFromBuffer(shortBuffer, totalFrames, numWaveformPoints)

                    return MappedDecodedAudio(
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
                    Log.w(TAG, "mmap failed on file size $totalBytes, falling back to paged", t)
                }
            }

            // Fallback / Large-file path: PagedFileDecodedAudio
            val waveform = computeWaveformFromChannel(fileChannel, totalFrames, numWaveformPoints)
            PagedFileDecodedAudio(
                tempFile = tempFile,
                raf = raf,
                channel = fileChannel,
                totalFrames = totalFrames,
                sampleRate = targetSampleRate,
                durationMs = totalDurationMs,
                waveform = waveform
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to decode audio file ${file.name}", t)
            try { bufferedOutput?.close() } catch (_: Throwable) {}
            try { tempFile?.delete() } catch (_: Throwable) {}
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

    private fun computeWaveformFromChannel(
        channel: FileChannel,
        totalFrames: Int,
        numPoints: Int
    ): List<Float> {
        if (totalFrames <= 0) return List(numPoints) { 0.05f }
        val framesPerBucket = (totalFrames / numPoints).coerceAtLeast(1)
        val result = ArrayList<Float>(numPoints)
        val sampleBuf = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)

        for (b in 0 until numPoints) {
            val startFrame = b * framesPerBucket
            val bytePos = startFrame.toLong() * 4L
            var maxAmp = 0

            try {
                channel.position(bytePos)
                sampleBuf.clear()
                val read = channel.read(sampleBuf)
                sampleBuf.flip()
                val shortsRead = read / 2
                val sBuf = sampleBuf.asShortBuffer()
                var i = 0
                while (i < shortsRead) {
                    val l = kotlin.math.abs(sBuf.get(i).toInt())
                    val r = if (i + 1 < shortsRead) kotlin.math.abs(sBuf.get(i + 1).toInt()) else l
                    val peak = maxOf(l, r)
                    if (peak > maxAmp) maxAmp = peak
                    i += 2
                }
            } catch (_: Exception) {}

            val norm = (maxAmp / 32768.0f).coerceIn(0.02f, 1.0f)
            result.add(norm)
        }
        while (result.size < numPoints) result.add(0.02f)
        return result
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
