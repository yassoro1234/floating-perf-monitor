package com.perfmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Foreground service that shows a draggable floating bubble with live device data. */
class OverlayService : Service() {

    companion object {
        @Volatile
        var isRunning: Boolean = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var container: LinearLayout
    private lateinit var windowParams: WindowManager.LayoutParams
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var appText: TextView
    private lateinit var batteryText: TextView
    private lateinit var deviceText: TextView
    private lateinit var thermalText: TextView
    private lateinit var ramText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render(PerfMonitor.state.value)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        buildOverlay()
        PerfMonitor.acquire(this)
        isRunning = true
        handler.post(refresh)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        PerfMonitor.release()
        if (::container.isInitialized) {
            runCatching { windowManager.removeView(container) }
        }
        isRunning = false
        super.onDestroy()
    }

    private fun startAsForeground() {
        val channelId = "perf_overlay"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Performance overlay", NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Perf Monitor is running")
            .setContentText("Tap the floating bubble to see details")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(): TextView = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        setPadding(0, dp(2), 0, dp(2))
        text = "--"
    }

    private fun buildOverlay() {
        bubble = TextView(this).apply {
            text = "--"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 16, 22, 34))
                setStroke(dp(2), Color.rgb(34, 211, 238))
            }
            this.layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
        }

        appText = label()
        batteryText = label()
        deviceText = label()
        thermalText = label()
        ramText = label()

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(235, 16, 22, 34))
                setStroke(dp(1), Color.argb(90, 34, 211, 238))
            }
            addView(appText)
            addView(batteryText)
            addView(deviceText)
            addView(thermalText)
            addView(ramText)
            visibility = View.GONE
        }

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bubble)
            addView(panel)
        }

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(120)
        }

        setupDragAndTap()
        windowManager.addView(container, windowParams)
    }

    private fun setupDragAndTap() {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = windowParams.x
                    startY = windowParams.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    windowParams.x = startX + dx
                    windowParams.y = startY + dy
                    windowManager.updateViewLayout(container, windowParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        panel.visibility =
                            if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun render(s: PerfMonitor.Snapshot) {
        if (!::bubble.isInitialized) return
        bubble.text = s.batteryTempC?.let { "%.0f°".format(it) } ?: "--"
        appText.text = "App: ${s.foregroundApp ?: "unknown (grant usage access)"}"
        val batteryTemp = s.batteryTempC?.let { "%.1f °C".format(it) } ?: "—"
        val batteryLevel = s.batteryPercent?.let { "$it%" } ?: "—"
        val charging = if (s.charging) " ⚡" else ""
        batteryText.text = "Battery: $batteryTemp · $batteryLevel$charging"
        deviceText.text = "Device sensor: ${s.deviceTempC?.let { "%.1f °C".format(it) } ?: "n/a"}"
        thermalText.text = "Thermal: ${s.thermal}"
        ramText.text = "RAM used: ${s.ramUsedPercent?.let { "$it%" } ?: "—"}"
    }
}
