package com.example.drowsydriverapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.drowsydriverapp.ml.FaceAnalyzer
import com.example.drowsydriverapp.ui.DrowsinessViewModel
import com.example.drowsydriverapp.ui.theme.DrowsyDriverAppTheme
import com.example.drowsydriverapp.data.models.DrowsinessState
import com.example.drowsydriverapp.data.models.AlertLevel
import com.example.drowsydriverapp.monitoring.PerformanceEvent
import com.example.drowsydriverapp.monitoring.PerformanceMonitor
import com.example.drowsydriverapp.monitoring.PerformanceSnapshot
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        requestCameraPermission()

        setContent {
            DrowsyDriverAppTheme {
                val drowsinessViewModel: DrowsinessViewModel by viewModels()
                val drowsinessState by drowsinessViewModel.drowsinessState.collectAsState()
                val performanceMetrics by PerformanceMonitor.metrics.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }
                var lastHandledEventTimestamp by remember { mutableLongStateOf(0L) }

                LaunchedEffect(Unit) {
                    PerformanceMonitor.events.collect { event ->
                        val eventTimestamp = event.timestampMillis()
                        if (eventTimestamp > lastHandledEventTimestamp) {
                            lastHandledEventTimestamp = eventTimestamp
                            snackbarHostState.showSnackbar(event.toMessage(), withDismissAction = true)
                        }
                    }
                }
                
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Scaffold(
                        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
                    ) { padding ->
                        MainContent(
                            previewView = previewView,
                            drowsinessState = drowsinessState,
                            performanceSnapshot = performanceMetrics,
                            modifier = Modifier.padding(padding)
                        )
                    }
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        val drowsinessViewModel: DrowsinessViewModel by viewModels()

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetResolution(Size(640, 480))
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also {
                    it.setAnalyzer(
                        ContextCompat.getMainExecutor(this),
                        FaceAnalyzer(drowsinessViewModel)
                    )
                }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )

                preview.setSurfaceProvider(previewView.surfaceProvider)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun requestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                startCamera()
            }
            else -> {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    REQUEST_CODE_PERMISSIONS
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        PerformanceMonitor.watchForLeaks(this, "MainActivity")
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
    }
}

@Composable
fun MainContent(
    previewView: PreviewView,
    drowsinessState: DrowsinessState,
    performanceSnapshot: PerformanceSnapshot,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.weight(1f)
        )
        
        Text(
            text = drowsinessState.message,
            style = MaterialTheme.typography.bodyLarge,
            color = when (drowsinessState.alertLevel) {
                AlertLevel.CRITICAL -> MaterialTheme.colorScheme.error
                AlertLevel.SEVERE -> MaterialTheme.colorScheme.errorContainer
                AlertLevel.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.onBackground
            },
            modifier = Modifier.padding(16.dp)
        )

        PerformancePanel(
            metrics = performanceSnapshot,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun PerformancePanel(
    metrics: PerformanceSnapshot,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium
    ) {
        val cpuText = if (metrics.cpuPercent <= 0f) "--" else metrics.cpuPercent.format() + "%"
        val fpsText = if (metrics.fps <= 0f) "--" else metrics.fps.format()
        val frameText = if (metrics.avgFrameMs <= 0f) "--" else metrics.avgFrameMs.format() + " ms"
        val memText = if (metrics.memoryUsageMb <= 0f) "--" else metrics.memoryUsageMb.format() + " MB"
        val hasBattery = metrics.batteryLevel >= 0
        val batteryText = if (hasBattery) "${metrics.batteryLevel}%" else "--"
        val tempText = if (hasBattery) metrics.batteryTemperatureC.format() + "°C" else "--"

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Performance Insights",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "CPU: $cpuText | FPS: $fpsText",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = "Frame: $frameText | Mem: $memText",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Battery: $batteryText @ $tempText",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun Float.format(): String =
    if (this == 0f) "0" else String.format(java.util.Locale.US, "%.1f", this)

private fun PerformanceEvent.timestampMillis(): Long = when (this) {
    is PerformanceEvent.Crash -> timestamp
    is PerformanceEvent.Anr -> timestamp
    is PerformanceEvent.MemoryLeak -> timestamp
    is PerformanceEvent.BatteryDrain -> timestamp
    is PerformanceEvent.MetricAlert -> timestamp
}

private fun PerformanceEvent.toMessage(): String = when (this) {
    is PerformanceEvent.Crash -> "Crash captured: $message"
    is PerformanceEvent.Anr -> "ANR detected (${durationMs}ms stall)"
    is PerformanceEvent.MemoryLeak -> "Possible leak: $label retained ${retainedHeapKb}kb"
    is PerformanceEvent.BatteryDrain -> "Battery drop to $level% (${temperatureC}°C)"
    is PerformanceEvent.MetricAlert -> "$metricName: $detail"
}