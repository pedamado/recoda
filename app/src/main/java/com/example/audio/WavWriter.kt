package com.example.audio

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance WAV file writer for 16-bit PCM stereo audio.
 * Writes standard RIFF WAV format directly to local app storage.
 */
class WavWriter(
    private val outputFile: File,
    private val sampleRate: Int = 48000,
    private val numChannels: Int = 2,
    private val bitsPerSample: Int = 16
) {
    private var fileOutputStream: FileOutputStream? = null
    private var totalAudioBytes: Long = 0L
    private var isRecording: Boolean = false

    private val bytesPerSample = bitsPerSample / 8
    private val blockAlign = numChannels * bytesPerSample
    private val byteRate = sampleRate * blockAlign

    init {
        outputFile.parentFile?.mkdirs()
        fileOutputStream = FileOutputStream(outputFile)
        // Write placeholder 44-byte header
        val dummyHeader = ByteArray(44)
        fileOutputStream?.write(dummyHeader)
        isRecording = true
    }

    /**
     * Writes 16-bit PCM samples (interleaved stereo or mono).
     */
    @Synchronized
    fun writeSamples(samples: ShortArray, length: Int) {
        if (!isRecording || fileOutputStream == null) return
        val byteBuffer = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until length) {
            byteBuffer.putShort(samples[i])
        }
        val bytes = byteBuffer.array()
        fileOutputStream?.write(bytes)
        totalAudioBytes += bytes.size
    }

    /**
     * Finalizes the WAV header with the accurate file and data chunk sizes and closes the stream.
     */
    @Synchronized
    fun close(): Long {
        if (!isRecording) return totalAudioBytes
        isRecording = false

        try {
            fileOutputStream?.flush()
            fileOutputStream?.close()
        } catch (_: Exception) {}
        fileOutputStream = null

        // Overwrite the header with accurate file and chunk lengths using RandomAccessFile
        try {
            RandomAccessFile(outputFile, "rw").use { raf ->
                raf.seek(0)
                val header = createWavHeader(totalAudioBytes, sampleRate, numChannels, bitsPerSample)
                raf.write(header)
            }
        } catch (_: Exception) {}

        return totalAudioBytes
    }

    val recordedDurationMs: Long
        get() = if (byteRate > 0) (totalAudioBytes * 1000L) / byteRate else 0L

    companion object {
        fun createWavHeader(
            totalAudioLen: Long,
            sampleRate: Int,
            channels: Int,
            bitsPerSample: Int
        ): ByteArray {
            val totalDataLen = totalAudioLen + 36
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = (channels * bitsPerSample / 8).toShort()

            val header = ByteArray(44)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            // RIFF chunk descriptor
            buffer.put('R'.code.toByte())
            buffer.put('I'.code.toByte())
            buffer.put('F'.code.toByte())
            buffer.put('F'.code.toByte())
            buffer.putInt(totalDataLen.toInt()) // ChunkSize
            buffer.put('W'.code.toByte())
            buffer.put('A'.code.toByte())
            buffer.put('V'.code.toByte())
            buffer.put('E'.code.toByte())

            // "fmt " sub-chunk
            buffer.put('f'.code.toByte())
            buffer.put('m'.code.toByte())
            buffer.put('t'.code.toByte())
            buffer.put(' '.code.toByte())
            buffer.putInt(16) // Subchunk1Size for PCM
            buffer.putShort(1.toShort()) // AudioFormat 1 = PCM
            buffer.putShort(channels.toShort())
            buffer.putInt(sampleRate)
            buffer.putInt(byteRate)
            buffer.putShort(blockAlign)
            buffer.putShort(bitsPerSample.toShort())

            // "data" sub-chunk
            buffer.put('d'.code.toByte())
            buffer.put('a'.code.toByte())
            buffer.put('t'.code.toByte())
            buffer.put('a'.code.toByte())
            buffer.putInt(totalAudioLen.toInt()) // Subchunk2Size

            return header
        }
    }
}
