package com.nlab.sysbattery

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.nlab.sysbattery.data.BatterySysfsReader
import com.nlab.sysbattery.data.AdvancedBatteryStatsReader
import com.nlab.sysbattery.data.RootAccessReader
import com.nlab.sysbattery.domain.BatteryStatsCalculator
import com.nlab.sysbattery.service.BatteryMonitorService
import rikka.shizuku.Shizuku
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var shizukuMessage by mutableStateOf<String?>(null)
    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == 7204) shizukuMessage = if (result == android.content.pm.PackageManager.PERMISSION_GRANTED)
            "Shizuku permission granted. Extra readings still depend on device access."
        else "Shizuku permission denied; standard readings remain available."
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        Shizuku.addRequestPermissionResultListener(shizukuListener)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            val state by BatteryMonitorService.state.collectAsState()
            val prefs = getSharedPreferences("sysbattery", MODE_PRIVATE)
            var running by remember { mutableStateOf(prefs.getBoolean("monitor_enabled", false)) }
            var advancedReport by remember { mutableStateOf<AdvancedBatteryStatsReader.Report?>(null) }
            var advancedError by remember { mutableStateOf<String?>(null) }
            var rootReport by remember { mutableStateOf<RootAccessReader.RootReport?>(null) }
            var rootLoading by remember { mutableStateOf(false) }
            SysBatteryTheme {
                BatteryDashboard(
                    sample = state.sample, rates = state.rates, running = running, shizukuMessage = shizukuMessage,
                    onStart = { BatteryMonitorService.start(this); running = true },
                    onStop = { BatteryMonitorService.stop(this); running = false },
                    onShizuku = {
                        when {
                            !Shizuku.pingBinder() -> shizukuMessage = "Shizuku is not running. Start it first."
                            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED ->
                                shizukuMessage = "Shizuku permission is already granted."
                            else -> Shizuku.requestPermission(7204)
                        }
                    },
                    onBackground = {
                        try {
                            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$packageName")
                            })
                        } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    },
                    onUsageAccess = {
                        try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                        catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    },
                    onCopyAdb = {
                        val commands = "adb shell pm grant com.nlab.sysbattery android.permission.BATTERY_STATS\nadb shell appops set com.nlab.sysbattery GET_USAGE_STATS allow"
                        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("SysBattery ADB commands", commands))
                        Toast.makeText(this, "ADB commands copied", Toast.LENGTH_SHORT).show()
                    },
                    advancedReport = advancedReport,
                    advancedError = advancedError,
                    rootReport = rootReport,
                    rootLoading = rootLoading,
                    onRefreshRoot = {
                        rootLoading = true
                        Thread {
                            val report = RootAccessReader().read()
                            runOnUiThread { rootReport = report; rootLoading = false }
                        }.start()
                    },
                    onRefreshAdvanced = {
                        try {
                            advancedReport = AdvancedBatteryStatsReader(this).read()
                            advancedError = null
                        } catch (e: Exception) {
                            advancedError = "Could not read advanced statistics: ${e.javaClass.simpleName}"
                        }
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
        super.onDestroy()
    }
}

@Composable
private fun SysBatteryTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(
            primary = Color(0xFFB8C8FF), secondary = Color(0xFF8CE0D0),
            background = Color(0xFF0A0F1E), surface = Color(0xFF171E30)
        ) else lightColorScheme(
            primary = Color(0xFF3459B8), secondary = Color(0xFF087F75),
            background = Color(0xFFF2F5FC), surface = Color.White
        ),
        content = content
    )
}

