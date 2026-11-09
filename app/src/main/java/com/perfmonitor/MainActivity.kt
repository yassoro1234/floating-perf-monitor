package com.perfmonitor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Accent = Color(0xFF22D3EE)
private val BackgroundColor = Color(0xFF0B0F17)
private val CardColor = Color(0xFF151C28)
private val TileColor = Color(0xFF1C2535)
private val TextDim = Color(0xFF8A96A8)
private val GoodColor = Color(0xFF34D399)
private val WarnColor = Color(0xFFFBBF24)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PerfTheme { Dashboard() }
        }
    }
}

@Composable
private fun PerfTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF00121A),
            background = BackgroundColor,
            surface = CardColor,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

@Composable
private fun Dashboard() {
    val context = LocalContext.current
    val snap by PerfMonitor.state.collectAsState()
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var usageGranted by remember { mutableStateOf(PerfMonitor.hasUsageAccess(context)) }
    var overlayRunning by remember { mutableStateOf(OverlayService.isRunning) }

    DisposableEffect(Unit) {
        PerfMonitor.acquire(context)
        onDispose { PerfMonitor.release() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            overlayGranted = Settings.canDrawOverlays(context)
            usageGranted = PerfMonitor.hasUsageAccess(context)
            overlayRunning = OverlayService.isRunning
            delay(1000L)
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Header(overlayRunning) }

            item {
                InfoCard("Permissions") {
                    PermissionRow("Display over other apps", overlayGranted) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                    PermissionRow("Usage access (current app)", usageGranted) {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        toggleOverlay(context, overlayRunning, overlayGranted)
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        if (overlayRunning) "Stop floating monitor" else "Start floating monitor",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            item {
                InfoCard("Live") {
                    StatsGrid(snap)
                }
            }

            item {
                Text(
                    "FPS of other apps needs elevated privileges (root or a helper), so it is not shown.",
                    color = TextDim,
                    fontSize = 12.sp
                )
            }
        }
    }
}

private fun toggleOverlay(context: Context, running: Boolean, granted: Boolean) {
    val service = Intent(context, OverlayService::class.java)
    when {
        running -> context.stopService(service)
        !granted -> Toast.makeText(context, "Grant display permission first", Toast.LENGTH_SHORT).show()
        else -> context.startForegroundService(service)
    }
}

@Composable
private fun Header(running: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Perf Monitor", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            if (running) "Monitor is running" else "Monitor is stopped",
            color = if (running) GoodColor else TextDim,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = CardColor
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onGrant: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp)
            Text(
                if (granted) "Granted" else "Required",
                color = if (granted) GoodColor else WarnColor,
                fontSize = 12.sp
            )
        }
        if (!granted) {
            TextButton(onClick = onGrant) { Text("Grant") }
        }
    }
}

@Composable
private fun StatsGrid(s: PerfMonitor.Snapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                "Battery temp",
                s.batteryTempC?.let { "%.1f °C".format(it) } ?: "—",
                Modifier.weight(1f)
            )
            StatTile(
                "Device sensor",
                s.deviceTempC?.let { "%.1f °C".format(it) } ?: "—",
                Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Thermal", s.thermal, Modifier.weight(1f))
            StatTile("RAM used", s.ramUsedPercent?.let { "$it%" } ?: "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                "Battery",
                (s.batteryPercent?.let { "$it%" } ?: "—") + if (s.charging) " ⚡" else "",
                Modifier.weight(1f)
            )
            StatTile("FPS", "N/A", Modifier.weight(1f))
        }
        StatTile(
            "Foreground app",
            s.foregroundApp ?: "Grant usage access",
            Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(TileColor, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(label, color = TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
