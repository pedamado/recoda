package com.example.audio

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Standard Robert Bristow-Johnson (RBJ) Audio EQ Cookbook Biquad Filter.
 * Implemented using Transposed Direct Form II for numerical stability in float.
 * Supports stereo (separate state for Left and Right channels).
 */
class BiquadFilter {

    enum class Type {
        LOW_SHELF,
        PEAKING,
        HIGH_SHELF
    }

    // Normalized filter coefficients:
    // H(z) = (b0 + b1*z^-1 + b2*z^-2) / (1 + a1*z^-1 + a2*z^-2)
    private var b0: Float = 1.0f
    private var b1: Float = 0.0f
    private var b2: Float = 0.0f
    private var a1: Float = 0.0f
    private var a2: Float = 0.0f

    // Direct Form II Transposed state variables
    private var z1Left: Float = 0.0f
    private var z2Left: Float = 0.0f
    private var z1Right: Float = 0.0f
    private var z2Right: Float = 0.0f

    private var currentType: Type = Type.PEAKING
    private var currentSampleRate: Float = 48000f
    private var currentFrequency: Float = 1000f
    private var currentGainDb: Float = 0.0f
    private var currentQ: Float = 1.0f

    /**
     * Updates the biquad filter coefficients according to RBJ formulas.
     *
     * @param type Filter type (LOW_SHELF, PEAKING, HIGH_SHELF)
     * @param sampleRate Sampling rate in Hz (e.g. 48000f)
     * @param frequency Center or corner frequency in Hz
     * @param gainDb Gain in decibels
     * @param q Quality factor (e.g. 0.707 for Butterworth shelf, 1.0 for mid peak)
     */
    fun updateCoefficients(
        type: Type,
        sampleRate: Float,
        frequency: Float,
        gainDb: Float,
        q: Float = 0.707f
    ) {
        currentType = type
        currentSampleRate = sampleRate
        currentFrequency = frequency
        currentGainDb = gainDb
        currentQ = q

        // Fast path for 0 dB gain: identity filter
        if (gainDb == 0.0f) {
            b0 = 1.0f
            b1 = 0.0f
            b2 = 0.0f
            a1 = 0.0f
            a2 = 0.0f
            return
        }

        val clampedFreq = frequency.coerceIn(20f, sampleRate * 0.49f)
        val w0 = (2.0 * Math.PI * clampedFreq / sampleRate).toFloat()
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val a = 10.0f.pow(gainDb / 40.0f) // sqrt(A_linear)
        val alpha = sinW0 / (2.0f * q)

        val tempB0: Float
        val tempB1: Float
        val tempB2: Float
        val tempA0: Float
        val tempA1: Float
        val tempA2: Float

        when (type) {
            Type.LOW_SHELF -> {
                val sqrtA = sqrt(a)
                val alphaShelf = (sinW0 / 2.0f) * sqrt((a + 1.0f / a) * (1.0f / q - 1.0f) + 2.0f).coerceAtLeast(0.001f)
                val twoSqrtAAlpha = 2.0f * sqrtA * alphaShelf

                tempB0 = a * ((a + 1.0f) - (a - 1.0f) * cosW0 + twoSqrtAAlpha)
                tempB1 = 2.0f * a * ((a - 1.0f) - (a + 1.0f) * cosW0)
                tempB2 = a * ((a + 1.0f) - (a - 1.0f) * cosW0 - twoSqrtAAlpha)
                tempA0 = (a + 1.0f) + (a - 1.0f) * cosW0 + twoSqrtAAlpha
                tempA1 = -2.0f * ((a - 1.0f) + (a + 1.0f) * cosW0)
                tempA2 = (a + 1.0f) + (a - 1.0f) * cosW0 - twoSqrtAAlpha
            }
            Type.PEAKING -> {
                tempB0 = 1.0f + alpha * a
                tempB1 = -2.0f * cosW0
                tempB2 = 1.0f - alpha * a
                tempA0 = 1.0f + alpha / a
                tempA1 = -2.0f * cosW0
                tempA2 = 1.0f - alpha / a
            }
            Type.HIGH_SHELF -> {
                val sqrtA = sqrt(a)
                val alphaShelf = (sinW0 / 2.0f) * sqrt((a + 1.0f / a) * (1.0f / q - 1.0f) + 2.0f).coerceAtLeast(0.001f)
                val twoSqrtAAlpha = 2.0f * sqrtA * alphaShelf

                tempB0 = a * ((a + 1.0f) + (a - 1.0f) * cosW0 + twoSqrtAAlpha)
                tempB1 = -2.0f * a * ((a - 1.0f) + (a + 1.0f) * cosW0)
                tempB2 = a * ((a + 1.0f) + (a - 1.0f) * cosW0 - twoSqrtAAlpha)
                tempA0 = (a + 1.0f) - (a - 1.0f) * cosW0 + twoSqrtAAlpha
                tempA1 = 2.0f * ((a - 1.0f) - (a + 1.0f) * cosW0)
                tempA2 = (a + 1.0f) - (a - 1.0f) * cosW0 - twoSqrtAAlpha
            }
        }

        // Normalize coefficients by a0
        val invA0 = 1.0f / tempA0
        b0 = tempB0 * invA0
        b1 = tempB1 * invA0
        b2 = tempB2 * invA0
        a1 = tempA1 * invA0
        a2 = tempA2 * invA0
    }

    /**
     * Resets delay buffers to avoid pops when engaging or changing parameters.
     */
    fun reset() {
        z1Left = 0.0f
        z2Left = 0.0f
        z1Right = 0.0f
        z2Right = 0.0f
    }

    /**
     * Processes a single Left channel sample.
     */
    fun processLeft(input: Float): Float {
        val out = b0 * input + z1Left
        z1Left = b1 * input - a1 * out + z2Left
        z2Left = b2 * input - a2 * out
        return out
    }

    /**
     * Processes a single Right channel sample.
     */
    fun processRight(input: Float): Float {
        val out = b0 * input + z1Right
        z1Right = b1 * input - a1 * out + z2Right
        z2Right = b2 * input - a2 * out
        return out
    }

    /**
     * Calculates the magnitude response in dB at a given frequency for curve visualization.
     */
    fun getMagnitudeAt(freq: Float): Float {
        if (currentGainDb == 0.0f) return 0.0f
        val w = (2.0 * Math.PI * freq / currentSampleRate).toFloat()
        val cosW = cos(w)
        val cos2W = cos(2.0f * w)
        val sinW = sin(w)
        val sin2W = sin(2.0f * w)

        val numReal = b0 + b1 * cosW + b2 * cos2W
        val numImag = -b1 * sinW - b2 * sin2W
        val denReal = 1.0f + a1 * cosW + a2 * cos2W
        val denImag = -a1 * sinW - a2 * sin2W

        val numMagSq = numReal * numReal + numImag * numImag
        val denMagSq = denReal * denReal + denImag * denImag

        if (denMagSq < 1e-12f) return 0.0f
        val mag = sqrt(numMagSq / denMagSq)
        return (20.0 * kotlin.math.log10(mag.toDouble().coerceAtLeast(1e-6))).toFloat()
    }
}
