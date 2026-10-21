package com.example.audio

import android.content.Context
import android.content.SharedPreferences

/**
 * Storage persistence manager using Android SharedPreferences.
 * Persists all user controls, DSP settings, and state across app launches.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var inputGainDb: Float
        get() = prefs.getFloat(KEY_INPUT_GAIN_DB, 0.0f)
        set(value) = prefs.edit().putFloat(KEY_INPUT_GAIN_DB, value).apply()

    var isLimiterActive: Boolean
        get() = prefs.getBoolean(KEY_IS_LIMITER_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_LIMITER_ACTIVE, value).apply()

    var isPassthroughActive: Boolean
        get() = prefs.getBoolean(KEY_IS_PASSTHROUGH_ACTIVE, true) // Default ON
        set(value) = prefs.edit().putBoolean(KEY_IS_PASSTHROUGH_ACTIVE, value).apply()

    var volumeOut: Float
        get() = prefs.getFloat(KEY_VOLUME_OUT, 0.5f) // Default 50%
        set(value) = prefs.edit().putFloat(KEY_VOLUME_OUT, value).apply()

    var isNoiseRemovalActive: Boolean
        get() = prefs.getBoolean(KEY_IS_NOISE_REMOVAL_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_NOISE_REMOVAL_ACTIVE, value).apply()

    var noiseRemovalThresholdDb: Float
        get() = prefs.getFloat(KEY_NOISE_THRESHOLD_DB, -42.0f) // Audible instrument gate threshold
        set(value) = prefs.edit().putFloat(KEY_NOISE_THRESHOLD_DB, value).apply()

    var lowShelfGainDb: Float
        get() = prefs.getFloat(KEY_LOW_SHELF_DB, 0.0f)
        set(value) = prefs.edit().putFloat(KEY_LOW_SHELF_DB, value).apply()

    var midPeakGainDb: Float
        get() = prefs.getFloat(KEY_MID_PEAK_DB, 0.0f)
        set(value) = prefs.edit().putFloat(KEY_MID_PEAK_DB, value).apply()

    var highShelfGainDb: Float
        get() = prefs.getFloat(KEY_HIGH_SHELF_DB, 0.0f)
        set(value) = prefs.edit().putFloat(KEY_HIGH_SHELF_DB, value).apply()

    var isEqBypassed: Boolean
        get() = prefs.getBoolean(KEY_IS_EQ_BYPASSED, true) // Default true (EQ disabled by default)
        set(value) = prefs.edit().putBoolean(KEY_IS_EQ_BYPASSED, value).apply()

    var isLeftMuted: Boolean
        get() = prefs.getBoolean(KEY_IS_LEFT_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_LEFT_MUTED, value).apply()

    var isRightMuted: Boolean
        get() = prefs.getBoolean(KEY_IS_RIGHT_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_RIGHT_MUTED, value).apply()

    companion object {
        private const val PREFS_NAME = "recoda_settings"
        private const val KEY_INPUT_GAIN_DB = "input_gain_db"
        private const val KEY_IS_LIMITER_ACTIVE = "is_limiter_active"
        private const val KEY_IS_PASSTHROUGH_ACTIVE = "is_passthrough_active"
        private const val KEY_VOLUME_OUT = "volume_out"
        private const val KEY_IS_NOISE_REMOVAL_ACTIVE = "is_noise_removal_active"
        private const val KEY_NOISE_THRESHOLD_DB = "noise_threshold_db"
        private const val KEY_LOW_SHELF_DB = "low_shelf_db"
        private const val KEY_MID_PEAK_DB = "mid_peak_db"
        private const val KEY_HIGH_SHELF_DB = "high_shelf_db"
        private const val KEY_IS_EQ_BYPASSED = "is_eq_bypassed"
        private const val KEY_IS_LEFT_MUTED = "is_left_muted"
        private const val KEY_IS_RIGHT_MUTED = "is_right_muted"
    }
}
