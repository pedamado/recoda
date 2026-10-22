package com.example.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hardware-accelerated AAC encoder in MPEG-4 (.m4a) container.
 * Encodes real-time PCM audio at 44.1kHz, 16-bit, 128kbps AAC-LC stereo.
 * Optimized for minimal CPU load, fail-safe recording, and safe finalization.
 */
class M4aWriter(
    private val outputFile: File,
    private val sampleRate: Int = 44100,
    private val numChannels: Int = 2, // Stereo 128kb/s 44100Hz 16-bit
    private val bitRate: Int = 128000 // 128 kbps (lowest CPU overhead, native AAC-LC)
) {
    companion object {
        private const val TAG = "M4aWriter"
        private const val TIMEOUT_US = 10000L
    }

    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var trackIndex = -1
    @Volatile private var muxerStarted = false
    @Volatile private var isClosed = false
    private var presentationTimeUs = 0L
    private var lastMuxerPtsUs = -1L
    private var totalSamplesWritten = 0L

    val recordedDurationMs: Long
        get() = if (sampleRate > 0 && numChannels > 0) {
            (totalSamplesWritten * 1000L) / (sampleRate.toLong() * numChannels.toLong())
        } else {
            0L
        }

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, numChannels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    /**
     * Receives interleaved stereo PCM 16-bit samples from AudioEngine DSP loop.
     * Converts to mono if numChannels == 1 and encodes to AAC.
     */
    @Synchronized
    fun writeSamples(stereoInterleaved: ShortArray, length: Int) {
        if (isClosed || length <= 0) return
        val samplesCount = if (numChannels == 1) length / 2 else length
        val pcmBytes = ByteArray(samplesCount * 2)
        val bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)

        if (numChannels == 1) {
            for (i in 0 until samplesCount) {
                val l = stereoInterleaved[i * 2].toInt()
                val r = stereoInterleaved[i * 2 + 1].toInt()
                val mono = ((l + r) / 2).toShort()
                bb.putShort(mono)
            }
        } else {
            for (i in 0 until samplesCount) {
                bb.putShort(stereoInterleaved[i])
            }
        }

        totalSamplesWritten += samplesCount
        try {
            feedPcmBytes(pcmBytes, endOfStream = false)
            drainEncoder(endOfStream = false)
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding audio frame in writeSamples", e)
        }
    }

    private fun feedPcmBytes(data: ByteArray, endOfStream: Boolean) {
        if (isClosed && !endOfStream) return
        var offset = 0
        while (offset < data.size || (endOfStream && offset == 0)) {
            val inputBufferIndex = try {
                codec.dequeueInputBuffer(TIMEOUT_US)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to dequeue input buffer", e)
                break
            }
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: continue
                inputBuffer.clear()
                val bytesToPut = minOf(data.size - offset, inputBuffer.remaining())
                if (bytesToPut > 0) {
                    inputBuffer.put(data, offset, bytesToPut)
                    offset += bytesToPut
                }
                val flags = if (endOfStream && offset >= data.size) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                val pts = presentationTimeUs
                val durationUs = (bytesToPut.toLong() * 1_000_000L) / (2L * numChannels.toLong() * sampleRate.toLong())
                presentationTimeUs += durationUs
                try {
                    codec.queueInputBuffer(inputBufferIndex, 0, bytesToPut, pts, flags)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to queue input buffer", e)
                    break
                }
                if (offset >= data.size) break
            } else {
                drainEncoder(endOfStream = false)
            }
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        var emptyRetries = 0
        while (true) {
            val timeout = if (endOfStream) 15000L else 0L
            val outputBufferIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, timeout)
            } catch (e: Exception) {
                Log.w(TAG, "Error dequeuing output buffer", e)
                break
            }

            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) {
                    break
                } else {
                    emptyRetries++
                    if (emptyRetries >= 10) break
                }
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    Log.w(TAG, "Format changed after muxer started")
                } else {
                    try {
                        val newFormat = codec.outputFormat
                        trackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        muxerStarted = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start muxer", e)
                    }
                }
            } else if (outputBufferIndex >= 0) {
                emptyRetries = 0
                val encodedData = try {
                    codec.getOutputBuffer(outputBufferIndex)
                } catch (e: Exception) {
                    null
                }

                if (encodedData != null) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size != 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)

                        // Guarantee monotonic presentation timestamps to prevent MediaMuxer crash
                        if (bufferInfo.presentationTimeUs <= lastMuxerPtsUs) {
                            bufferInfo.presentationTimeUs = lastMuxerPtsUs + 1000L
                        }
                        lastMuxerPtsUs = bufferInfo.presentationTimeUs

                        // Mark sync frame / keyframe for reliable seeking and MP4 indexing
                        bufferInfo.flags = bufferInfo.flags or MediaCodec.BUFFER_FLAG_KEY_FRAME

                        try {
                            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        } catch (e: Exception) {
                            Log.w(TAG, "Error writing sample data to muxer", e)
                        }
                    }
                }
                try {
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                } catch (_: Exception) {}

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    /**
     * Closes the encoder and finalizes the MP4 container in a fail-safe way.
     * Ensures all buffers are drained and muxer is properly stopped and released.
     */
    @Synchronized
    fun close() {
        if (isClosed) return
        isClosed = true
        try {
            feedPcmBytes(ByteArray(0), endOfStream = true)
            drainEncoder(endOfStream = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error draining encoder on close", e)
        } finally {
            try {
                codec.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping codec", e)
            }
            try {
                codec.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing codec", e)
            }
            try {
                if (muxerStarted) {
                    muxer.stop()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping muxer on close", e)
            }
            try {
                muxer.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing muxer", e)
            }
        }
    }
}