@Composable
private fun BatteryDashboard(
    sample: BatterySysfsReader.Snapshot?,
    rates: BatteryStatsCalculator.Rates?,
    running: Boolean,
    shizukuMessage: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onShizuku: () -> Unit,
    onBackground: () -> Unit,
    onUsageAccess: () -> Unit,
    onCopyAdb: () -> Unit,
    advancedReport: AdvancedBatteryStatsReader.Report?,
    advancedError: String?,
    rootReport: RootAccessReader.RootReport?,
    rootLoading: Boolean,
    onRefreshRoot: () -> Unit,
    onRefreshAdvanced: () -> Unit
) {
    val dark = isSystemInDarkTheme()
    val bg = if (dark) Color(0xFF090E1B) else Color(0xFFF1F4FC)
    val accent = if (dark) Color(0xFF172747) else Color(0xFFDCE6FF)
    val level = sample?.levelPercent
    val current = rates?.currentMilliAmpsMagnitude?.let { String.format(Locale.getDefault(), "%.0f mA", it) } ?: "—"
    val temp = sample?.temperatureTenthsCelsius?.let { String.format(Locale.getDefault(), "%.1f °C", it / 10.0) } ?: "—"
    val voltage = sample?.voltageMilliVolts?.let { "$it mV" } ?: "—"
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bg, accent, bg)))) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.size(50.dp).clip(RoundedCornerShape(17.dp)).background(
                    Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))
                ), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.BatteryChargingFull, null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("SysBattery", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Battery telemetry", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = CircleShape, color = if (running) Color(0xFF168264).copy(alpha = .15f) else MaterialTheme.colorScheme.surfaceVariant) {
                    Text(if (running) "LIVE" else "PAUSED", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
            GlassCard {
                Text("BATTERY LEVEL", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.6.sp)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(level?.toString() ?: "—", fontSize = 56.sp, fontWeight = FontWeight.Bold)
                            if (level != null) Text("%", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 9.dp))
                        }
                        Text(rates?.direction?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Waiting for readings", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.BatteryFull, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(66.dp))
                }
                LinearProgressIndicator(
                    progress = { ((level ?: 0).coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
                )
            }
            Text("Live readings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("Current", current, "↯", Modifier.weight(1f))
                MetricCard("Temperature", temp, "°", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("Voltage", voltage, "ϟ", Modifier.weight(1f))
                MetricCard("Drain rate", rates?.batteryPercentPerHour?.let { String.format(Locale.getDefault(), "%.2f%%/h", it) } ?: "Collecting…", "⌁", Modifier.weight(1f))
            }
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Background monitor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Persistent notification · 10-second updates", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(onClick = if (running) onStop else onStart, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Icon(if (running) Icons.Rounded.PowerSettingsNew else Icons.Rounded.Bolt, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (running) "Stop monitoring" else "Start monitoring")
                }
                OutlinedButton(onClick = onBackground, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Rounded.Security, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Allow background activity")
                }
            }
            GlassCard {
                Text("OEM background settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "ColorOS / OxygenOS: enable Auto-launch and allow background activity. HyperOS: set Battery saver to No restrictions and enable autostart if available. One UI: set battery usage to Unrestricted and remove SysBattery from Sleeping apps. Also allow notifications. Menu names vary by OS version.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium
                )
            }
            GlassCard {
                Text("Advanced battery statistics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Optional ADB setup for supported Android builds. BATTERY_STATS can expose system battery history when the OS accepts the grant. Usage access lets SysBattery correlate app foreground/background time; it does not itself provide per-app mAh attribution.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = onCopyAdb, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text("Copy ADB commands")
                }
                OutlinedButton(onClick = onUsageAccess, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text("Open Usage Access settings")
                }
                Text(
                    "On your computer, with USB debugging authorized, run the copied commands. If pm grant reports a permission/protection error, that Android build does not allow this grant to a regular app. Usage access may still need to be enabled manually in Settings.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
                )
            }
            GlassCard {
                Text("Battery attribution & app activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Reads Android BatteryUsageStats when available and UsageStats for the last 24 hours. App mAh is Android's estimate, not a direct electrical measurement; unsupported or denied data is reported rather than fabricated.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = onRefreshAdvanced, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text("Refresh advanced statistics")
                }
                advancedError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                advancedReport?.let { report ->
                    Text("Android total consumed power: ${report.totalConsumedMah?.let { String.format(Locale.getDefault(), "%.1f mAh", it) } ?: "unavailable"}")
                    report.batteryStatsError?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    report.apps.take(8).forEach { app ->
                        Text("${app.packageName ?: "UID ${app.uid}"} · ${String.format(Locale.getDefault(), "%.2f mAh", app.consumedMah)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Most-used apps (foreground time)", fontWeight = FontWeight.SemiBold)
                    report.usage.take(8).forEach { app ->
                        val minutes = app.foregroundMillis / 60_000L
                        Text("${app.packageName} · ${minutes / 60}h ${minutes % 60}m", style = MaterialTheme.typography.bodySmall)
                    }
                    report.usageStatsError?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                }
            }
            GlassCard {
                Text("Root access", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Optional for rooted devices. SysBattery requests root through su and only runs read-only battery-statistics and power-state diagnostics. Approve the superuser prompt in your root manager.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = onRefreshRoot, enabled = !rootLoading, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text(if (rootLoading) "Checking root…" else "Check root & read system stats")
                }
                rootReport?.let { report ->
                    Text(if (report.rooted) "Root granted · ${report.identity}" else "Root unavailable · ${report.identity}",
                        color = if (report.rooted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    report.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (report.findings.isNotEmpty()) {
                        Text("Parsed battery findings", fontWeight = FontWeight.SemiBold)
                        report.findings.forEach { finding ->
                            Text("${finding.label}: ${finding.value}", style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (report.rooted) {
                        Text("No recognized summary lines found in this Android/OEM output. Raw diagnostics are still available below.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    report.batteryStats?.let {
                        Text("BatteryStats output", fontWeight = FontWeight.SemiBold)
                        Text(it.take(4500), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    report.powerState?.let {
                        Text("Power-state output", fontWeight = FontWeight.SemiBold)
                        Text(it.take(2500), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedButton(onClick = onShizuku, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("Check Shizuku access") }
                shizukuMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                Text("Source: ${sample?.source ?: "waiting for first sample"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Readings depend on device and kernel support.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = if (dark) .78f else .82f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
    ) {
        Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun MetricCard(title: String, value: String, symbol: String, modifier: Modifier = Modifier) {
    Box(modifier) {
        GlassCard {
            Text(symbol, color = MaterialTheme.colorScheme.primary, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
