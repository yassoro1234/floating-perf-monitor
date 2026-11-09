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
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Foreground service that shows a draggable floating bubble with performance data. */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var container: LinearLayout
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var appText: TextView
    private lateinit var fpsText: TextView
    private lateinit var tempText: TextView
    private lateinit var thermalText: TextView
    private var sampler: PerfSampler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        buildOverlay()
        sampler = PerfSampler(this) { render(it) }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        sampler?.stop()
        sampler = null
        if (::container.isInitialized) {
            runCatching { windowManager.removeView(container) }
        }
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

    private fun buildOverlay() {
        bubble = TextView(this).apply {
            text = "--"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(230, 20, 20, 30))
                setStroke(dp(2), Color.rgb(34, 211, 238))
            }
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        }

        appText = label()
        fpsText = label()
        tempText = label()
        thermalText = label()

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(230, 20, 20, 30))
            }
            addView(appText)
            addView(fpsText)
            addView(tempText)
            addView(thermalText)
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

        layoutParams = WindowManager.LayoutParams(
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
        windowManager.addView(container, layoutParams)
    }

    private fun label(): TextView = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        text = "--"
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
                    startX = layoutParams.x
                    startY = layoutParams.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    layoutParams.x = startX + dx
                    layoutParams.y = startY + dy
                    windowManager.updateViewLayout(container, layoutParams)
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

    private fun render(m: PerfSampler.Metrics) {
        if (!::container.isInitialized) return
        bubble.text = m.fps?.let { "%.0f".format(it) } ?: "--"
        appText.text = "App: ${m.app ?: "none"}"
        fpsText.text = when {
            !m.shizukuReady -> "FPS: needs Shizuku running and granted"
            m.fps == null -> "FPS: waiting for a game…"
            else -> "FPS: %.1f".format(m.fps)
        }
        tempText.text = "Battery temp: ${m.batteryTempC?.let { "%.1f °C".format(it) } ?: "N/A"}"
        thermalText.text = "Thermal: ${m.thermal}"
    }
}
