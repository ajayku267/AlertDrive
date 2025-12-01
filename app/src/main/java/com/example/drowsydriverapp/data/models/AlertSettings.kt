package com.example.drowsydriverapp.data.models

/**
 * User-tunable alert configuration for sound, haptics and thresholds.
 */
data class AlertSettings(
    val warningThreshold: Float = 0.4f,
    val severeThreshold: Float = 0.6f,
    val criticalThreshold: Float = 0.8f,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val voiceEnabled: Boolean = false,
    val languageCode: String = "en",
    val routeToBluetoothMedia: Boolean = true
)


