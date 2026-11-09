package com.perfmonitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Samples performance data once per second and reports it on the main thread. */
class PerfSampler(
    private val context: Context,
    private val onUpdate: (Metrics) -> Unit
) {

    data class Metrics(
        val fps: Float?,
        val app: String?,
        val batteryTempC: Float?,
        val thermal: String,
        val shizukuReady: Boolean
    )

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var lastPkg: String? = null
    private var lastFrames: Long = -1
    private var lastTimeMs: Long = 0

    fun start() {
        executor.scheduleWithFixedDelay({ tick() }, 0, 1000, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        executor.shutdownNow()
    }

    private fun tick() {
        try {
            val shizukuReady = ShizukuShell.isReady()
            var fps: Float? = null
            var app: String? = null

            if (shizukuReady) {
                app = ShizukuShell.foregroundPackage(context.packageName)
                if (app != null) {
                    val frames = ShizukuShell.totalFrames(app)
                    val now = SystemClock.elapsedRealtime()
                    if (frames != null) {
                        if (app == lastPkg && lastFrames >= 0 && frames >= lastFrames && now > lastTimeMs) {
                            fps = (frames - lastFrames) * 1000f / (now - lastTimeMs)
                        }
                        lastPkg = app
                        lastFrames = frames
                        lastTimeMs = now
                    }
                }
            }

            val metrics = Metrics(
                fps = fps,
                app = app,
                batteryTempC = readBatteryTemp(),
                thermal = readThermal(),
                shizukuReady = shizukuReady
            )
            main.post { onUpdate(metrics) }
        } catch (e: Throwable) {
            // Never let a failed sample kill the scheduler.
        }
    }

    private fun readBatteryTemp(): Float? {
        val intent: Intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10f
    }

    private fun readThermal(): String {
        if (Build.VERSION.SDK_INT < 29) return "N/A"
        val pm = context.getSystemService(PowerManager::class.java) ?: return "N/A"
        return when (pm.currentThermalStatus) {
            0 -> "Normal"
            1 -> "Light"
            2 -> "Moderate"
            3 -> "Severe"
            4 -> "Critical"
            5 -> "Emergency"
            6 -> "Shutdown"
            else -> "Unknown"
        }
    }
}
