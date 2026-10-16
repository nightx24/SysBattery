package com.nlab.sysbattery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.nlab.sysbattery.data.BatterySysfsReader
import com.nlab.sysbattery.data.AdvancedBatteryStatsReader
import com.nlab.sysbattery.data.RootAccessReader
import com.nlab.sysbattery.data.ShizukuBatteryStatsReader
import com.nlab.sysbattery.data.RootBatteryStatsParser
import com.nlab.sysbattery.domain.BatteryStatsCalculator
import com.nlab.sysbattery.service.BatteryMonitorService
import java.util.Locale
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private var shizukuStatus by mutableStateOf("Checking Shizuku…")
    private var shizukuFindings by mutableStateOf<List<RootBatteryStatsParser.Finding>>(emptyList())
    private var shizukuLoading by mutableStateOf(false)
    private var shizukuAuthorized by mutableStateOf(false)
    private val shizukuRequestCode = 0x5B24
    private val shizukuPermissionListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode != shizukuRequestCode) return
            Shizuku.removeRequestPermissionResultListener(this)
            runOnUiThread {
                shizukuAuthorized = grantResult == PackageManager.PERMISSION_GRANTED
                shizukuStatus = if (shizukuAuthorized) "Authorized · advanced statistics available"
                    else "Permission denied · standard monitoring remains available"
            }
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        refreshShizukuStatus()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            val state by BatteryMonitorService.state.collectAsState()
            val prefs = getSharedPreferences("sysbattery", MODE_PRIVATE)
            var running by remember { mutableStateOf(prefs.getBoolean("monitor_enabled", false)) }
            var themeMode by remember { mutableStateOf(prefs.getString("theme_mode", "System default") ?: "System default") }
            var preferencesRevision by remember { mutableIntStateOf(0) }
            var advancedReport by remember { mutableStateOf<AdvancedBatteryStatsReader.Report?>(null) }
            var advancedError by remember { mutableStateOf<String?>(null) }
            var rootReport by remember { mutableStateOf<RootAccessReader.RootReport?>(null) }
            var rootLoading by remember { mutableStateOf(false) }
            SysBatteryTheme(themeMode) {
                BatteryDashboard(
                    sample = state.sample, rates = state.rates, running = running,
                    onStart = { BatteryMonitorService.start(this); running = true },
                    onStop = { BatteryMonitorService.stop(this); running = false },
                    onMonitorEnabledChange = { enabled ->
                        if (enabled) {
                            BatteryMonitorService.start(this)
                            running = true
                        } else {
                            BatteryMonitorService.stop(this)
                            running = false
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
                    settingsPrefs = prefs,
                    onThemeModeChange = { themeMode = it },
                    onPreferencesChanged = { preferencesRevision++ },
                    preferencesRevision = preferencesRevision,
                    shizukuStatus = shizukuStatus,
                    shizukuAuthorized = shizukuAuthorized,
                    shizukuFindings = shizukuFindings,
                    shizukuLoading = shizukuLoading,
                    onRequestShizuku = { requestShizukuAccess() },
                    onRefreshAdvanced = {
                        shizukuLoading = true
                        Thread {
                            var findings = emptyList<RootBatteryStatsParser.Finding>()
                            var shizukuError: String? = null
                            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                                try {
                                    findings = ShizukuBatteryStatsReader().read().findings
                                } catch (e: Exception) {
                                    shizukuError = e.message ?: e.javaClass.simpleName
                                }
                            }
                            var report: AdvancedBatteryStatsReader.Report? = null
                            var reportError: String? = null
                            try {
                                report = AdvancedBatteryStatsReader(this).read()
                            } catch (e: Exception) {
                                reportError = "Could not read standard statistics: ${e.javaClass.simpleName}"
                            }
                            runOnUiThread {
                                advancedReport = report
                                advancedError = reportError
                                shizukuFindings = findings
                                if (shizukuError != null && findings.isEmpty()) {
                                    advancedError = listOfNotNull(reportError, "Shizuku: $shizukuError").joinToString("\n")
                                }
                                shizukuLoading = false
                            }
                        }.start()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshShizukuStatus()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    private fun refreshShizukuStatus() {
        val available = try {
            packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
        shizukuAuthorized = Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        shizukuStatus = when {
            shizukuAuthorized -> "Authorized · advanced statistics available"
            Shizuku.pingBinder() -> "Shizuku running · permission needed"
            available -> "Shizuku installed · service not running"
            else -> "Shizuku not installed · optional"
        }
    }

    private fun requestShizukuAccess() {
        if (!Shizuku.pingBinder()) {
            val launchIntent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (launchIntent != null) {
                startActivity(launchIntent)
                shizukuStatus = "Open Shizuku and start its service, then return here"
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases")))
                shizukuStatus = "Install Shizuku, start its service, then return here"
            }
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            shizukuAuthorized = true
            shizukuStatus = "Authorized · advanced statistics available"
            return
        }
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.requestPermission(shizukuRequestCode)
    }
}

@Composable
private fun SysBatteryTheme(themeMode: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        "Light" -> false
        "Dark" -> true
        else -> systemDark
    }
    val fallbackScheme = if (dark) darkColorScheme(
        primary = Color(0xFFB8C8FF), secondary = Color(0xFF8CE0D0),
        background = Color(0xFF101318), surface = Color(0xFF191D23)
    ) else lightColorScheme(
        primary = Color(0xFF3459B8), secondary = Color(0xFF087F75),
        background = Color(0xFFF7F7FF), surface = Color(0xFFFFFFFF)
    )
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } catch (_: Exception) {
            fallbackScheme
        }
    } else {
        fallbackScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatteryDashboard(
    sample: BatterySysfsReader.Snapshot?,
    rates: BatteryStatsCalculator.Rates?,
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBackground: () -> Unit,
    onUsageAccess: () -> Unit,
    advancedReport: AdvancedBatteryStatsReader.Report?,
    advancedError: String?,
    rootReport: RootAccessReader.RootReport?,
    rootLoading: Boolean,
    onRefreshRoot: () -> Unit,
    onRefreshAdvanced: () -> Unit,
    shizukuStatus: String,
    shizukuAuthorized: Boolean,
    shizukuFindings: List<RootBatteryStatsParser.Finding>,
    shizukuLoading: Boolean,
    onRequestShizuku: () -> Unit,
    shizukuStatus: String,
    shizukuAuthorized: Boolean,
    shizukuFindings: List<RootBatteryStatsParser.Finding>,
    shizukuLoading: Boolean,
    onRequestShizuku: () -> Unit,
    onMonitorEnabledChange: (Boolean) -> Unit,
    onThemeModeChange: (String) -> Unit,
    onPreferencesChanged: () -> Unit,
    preferencesRevision: Int,
    settingsPrefs: android.content.SharedPreferences
) {
    val level = sample?.levelPercent
    val temperatureUnit = remember(preferencesRevision) {
        settingsPrefs.getString("temperature_unit", "Celsius (°C)") ?: "Celsius (°C)"
    }
    val current = rates?.currentMilliAmpsMagnitude?.let { String.format(Locale.getDefault(), "%.0f mA", it) } ?: "—"
    val temp = sample?.temperatureTenthsCelsius?.let {
        val celsius = it / 10.0
        val fahrenheit = celsius * 9.0 / 5.0 + 32.0
        if (temperatureUnit == "Fahrenheit (°F)") String.format(Locale.getDefault(), "%.1f °F", fahrenheit)
        else String.format(Locale.getDefault(), "%.1f °C", celsius)
    } ?: "—"
    val voltage = sample?.voltageMilliVolts?.let { "$it mV" } ?: "—"
    var selectedTab by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
            PrimaryTabRow(selectedTabIndex = selectedTab, modifier = Modifier.fillMaxWidth()) {
                listOf("Status", "App usage", "Options").forEachIndexed { index, label ->
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(label) })
                }
            }
            if (selectedTab == 0) {
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
            }
            if (selectedTab == 2) {
            GlassCard {
                Text("OEM background settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "ColorOS / OxygenOS: enable Auto-launch and allow background activity. HyperOS: set Battery saver to No restrictions and enable autostart if available. One UI: set battery usage to Unrestricted and remove SysBattery from Sleeping apps. Also allow notifications. Menu names vary by OS version.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium
                )
            }
            }
            if (selectedTab == 1) {
            GlassCard {
                Text("Advanced battery statistics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "SysBattery uses public battery APIs for live readings and Usage Access for app foreground time. Android restricts per-app power attribution on many production builds; if access is denied, the app reports that limitation instead of showing invented mAh values.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = onUsageAccess, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text("Open Usage Access settings")
                }
                Text(
                    "No privileged ADB grant is required for live battery level, voltage, temperature, and supported current readings. Usage Access is managed in Android Settings; ADB may be blocked from changing it on some OEM builds.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
                )
            }
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Shizuku advanced access", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(shizukuStatus, color = if (shizukuAuthorized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Surface(shape = CircleShape, color = if (shizukuAuthorized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                        Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.padding(10.dp),
                            tint = if (shizukuAuthorized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("Optional. Shizuku can run selected read-only BatteryStats diagnostics with shell privileges. Standard battery monitoring works without it.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Button(onClick = onRequestShizuku, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text(when {
                        shizukuAuthorized -> "Shizuku authorized"
                        shizukuStatus.contains("not installed", ignoreCase = true) || shizukuStatus.contains("installed · service not running", ignoreCase = true) -> "Open / install Shizuku"
                        else -> "Enable Shizuku access"
                    })
                }
                if (shizukuAuthorized) {
                    OutlinedButton(onClick = onRefreshAdvanced, enabled = !shizukuLoading, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Text(if (shizukuLoading) "Reading BatteryStats…" else "Read privileged BatteryStats")
                    }
                    shizukuFindings.take(20).forEach { finding ->
                        Text("${finding.label}: ${finding.value}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (shizukuFindings.isEmpty() && !shizukuLoading) Text("No Shizuku report loaded yet. Tap Read privileged BatteryStats.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
            }
            if (selectedTab == 2) {
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
                    report.batteryHistory?.let {
                        Text("Battery history (raw)", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (it.isBlank()) "History unavailable on this device/build." else it.take(2500),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text("Source: ${sample?.source ?: "waiting for first sample"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            }
            if (selectedTab == 2) {
            BatteryMonitorSettings(
                prefs = settingsPrefs,
                running = running,
                onMonitorEnabledChange = onMonitorEnabledChange,
                onThemeModeChange = onThemeModeChange,
                onPreferencesChanged = onPreferencesChanged,
                preferencesRevision = preferencesRevision
            )
            }
            if (selectedTab == 0) Text("Readings depend on device and kernel support.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BatteryMonitorSettings(
    prefs: android.content.SharedPreferences,
    running: Boolean,
    onMonitorEnabledChange: (Boolean) -> Unit,
    onThemeModeChange: (String) -> Unit,
    onPreferencesChanged: () -> Unit,
    preferencesRevision: Int
) {
    val context = LocalContext.current
    var serviceEnabled by remember { mutableStateOf(running) }
    LaunchedEffect(running) { serviceEnabled = running }
    var resetAtThreshold by remember { mutableStateOf(prefs.getBoolean("reset_at_threshold", true)) }
    var resetOnPlug by remember { mutableStateOf(prefs.getBoolean("reset_on_plug", true)) }
    var resetOnReboot by remember { mutableStateOf(prefs.getBoolean("reset_on_reboot", true)) }
    var idleWarning by remember { mutableStateOf(prefs.getBoolean("idle_warning", true)) }
    var showBatteryIcon by remember { mutableStateOf(prefs.getBoolean("show_battery_icon", false)) }
    var showChargingPower by remember { mutableStateOf(prefs.getBoolean("show_charging_power", true)) }
    var threshold by remember { mutableFloatStateOf(prefs.getInt("reset_threshold_percent", 90).toFloat()) }
    var warning by remember { mutableFloatStateOf(prefs.getInt("idle_warning_percent", 5).toFloat()) }
    var temperatureUnit by remember(preferencesRevision) {
        mutableStateOf(prefs.getString("temperature_unit", "Celsius (°C)") ?: "Celsius (°C)")
    }
    var theme by remember(preferencesRevision) {
        mutableStateOf(prefs.getString("theme_mode", "System default") ?: "System default")
    }
    var showResetConfirmation by remember { mutableStateOf(false) }
    fun saveBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    GlassCard {
        Text("Monitor settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Customize tracking, reset rules, notifications and appearance.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        SettingSwitch("Battery monitor service", "Keep collecting while the app is in the background.", serviceEnabled) {
            serviceEnabled = it
            saveBoolean("monitor_enabled", it)
            onMonitorEnabledChange(it)
        }
        SettingSwitch("Reset stats at battery threshold", "Reset session counters when charging reaches the selected level.", resetAtThreshold) {
            resetAtThreshold = it; saveBoolean("reset_at_threshold", it)
        }
        Text("Reset threshold: ${threshold.toInt()}%", style = MaterialTheme.typography.bodyMedium)
        Slider(value = threshold, onValueChange = { threshold = it }, valueRange = 50f..100f, steps = 9,
            onValueChangeFinished = { prefs.edit().putInt("reset_threshold_percent", threshold.toInt()).apply() },
            enabled = resetAtThreshold)
        SettingSwitch("Reset stats when plugged in", "Start a fresh session on charge connection.", resetOnPlug) {
            resetOnPlug = it; saveBoolean("reset_on_plug", it)
        }
        SettingSwitch("Reset stats after reboot or shutdown", "Keep session history separated by device restarts.", resetOnReboot) {
            resetOnReboot = it; saveBoolean("reset_on_reboot", it)
        }
        SettingSwitch("Warn about high idle drain", "Show a warning when measured idle drain exceeds your limit.", idleWarning) {
            idleWarning = it; saveBoolean("idle_warning", it)
        }
        Text("Idle drain warning: ${warning.toInt()}% per hour", style = MaterialTheme.typography.bodyMedium)
        Slider(value = warning, onValueChange = { warning = it }, valueRange = 1f..20f, steps = 18,
            onValueChangeFinished = { prefs.edit().putInt("idle_warning_percent", warning.toInt()).apply() },
            enabled = idleWarning)
        HorizontalDivider()
        Text("Battery temperature unit", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = temperatureUnit == "Celsius (°C)", onClick = {
                temperatureUnit = "Celsius (°C)"; prefs.edit().putString("temperature_unit", temperatureUnit).apply()
                onPreferencesChanged()
            }, label = { Text("°C") })
            FilterChip(selected = temperatureUnit == "Fahrenheit (°F)", onClick = {
                temperatureUnit = "Fahrenheit (°F)"; prefs.edit().putString("temperature_unit", temperatureUnit).apply()
                onPreferencesChanged()
            }, label = { Text("°F") })
        }
        SettingSwitch("Show battery percentage in notification", "Enable the percentage indicator preference.", showBatteryIcon) {
            showBatteryIcon = it; saveBoolean("show_battery_icon", it)
        }
        SettingSwitch("Show charging amperage and wattage", "Display live electrical readings when supported by the device.", showChargingPower) {
            showChargingPower = it; saveBoolean("show_charging_power", it)
        }
        HorizontalDivider()
        Text("Appearance", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("System default", "Light", "Dark").forEach { mode ->
                FilterChip(selected = theme == mode, onClick = {
                    theme = mode
                    prefs.edit().putString("theme_mode", mode).apply()
                    onThemeModeChange(mode)
                    onPreferencesChanged()
                }, label = { Text(mode) })
            }
        }
        OutlinedButton(onClick = { showResetConfirmation = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Reset stats")
        }
        if (showResetConfirmation) {
            AlertDialog(
                onDismissRequest = { showResetConfirmation = false },
                title = { Text("Reset battery statistics?") },
                text = { Text("This clears SysBattery's locally stored session counters. Android system battery history is not modified.") },
                confirmButton = {
                    TextButton(onClick = {
                        BatteryMonitorService.resetStoredCounters(context)
                        showResetConfirmation = false
                        onPreferencesChanged()
                    }) { Text("Reset") }
                },
                dismissButton = { TextButton(onClick = { showResetConfirmation = false }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
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
