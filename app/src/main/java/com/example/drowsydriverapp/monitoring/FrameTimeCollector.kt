package com.example.drowsydriverapp.monitoring

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uses Choreographer to keep track of recent frame durations/FPS.
 */
class FrameTimeCollector {
    private val handler = Handler(Looper.getMainLooper())
    private val isCollecting = AtomicBoolean(false)
    private var lastFrameNs: Long = 0L

    @Volatile
    var lastFrameMs: Float = 0f
        private set

    @Volatile
    private var framesInWindow: Int = 0

    @Volatile
    private var windowStartNs: Long = 0L

    @Volatile
    private var fpsValue: Float = 0f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isCollecting.get()) {
                return
            }
            if (lastFrameNs > 0L) {
                lastFrameMs = (frameTimeNanos - lastFrameNs) / 1_000_000f
            }
            lastFrameNs = frameTimeNanos

            if (windowStartNs == 0L) {
                windowStartNs = frameTimeNanos
            }

            framesInWindow++
            val windowDurationMs = (frameTimeNanos - windowStartNs) / 1_000_000f
            if (windowDurationMs >= 1_000f) {
                fpsValue = (framesInWindow / (windowDurationMs / 1_000f))
                windowStartNs = frameTimeNanos
                framesInWindow = 0
            }

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (isCollecting.compareAndSet(false, true)) {
            handler.post {
                lastFrameNs = 0L
                framesInWindow = 0
                windowStartNs = 0L
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        }
    }

    fun stop() {
        if (isCollecting.compareAndSet(true, false)) {
            handler.post {
                Choreographer.getInstance().removeFrameCallback(frameCallback)
            }
        }
    }

    fun fps(): Float = fpsValue
}

