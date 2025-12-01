package com.example.drowsydriverapp.monitoring

/**
 * Captures noteworthy performance events so that they can be surfaced to
 * analytics, logs, or debugging UI.
 */
sealed class PerformanceEvent {
    data class Crash(
        val threadName: String,
        val message: String,
        val stackTrace: String,
        val timestamp: Long
    ) : PerformanceEvent()

    data class Anr(
        val durationMs: Long,
        val stackTrace: String,
        val timestamp: Long
    ) : PerformanceEvent()

    data class MemoryLeak(
        val label: String,
        val retainedHeapKb: Long,
        val timestamp: Long
    ) : PerformanceEvent()

    data class BatteryDrain(
        val level: Int,
        val temperatureC: Float,
        val timestamp: Long
    ) : PerformanceEvent()

    data class MetricAlert(
        val metricName: String,
        val detail: String,
        val timestamp: Long
    ) : PerformanceEvent()
}

