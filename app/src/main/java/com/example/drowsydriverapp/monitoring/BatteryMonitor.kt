package com.example.drowsydriverapp.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BatterySnapshot(
    val level: Int = -1,
    val temperatureC: Float = 0f,
    val isCharging: Boolean = false
)

/**
 * Listens to battery broadcasts and exposes the most recent reading.
 */
class BatteryMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val _battery = MutableStateFlow(BatterySnapshot())
    val battery: StateFlow<BatterySnapshot> = _battery.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent ?: return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val normalizedLevel = if (level >= 0 && scale > 0) {
                (level * 100) / scale
            } else {
                level
            }
            _battery.value = BatterySnapshot(
                level = normalizedLevel,
                temperatureC = temperature,
                isCharging = isCharging
            )
        }
    }

    init {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        appContext.registerReceiver(receiver, filter)
    }
}

