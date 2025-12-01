package com.example.drowsydriverapp.monitoring

import android.os.Process
import android.os.SystemClock

/**
 * Lightweight CPU sampler that estimates process level CPU utilization.
 */
class CpuSampler {
    private var lastCpuTimeMs: Long = Process.getElapsedCpuTime()
    private var lastWallTimeMs: Long = SystemClock.elapsedRealtime()

    fun sample(): Float {
        val currentCpu = Process.getElapsedCpuTime()
        val currentWall = SystemClock.elapsedRealtime()

        val cpuDelta = currentCpu - lastCpuTimeMs
        val wallDelta = currentWall - lastWallTimeMs

        lastCpuTimeMs = currentCpu
        lastWallTimeMs = currentWall

        if (wallDelta <= 0) return 0f

        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val utilization = (cpuDelta.toFloat() / wallDelta.toFloat()) * (100f / cores)

        return utilization.coerceIn(0f, 100f)
    }
}

