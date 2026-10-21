package com.example.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DecodedAudio(
    val samples: ShortArray, // Stereo interleaved (L, R, L, R...) at targetSampleRate
    val sampleRate: Int,
    val durationMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DecodedAudio
        return sampleRate == other.sampleRate && durationMs == other.durationMs
    }

    override fun hashCode(): Int {
        var result = sampleRate
        result = 31 * result + durationMs.hashCode()
        return result
    }
}

object AudioDecoder {
    private const val TAG = "AudioDecoder"

    fun getDurationMs(file: File): Long {
        if (!file.exists()) return 0L
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            val parsed = durStr?.toLongOrNull() ?: 0L
            if (parsed > 0L) parsed else fallbackWavDuration(file)
        } catch (_: Exception) {
            fallbackWavDuration(file)
        }
    }

    private fun fallbackWavDuration(file: File): Long {
        return if (file.extension.equals("wav", ignoreCase = true) && file.length() > 44) {
            ((file.length() - 44) * 1000L) / (44100L * 2 * 2)
        } else {
            0L
        }
    }

    /**
     * Decodes any audio file (.m4a, .mp3, .wav) to stereo interleaved 16-bit PCM at targetSampleRate.
     * Caches in memory for instant high-speed scrubbing, loop, and reverse playback.
     */
    fun decodeToStereoPcm(file: File, targetSampleRate: Int = 44100): DecodedAudio? {
        if (!file.exists()) return null

        // Fast path for 16-bit 44100Hz stereo WAV:
        if (file.extension.equals("wav", ignoreCase = true) && file.length() > 44) {
            try {
                val fis = FileInputStream(file)
                fis.skip(44)
                val rawBytes = fis.readBytes()
                fis.close()
                val shorts = ShortArray(rawBytes.size / 2)
                ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                val durationMs = (shorts.size * 1000L) / (targetSampleRate.toLong() * 2L)
                return DecodedAudio(shorts, targetSampleRate, durationMs)
            } catch (e: Exception) {
                Log.w(TAG, "Fast WAV decode failed, falling back to MediaCodec", e)
            }
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        return try {
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
                extractor.release()
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val srcSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else targetSampleRate
            val srcChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val rawPcmList = ArrayList<ShortArray>()
            var totalDecodedShorts = 0

            val bufferInfo = MediaCodec.BufferInfo()
            var isEos = false
            var decoderDone = false

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
                        val shortBuffer = outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val chunk = ShortArray(shortBuffer.remaining())
                        shortBuffer.get(chunk)
                        rawPcmList.add(chunk)
                        totalDecodedShorts += chunk.size
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        decoderDone = true
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (isEos) decoderDone = true
                }
            }

            if (totalDecodedShorts == 0) return null

            // Flatten decoded shorts
            val flattened = ShortArray(totalDecodedShorts)
            var destOffset = 0
            for (chunk in rawPcmList) {
                System.arraycopy(chunk, 0, flattened, destOffset, chunk.size)
                destOffset += chunk.size
            }

            // Convert to Stereo if mono
            val stereoSamples: ShortArray
            if (srcChannels == 1) {
                stereoSamples = ShortArray(flattened.size * 2)
                for (i in flattened.indices) {
                    val s = flattened[i]
                    stereoSamples[i * 2] = s
                    stereoSamples[i * 2 + 1] = s
                }
            } else {
                stereoSamples = flattened
            }

            // Resample if sample rate doesn't match targetSampleRate
            val finalSamples: ShortArray
            if (srcSampleRate != targetSampleRate && srcSampleRate > 0) {
                val ratio = targetSampleRate.toDouble() / srcSampleRate.toDouble()
                val srcFrames = stereoSamples.size / 2
                val outFrames = (srcFrames * ratio).toInt()
                finalSamples = ShortArray(outFrames * 2)
                for (f in 0 until outFrames) {
                    val srcF = (f / ratio).toInt().coerceIn(0, srcFrames - 1)
                    finalSamples[f * 2] = stereoSamples[srcF * 2]
                    finalSamples[f * 2 + 1] = stereoSamples[srcF * 2 + 1]
                }
            } else {
                finalSamples = stereoSamples
            }

            val totalDurationMs = (finalSamples.size * 1000L) / (targetSampleRate.toLong() * 2L)
            DecodedAudio(finalSamples, targetSampleRate, totalDurationMs)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode audio file ${file.name}", e)
            null
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {}
            try {
                extractor.release()
            } catch (_: Exception) {}
        }
    }
}
