package com.example.drowsydriverapp.monitoring

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Captures uncaught exceptions, persists them for later inspection, and
 * forwards the event to the supplied callback.
 */
class CrashReporter(
    context: Context,
    private val onCrash: (PerformanceEvent.Crash) -> Unit
) : Thread.UncaughtExceptionHandler {
    private val appContext = context.applicationContext
    private val crashDir: File = File(appContext.filesDir, "crash-logs")
    private val formatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val installed = AtomicBoolean(false)
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    init {
        if (!crashDir.exists()) {
            crashDir.mkdirs()
        }
    }

    fun install() {
        if (installed.compareAndSet(false, true)) {
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(this)
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        persistCrash(thread.name, trace)
        onCrash(
            PerformanceEvent.Crash(
                threadName = thread.name,
                message = throwable.message.orEmpty(),
                stackTrace = trace,
                timestamp = System.currentTimeMillis()
            )
        )
        previousHandler?.uncaughtException(thread, throwable) ?: exitProcess(2)
    }

    private fun persistCrash(threadName: String, stackTrace: String) {
        val fileName = "crash_${formatter.format(Date())}.log"
        val crashFile = File(crashDir, fileName)
        crashFile.writeText(
            buildString {
                appendLine("Thread: $threadName")
                appendLine("Timestamp: ${Date()}")
                appendLine(stackTrace)
            }
        )
    }
}

