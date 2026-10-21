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
 * Hardware-accelerated AAC encoder in MPEG-4 (.m4a) container.
 * Encodes real-time PCM audio at 44.1kHz, 16-bit, 192kbps AAC-LC stereo.
 */
class M4aWriter(
    private val outputFile: File,
    private val sampleRate: Int = 44100,
    private val numChannels: Int = 2, // Stereo 192kbps 44100Hz 16-bit
    private val bitRate: Int = 192000  // 192 kbps
) {
    companion object {
        private const val TAG = "M4aWriter"
        private const val TIMEOUT_US = 10000L
    }

    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var trackIndex = -1
    private var muxerStarted = false
    private var presentationTimeUs = 0L
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
        if (length <= 0) return
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
        feedPcmBytes(pcmBytes, endOfStream = false)
        drainEncoder(endOfStream = false)
    }

    private fun feedPcmBytes(data: ByteArray, endOfStream: Boolean) {
        var offset = 0
        while (offset < data.size || (endOfStream && offset == 0)) {
            val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
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
                codec.queueInputBuffer(inputBufferIndex, 0, bytesToPut, pts, flags)
                if (offset >= data.size) break
            } else {
                drainEncoder(endOfStream = false)
            }
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 20000L else 0L)
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    Log.w(TAG, "Format changed after muxer started")
                } else {
                    val newFormat = codec.outputFormat
                    trackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                }
            } else if (outputBufferIndex >= 0) {
                val encodedData = codec.getOutputBuffer(outputBufferIndex)
                if (encodedData != null) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size != 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                }
                codec.releaseOutputBuffer(outputBufferIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    @Synchronized
    fun close() {
        try {
            feedPcmBytes(ByteArray(0), endOfStream = true)
            drainEncoder(endOfStream = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error draining encoder on close", e)
        } finally {
            try {
                codec.stop()
                codec.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing codec", e)
            }
            try {
                if (muxerStarted) {
                    muxer.stop()
                }
                muxer.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing muxer", e)
            }
        }
    }
}
