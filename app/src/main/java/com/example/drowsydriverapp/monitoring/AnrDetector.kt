package com.example.drowsydriverapp.monitoring

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Simple ANR watchdog that pings the main thread and reports stalls.
 */
class AnrDetector(
    private val timeoutMs: Long = 5_000L,
    private val onAnr: (PerformanceEvent.Anr) -> Unit
) {
    private val watchdogThread = HandlerThread("AnrWatchdog")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastResponse = AtomicLong(SystemClock.uptimeMillis())
    private val started = AtomicBoolean(false)
    private lateinit var watchdogHandler: Handler

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (!started.get()) return

            mainHandler.post {
                lastResponse.set(SystemClock.uptimeMillis())
            }

            watchdogHandler.postDelayed({
                val delta = SystemClock.uptimeMillis() - lastResponse.get()
                if (delta >= timeoutMs) {
                    val stackDump = Looper.getMainLooper().thread
                        .stackTrace
                        .joinToString(separator = "\n")
                    onAnr(
                        PerformanceEvent.Anr(
                            durationMs = delta,
                            stackTrace = stackDump,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
                watchdogHandler.postDelayed(this, timeoutMs)
            }, timeoutMs)
        }
    }

    fun start() {
        if (started.compareAndSet(false, true)) {
            watchdogThread.start()
            watchdogHandler = Handler(watchdogThread.looper)
            watchdogHandler.post(monitorRunnable)
        }
    }

    fun stop() {
        if (started.compareAndSet(true, false)) {
            watchdogThread.quitSafely()
        }
    }
}

