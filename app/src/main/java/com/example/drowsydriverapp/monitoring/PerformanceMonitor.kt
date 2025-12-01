package com.example.drowsydriverapp.monitoring

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import kotlinx.coroutines.BufferOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central coordinator for all performance monitoring responsibilities.
 */
object PerformanceMonitor {
    data class Config(
        val metricsIntervalMs: Long = 2_000L,
        val anrTimeoutMs: Long = 5_000L,
        val leakDetectionDelayMs: Long = 6_000L
    )

    private val initialized = AtomicBoolean(false)
    private lateinit var appContext: Context
    private lateinit var config: Config

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _metrics = MutableStateFlow(PerformanceSnapshot())
    val metrics: StateFlow<PerformanceSnapshot> = _metrics.asStateFlow()

    private val _events = MutableSharedFlow<PerformanceEvent>(
        replay = 8,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<PerformanceEvent> = _events.asSharedFlow()

    private lateinit var crashReporter: CrashReporter
    private lateinit var anrDetector: AnrDetector
    private lateinit var leakDetector: MemoryLeakDetector
    private lateinit var batteryMonitor: BatteryMonitor
    private val frameCollector = FrameTimeCollector()
    private val cpuSampler = CpuSampler()
    private var batterySnapshot: BatterySnapshot? = null
    private var metricsJob: Job? = null
    private val logDirFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val perfLogDirName = "performance"

    fun initialize(context: Context, config: Config = Config()) {
        if (initialized.compareAndSet(false, true)) {
            this.config = config
            appContext = context.applicationContext
            batteryMonitor = BatteryMonitor(appContext)
            crashReporter = CrashReporter(appContext) { emitEvent(it) }.also { it.install() }
            leakDetector = MemoryLeakDetector(config.leakDetectionDelayMs) { emitEvent(it) }
            anrDetector = AnrDetector(config.anrTimeoutMs) { emitEvent(it) }.also { it.start() }
            frameCollector.start()
            watchBattery()
            startMetricsLoop()
        }
    }

    private fun watchBattery() {
        scope.launch {
            batteryMonitor.battery.collect { snapshot ->
                batterySnapshot = snapshot
                updateMetricsWithBattery(snapshot)
                maybeReportBatteryDrain(snapshot)
            }
        }
    }

    private fun maybeReportBatteryDrain(snapshot: BatterySnapshot) {
        val previous = _metrics.value.batteryLevel
        if (previous >= 0 && snapshot.level >= 0) {
            val drop = previous - snapshot.level
            if (!snapshot.isCharging && drop >= 5) {
                emitEvent(
                    PerformanceEvent.BatteryDrain(
                        level = snapshot.level,
                        temperatureC = snapshot.temperatureC,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private fun startMetricsLoop() {
        metricsJob?.cancel()
        metricsJob = scope.launch {
            while (true) {
                val runtime = Runtime.getRuntime()
                val totalMem = runtime.totalMemory()
                val freeMem = runtime.freeMemory()
                val usedMemMb = (totalMem - freeMem).toFloat() / MB_FACTOR
                val heapMb = Debug.getNativeHeapAllocatedSize().toFloat() / MB_FACTOR

                val battery = batterySnapshot

                _metrics.value = PerformanceSnapshot(
                    cpuPercent = cpuSampler.sample(),
                    fps = frameCollector.fps(),
                    avgFrameMs = frameCollector.lastFrameMs,
                    memoryUsageMb = usedMemMb,
                    heapUsageMb = heapMb,
                    batteryLevel = battery?.level ?: _metrics.value.batteryLevel,
                    batteryTemperatureC = battery?.temperatureC ?: _metrics.value.batteryTemperatureC,
                    isCharging = battery?.isCharging ?: _metrics.value.isCharging,
                    uptimeMs = SystemClock.uptimeMillis(),
                    timestamp = System.currentTimeMillis()
                )

                persistMetricsSnapshot(_metrics.value)
                delay(config.metricsIntervalMs)
            }
        }
    }

    private fun persistMetricsSnapshot(snapshot: PerformanceSnapshot) {
        val dir = File(appContext.filesDir, perfLogDirName).apply { if (!exists()) mkdirs() }
        val file = File(dir, "metrics_${logDirFormatter.format(Date())}.log")
        file.appendText(
            buildString {
                append(snapshot.timestamp)
                append(", cpu=")
                append(snapshot.cpuPercent)
                append(", fps=")
                append(snapshot.fps)
                append(", frameMs=")
                append(snapshot.avgFrameMs)
                append(", memMb=")
                append(snapshot.memoryUsageMb)
                append(", heapMb=")
                append(snapshot.heapUsageMb)
                append(", battery=")
                append(snapshot.batteryLevel)
                append(", tempC=")
                append(snapshot.batteryTemperatureC)
                append(", charging=")
                append(snapshot.isCharging)
                appendLine()
            }
        )
    }

    private fun updateMetricsWithBattery(snapshot: BatterySnapshot) {
        val current = _metrics.value
        _metrics.value = current.copy(
            batteryLevel = snapshot.level,
            batteryTemperatureC = snapshot.temperatureC,
            isCharging = snapshot.isCharging
        )
    }

    fun watchForLeaks(target: Any, label: String) {
        if (initialized.get()) {
            leakDetector.watch(target, label)
        }
    }

    fun emitEvent(event: PerformanceEvent) {
        _events.tryEmit(event)
    }

    fun recordCustomMetric(name: String, detail: String) {
        emitEvent(
            PerformanceEvent.MetricAlert(
                metricName = name,
                detail = detail,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    private const val MB_FACTOR = 1024f * 1024f
}

