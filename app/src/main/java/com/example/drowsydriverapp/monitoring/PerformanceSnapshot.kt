package com.example.drowsydriverapp.monitoring

/**
 * Snapshot of relevant runtime and device metrics that we want to expose
 * to the UI and logging pipelines.
 */
data class PerformanceSnapshot(
    val cpuPercent: Float = 0f,
    val fps: Float = 0f,
    val avgFrameMs: Float = 0f,
    val memoryUsageMb: Float = 0f,
    val heapUsageMb: Float = 0f,
    val batteryLevel: Int = -1,
    val batteryTemperatureC: Float = 0f,
    val isCharging: Boolean = false,
    val uptimeMs: Long = 0L,
    val timestamp: Long = 0L
)

