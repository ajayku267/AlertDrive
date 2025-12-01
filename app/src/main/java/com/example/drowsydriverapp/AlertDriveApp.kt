package com.example.drowsydriverapp

import android.app.Application
import com.example.drowsydriverapp.monitoring.PerformanceMonitor

/**
 * Custom application entry point that wires up performance monitoring as
 * early as possible in the process lifecycle.
 */
class AlertDriveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PerformanceMonitor.initialize(this)
    }
}

