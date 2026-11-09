package com.perfmonitor

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Collects device performance data using only public Android APIs (no root, no helper app).
 * Frame rate of other apps cannot be read without elevated privileges, so it is not included.
 */
object PerfMonitor {

    data class Snapshot(
        val batteryTempC: Float? = null,
        val batteryPercent: Int? = null,
        val charging: Boolean = false,
        val thermal: String = "N/A",
        val deviceTempC: Float? = null,
        val ramUsedPercent: Int? = null,
        val foregroundApp: String? = null,
        val updatedAt: Long = 0L
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private var refs = 0
    private var executor: ScheduledExecutorService? = null
    private var lastForeground: String? = null
    private var lastEventQuery: Long = 0L

    @Synchronized
    fun acquire(context: Context) {
        refs++
        if (executor == null) {
            val app = context.applicationContext
            lastEventQuery = System.currentTimeMillis() - 60L * 60L * 1000L
            executor = Executors.newSingleThreadScheduledExecutor().also {
                it.scheduleWithFixedDelay({ sample(app) }, 0, 1, TimeUnit.SECONDS)
            }
        }
    }

    @Synchronized
    fun release() {
        refs = (refs - 1).coerceAtLeast(0)
        if (refs == 0) {
            executor?.shutdownNow()
            executor = null
        }
    }

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun sample(context: Context) {
        try {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memory = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memory)
            val ram = if (memory.totalMem > 0L) {
                (((memory.totalMem - memory.availMem) * 100L) / memory.totalMem).toInt()
            } else {
                null
            }

            _state.value = Snapshot(
                batteryTempC = if (tenths == Int.MIN_VALUE) null else tenths / 10f,
                batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
                charging = charging,
                thermal = readThermal(context),
                deviceTempC = readDeviceTemp(),
                ramUsedPercent = ram,
                foregroundApp = readForeground(context),
                updatedAt = System.currentTimeMillis()
            )
        } catch (e: Throwable) {
            // Keep the last good snapshot; the next tick will try again.
        }
    }

    private fun readThermal(context: Context): String {
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

    /** Highest plausible reading from the kernel thermal sensors, if the system exposes them. */
    private fun readDeviceTemp(): Float? {
        val zones = File("/sys/class/thermal").listFiles() ?: return null
        return zones
            .filter { it.name.startsWith("thermal_zone") }
            .mapNotNull { zone ->
                runCatching { File(zone, "temp").readText().trim().toFloat() }.getOrNull()
            }
            .map { if (it > 1000f) it / 1000f else it }
            .filter { it in 0f..120f }
            .maxOrNull()
    }

    private fun readForeground(context: Context): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastEventQuery, now)
        val event = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                latest = event.packageName
            }
        }
        lastEventQuery = now
        if (latest != null) lastForeground = latest

        val pkg = lastForeground ?: return null
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }
}
