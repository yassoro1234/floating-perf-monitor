package com.perfmonitor

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var status: TextView

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply { textSize = 15f }
        root.addView(status)

        root.addView(button("1) Grant overlay permission") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        })

        root.addView(button("2) Grant Shizuku permission") {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Shizuku is not running", Toast.LENGTH_LONG).show()
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQ_SHIZUKU)
            } else {
                refreshStatus()
            }
        })

        root.addView(button("3) Start overlay") {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant overlay permission first", Toast.LENGTH_LONG).show()
            } else {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTIFY)
                }
                startForegroundService(Intent(this, OverlayService::class.java))
            }
        })

        root.addView(button("Stop overlay") {
            stopService(Intent(this, OverlayService::class.java))
        })

        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        Shizuku.addRequestPermissionResultListener(shizukuListener)
    }

    override fun onStop() {
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val overlay = if (Settings.canDrawOverlays(this)) "OK" else "missing"
        val shizuku = when {
            !Shizuku.pingBinder() -> "not running"
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "granted"
            else -> "not granted"
        }
        status.text = "Overlay permission: $overlay\nShizuku: $shizuku"
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_SHIZUKU = 1001
        private const val REQ_NOTIFY = 1002
    }
}
