package com.example.orgclock.ui.perf

import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.metrics.performance.JankStats

data class PerfSnapshot(
    val totalFrames: Long,
    val jankFrames: Long,
    val jankPercent: Float,
)

class PerformanceMonitor(window: Window) {
    private val jankStats = JankStats.createAndTrack(window) { frameData ->
        totalFrames += 1
        if (frameData.isJank) {
            jankFrames += 1
        }

        if (frameData.isJank || totalFrames % SNAPSHOT_INTERVAL == 0L) {
            snapshot = PerfSnapshot(
                totalFrames = totalFrames,
                jankFrames = jankFrames,
                jankPercent = if (totalFrames == 0L) 0f else (jankFrames.toFloat() / totalFrames.toFloat()) * 100f,
            )
        }
    }

    var snapshot by mutableStateOf(PerfSnapshot(0, 0, 0f))
        private set

    private var totalFrames: Long = 0
    private var jankFrames: Long = 0

    fun setTrackingEnabled(enabled: Boolean) {
        jankStats.isTrackingEnabled = enabled
    }

    fun reset() {
        totalFrames = 0
        jankFrames = 0
        snapshot = PerfSnapshot(0, 0, 0f)
    }

    companion object {
        private const val SNAPSHOT_INTERVAL = 90L
    }
}
