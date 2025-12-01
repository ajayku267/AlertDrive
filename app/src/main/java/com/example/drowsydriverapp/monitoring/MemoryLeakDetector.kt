package com.example.drowsydriverapp.monitoring

import android.os.Debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Performs very lightweight reference watching to hint at possible leaks.
 */
class MemoryLeakDetector(
    private val detectionDelayMs: Long = 5_000L,
    private val onLeak: (PerformanceEvent.MemoryLeak) -> Unit
) {
    private val scope = CoroutineScope(Job() + Dispatchers.Default)

    fun watch(target: Any, label: String) {
        val reference = WeakReference(target)
        scope.launch {
            delay(detectionDelayMs)
            System.gc()
            delay(250)
            if (reference.get() != null) {
                val retainedKb = Debug.getNativeHeapAllocatedSize() / 1024
                onLeak(
                    PerformanceEvent.MemoryLeak(
                        label = label,
                        retainedHeapKb = retainedKb,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }
    }
}

