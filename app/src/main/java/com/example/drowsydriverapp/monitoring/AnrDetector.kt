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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastResponse = AtomicLong(SystemClock.uptimeMillis())
    private val started = AtomicBoolean(false)
    @Volatile
    private var watchdogThread: HandlerThread? = null
    @Volatile
    private var watchdogHandler: Handler? = null

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (!started.get()) return

            mainHandler.post {
                lastResponse.set(SystemClock.uptimeMillis())
            }

            val handler = watchdogHandler ?: return
            handler.postDelayed({
                if (!started.get()) return@postDelayed

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
                if (started.get()) {
                    watchdogHandler?.postDelayed(this, timeoutMs)
                }
            }, timeoutMs)
        }
    }

    fun start() {
        if (started.compareAndSet(false, true)) {
            val thread = HandlerThread("AnrWatchdog").also { it.start() }
            watchdogThread = thread
            watchdogHandler = Handler(thread.looper)
            watchdogHandler?.post(monitorRunnable)
        }
    }

    fun stop() {
        if (started.compareAndSet(true, false)) {
            watchdogHandler?.removeCallbacksAndMessages(null)
            watchdogThread?.quitSafely()
            watchdogHandler = null
            watchdogThread = null
        }
    }
}

