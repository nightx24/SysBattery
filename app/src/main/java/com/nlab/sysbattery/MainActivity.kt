package com.nlab.sysbattery

import android.Manifest
import android.app.AppOpsManager
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import com.nlab.sysbattery.diagnostics.DiagnosticLogger
import android.widget.Toast
import com.nlab.sysbattery.data.AdvancedBatteryStatsReader
import com.nlab.sysbattery.data.RootAccessReader
import com.nlab.sysbattery.data.ShizukuBatteryStatsReader
import com.nlab.sysbattery.data.RootBatteryStatsParser
import com.nlab.sysbattery.data.shizuku.BatteryStatsUserService
import com.nlab.sysbattery.data.shizuku.IBatteryStatsUserService
import com.nlab.sysbattery.domain.BatteryStatsCalculator
import com.nlab.sysbattery.service.BatteryMonitorService
import java.util.Locale
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private var shizukuStatus by mutableStateOf("Checking Shizuku…")
    private var shizukuFindings by mutableStateOf<List<RootBatteryStatsParser.Finding>>(emptyList())
    private var shizukuLoading by mutableStateOf(false)
    private var showDiagnostics by mutableStateOf(false)
    private var usageAccessGranted by mutableStateOf(false)
    private var batteryStatsPermissionGranted by mutableStateOf(false)
    private val exportDiagnostics = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(DiagnosticLogger.readLogs()) }
            Toast.makeText(this, "Diagnostics exported", Toast.LENGTH_SHORT).show()
        }.onFailure { DiagnosticLogger.error("Diagnostics export failed", it); Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show() }
    }
    private var shizukuAuthorized by mutableStateOf(false)
    private var shizukuServiceBinding = false
    private var shizukuService: IBatteryStatsUserService? = null
    private val shizukuUserServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, BatteryStatsUserService::class.java))
            .daemon(false)
            .tag("sysbattery-battery-stats")
            .version(1)
    }
    private val shizukuServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shizukuService = service?.let { IBatteryStatsUserService.Stub.asInterface(it) }
            shizukuServiceBinding = false
            runOnUiThread {
                shizukuStatus = if (shizukuService != null) {
                    "Authorized · BatteryStats service connected"
                } else {
                    "Authorized · could not connect to BatteryStats service"
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shizukuService = null
            shizukuServiceBinding = false
            runOnUiThread {
                shizukuStatus = if (shizukuAuthorized) "Authorized · reconnect required" else "Shizuku disconnected"
            }
        }
    }
    private val shizukuRequestCode = 0x5B24
    private val shizukuPermissionListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode != shizukuRequestCode) return
            Shizuku.removeRequestPermissionResultListener(this)
            runOnUiThread {
                shizukuAuthorized = grantResult == PackageManager.PERMISSION_GRANTED
                shizukuStatus = if (shizukuAuthorized) "Authorized · connecting to BatteryStats service"
                    else "Permission denied · standard monitoring remains available"
                if (shizukuAuthorized) bindShizukuService()
            }
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun hasBatteryStatsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BATTERY_STATS) == PackageManager.PERMISSION_GRANTED

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java) ?: return false
        return appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        DiagnosticLogger.initialize(applicationContext)
        DiagnosticLogger.info("MainActivity created")
        usageAccessGranted = hasUsageAccess()
        batteryStatsPermissionGranted = hasBatteryStatsPermission()
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
                        openBackgroundSettings()
                    },
                    onUsageAccess = {
                        try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                        catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    },
                    advancedReport = advancedReport,
                    usageAccessGranted = usageAccessGranted,
                    batteryStatsPermissionGranted = batteryStatsPermissionGranted,
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
                    onOpenDiagnostics = { showDiagnostics = true },
                    onExportDiagnostics = { exportDiagnostics.launch("sysbattery-diagnostics.txt") },
                    onClearDiagnostics = { DiagnosticLogger.clear(); DiagnosticLogger.info("Logs cleared by user") },
                    diagnosticText = DiagnosticLogger.readLogs(),
                    showDiagnostics = showDiagnostics,
                    onDismissDiagnostics = { showDiagnostics = false },
                    onRefreshAdvanced = {
                        shizukuLoading = true
                        Thread {
                            var findings = emptyList<RootBatteryStatsParser.Finding>()
                            var shizukuError: String? = null
                            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                                try {
                                    val service = shizukuService
                                    if (service != null) findings = ShizukuBatteryStatsReader(service).read().findings
                                    else shizukuError = "Shizuku service is not connected yet. Tap Enable Shizuku access again."
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
        usageAccessGranted = hasUsageAccess()
        batteryStatsPermissionGranted = hasBatteryStatsPermission()
        refreshShizukuStatus()
    }

    private fun openBackgroundSettings() {
        val powerManager = getSystemService(android.os.PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager?.isIgnoringBatteryOptimizations(packageName) != true) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") })
                return
            } catch (_: Exception) { }
        }
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:$packageName") }) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        if (shizukuService != null || shizukuServiceBinding) {
            runCatching { Shizuku.unbindUserService(shizukuUserServiceArgs, shizukuServiceConnection, true) }
        }
        shizukuService = null
        shizukuServiceBinding = false
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
            shizukuService != null -> "Authorized · BatteryStats service connected"
            shizukuServiceBinding -> "Authorized · connecting to BatteryStats service"
            shizukuAuthorized -> "Authorized · connecting to BatteryStats service"
            Shizuku.pingBinder() -> "Shizuku running · permission needed"
            available -> "Shizuku installed · service not running"
            else -> "Shizuku not installed · optional"
        }
        if (shizukuAuthorized && shizukuService == null) bindShizukuService()
    }

    private fun bindShizukuService() {
        if (!Shizuku.pingBinder() ||
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ||
            shizukuService != null || shizukuServiceBinding
        ) return
        shizukuServiceBinding = true
        try {
            Shizuku.bindUserService(shizukuUserServiceArgs, shizukuServiceConnection)
        } catch (e: Exception) {
            DiagnosticLogger.error("Failed to bind Shizuku BatteryStats service", e)
            shizukuServiceBinding = false
            shizukuStatus = "Could not bind Shizuku service: ${e.javaClass.simpleName}"
        }
    }

    private fun requestShizukuAccess() {
        DiagnosticLogger.info("Shizuku authorization requested")
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
            shizukuStatus = "Authorized · connecting to BatteryStats service"
            bindShizukuService()
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
    usageAccessGranted: Boolean,
    batteryStatsPermissionGranted: Boolean,
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
    onOpenDiagnostics: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onClearDiagnostics: () -> Unit,
    diagnosticText: String,
    showDiagnostics: Boolean,
    onDismissDiagnostics: () -> Unit,
    onMonitorEnabledChange: (Boolean) -> Unit,
    onThemeModeChange: (String) -> Unit,
    onPreferencesChanged: () -> Unit,
    preferencesRevision: Int,
    settingsPrefs: android.content.SharedPreferences
) {
    val level = sample?.levelPercent
    // Keep a small, session-local history. Sample on meaningful changes or every 30 seconds.
    val batteryHistory = remember { mutableStateListOf<Pair<Long, Int>>() }
    LaunchedEffect(sample?.timestampMillis, level) {
        val currentLevel = level
        if (currentLevel != null) {
            val timestamp = sample?.timestampMillis ?: System.currentTimeMillis()
            val previous = batteryHistory.lastOrNull()
            if (previous == null || previous.second != currentLevel || timestamp - previous.first >= 30_000L) {
                batteryHistory.add(timestamp to currentLevel.coerceIn(0, 100))
                while (batteryHistory.size > 36) batteryHistory.removeAt(0)
            }
        }
    }
    val temperatureUnit = remember(preferencesRevision) {
        settingsPrefs.getString("temperature_unit", "Celsius (°C)") ?: "Celsius (°C)"
    }
    val current = rates?.currentMilliAmpsMagnitude?.let { magnitude ->
        val signed = when (rates.direction) {
            BatteryStatsCalculator.Direction.CHARGING -> magnitude
            BatteryStatsCalculator.Direction.DISCHARGING -> -magnitude
            BatteryStatsCalculator.Direction.UNKNOWN -> magnitude
        }
        String.format(Locale.getDefault(), "%+.0f mA", signed)
    } ?: "—"
    val temp = sample?.temperatureTenthsCelsius?.let {
        val celsius = it / 10.0
        val fahrenheit = celsius * 9.0 / 5.0 + 32.0
        if (temperatureUnit == "Fahrenheit (°F)") String.format(Locale.getDefault(), "%.1f °F", fahrenheit)
        else String.format(Locale.getDefault(), "%.1f °C", celsius)
    } ?: "—"
    val voltage = sample?.voltageMilliVolts?.let { "$it mV" } ?: "—"
    val power = rates?.powerWattsMagnitude?.let {
        val signed = if (rates.direction == BatteryStatsCalculator.Direction.DISCHARGING) -it else it
        String.format(Locale.getDefault(), "%+.2f W", signed)
    } ?: "—"
    val cycleCount = sample?.cycleCount?.toString() ?: "Not reported"
    val health = when (sample?.health) {
        android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
        android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over-voltage"
        android.os.BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure reported"
        android.os.BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        else -> "Not reported"
    }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var selectedOptionsPage by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(selectedTab, usageAccessGranted) {
        if (selectedTab == 1) onRefreshAdvanced()
    }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .pointerInput(selectedTab) {
                    var horizontalDrag = 0f
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            horizontalDrag += dragAmount
                            if (kotlin.math.abs(horizontalDrag) > 80f) {
                                val direction = if (horizontalDrag < 0f) 1 else -1
                                val nextTab = (selectedTab + direction).coerceIn(0, 2)
                                if (nextTab != selectedTab) selectedTab = nextTab
                                horizontalDrag = 0f
                                change.consume()
                            }
                        },
                        onDragEnd = { horizontalDrag = 0f },
                        onDragCancel = { horizontalDrag = 0f }
                    )
                }
                .verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            if (selectedTab == 0) {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("BATTERY LEVEL", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.4.sp)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(level?.toString() ?: "—", fontSize = 58.sp, fontWeight = FontWeight.Bold, lineHeight = 60.sp)
                            if (level != null) Text("%", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        Text(
                            when (rates?.direction) {
                                BatteryStatsCalculator.Direction.CHARGING -> "Charging"
                                BatteryStatsCalculator.Direction.DISCHARGING -> "On battery"
                                else -> "Waiting for readings"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
                    ) {
                        Icon(
                            if (rates?.direction == BatteryStatsCalculator.Direction.CHARGING) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryFull,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(18.dp).size(48.dp)
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = { ((level ?: 0).coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
                )
            }
            GlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Battery history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (batteryHistory.size < 2) "Collecting session samples…" else "${batteryHistory.size} samples · session only",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(level?.let { "$it%" } ?: "—", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                if (batteryHistory.size >= 2) {
                    val chartLineColor = MaterialTheme.colorScheme.primary
                    val chartGridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                    Canvas(Modifier.fillMaxWidth().height(92.dp)) {
                        val values = batteryHistory.map { it.second.toFloat() }
                        val low = (values.minOrNull() ?: 0f).coerceAtMost(100f)
                        val high = (values.maxOrNull() ?: 100f).coerceAtLeast(0f)
                        val span = (high - low).coerceAtLeast(2f)
                        val inset = 8.dp.toPx()
                        val width = (size.width - inset * 2).coerceAtLeast(1f)
                        val height = (size.height - inset * 2).coerceAtLeast(1f)
                        repeat(3) { index ->
                            val y = inset + height * index / 2f
                            drawLine(
                                color = chartGridColor,
                                start = Offset(inset, y),
                                end = Offset(size.width - inset, y),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                        val points = values.mapIndexed { index, value ->
                            val x = inset + width * index / (values.size - 1).coerceAtLeast(1)
                            val y = inset + height * ((high - value) / span).coerceIn(0f, 1f)
                            Offset(x, y)
                        }
                        points.zipWithNext().forEach { (start, end) ->
                            drawLine(
                                color = chartLineColor,
                                start = start,
                                end = end,
                                strokeWidth = 3.dp.toPx()
                            )
                        }
                        points.lastOrNull()?.let { point ->
                            drawCircle(chartLineColor, radius = 4.dp.toPx(), center = point)
                        }
                    }
                } else {
                    Text("History appears as readings arrive while monitoring is active.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("Current", current, "↯", Modifier.weight(1f))
                MetricCard("Temperature", temp, "°", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("Voltage", voltage, "ϟ", Modifier.weight(1f))
                MetricCard("Drain rate", rates?.batteryPercentPerHour?.let { String.format(Locale.getDefault(), "%.2f%%/h", it) } ?: "Collecting…", "⌁", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("Power", power, "W", Modifier.weight(1f))
                MetricCard("Health", health, "♥", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("Cycle count", cycleCount, "↻", Modifier.weight(1f))
                MetricCard("Charge counter", sample?.chargeCounterMicroAmpHours?.let {
                    String.format(Locale.getDefault(), "%.0f mAh", it / 1000.0)
                } ?: "Not reported", "mAh", Modifier.weight(1f))
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
            if (selectedTab == 1) {
            GlassCard {
                Text("App usage estimates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Battery estimates and app foreground time from Android, when supported.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
                )
                if (!usageAccessGranted) {
                    Text("Enable Usage Access to display app foreground time.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onUsageAccess, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("Enable app usage access") }
                }
                OutlinedButton(onClick = onRefreshAdvanced, enabled = !shizukuLoading, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text(if (shizukuLoading) "Loading app usage…" else "Refresh app usage")
                }
                advancedError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                advancedReport?.let { report ->
                    Text("Battery power", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                report.totalConsumedMah?.let { String.format(Locale.getDefault(), "%.1f mAh", it) } ?: "Not provided by Android",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            report.batteryStatsError?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    Text("Per-app power estimates", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (report.apps.isEmpty()) {
                        Text("Android did not provide per-app power estimates for this refresh.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        report.apps.take(8).forEach { app ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(app.packageName ?: "UID ${app.uid}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                                    Text("Battery use estimate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(String.format(Locale.getDefault(), "%.2f mAh", app.consumedMah), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        }
                    }
                    Text("Most-used apps", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (report.usage.isEmpty()) {
                        Text(
                            if (!usageAccessGranted) "Turn on Usage Access to see foreground time." else "No app usage data is available for this period.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        report.usage.take(12).forEach { app ->
                            val minutes = app.foregroundMillis / 60_000L
                            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text(app.packageName.take(1).uppercase(Locale.getDefault()), modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp), color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
                                }
                                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(app.packageName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                                    Text("Foreground time", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("${minutes / 60}h ${minutes % 60}m", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    report.usageStatsError?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                }
            }

            }
            if (selectedTab == 2) {
                Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("Permissions and diagnostics", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Access overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Optional features and permissions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    AccessStatusRow("App usage", if (usageAccessGranted) "Enabled" else "Optional", usageAccessGranted)
                    AccessStatusRow("Shizuku", if (shizukuAuthorized) "Authorized" else "Not connected", shizukuAuthorized)
                    AccessStatusRow("Root diagnostics", if (rootReport?.rooted == true) "Available" else "Optional", rootReport?.rooted == true)
                    Text("Basic battery monitoring works without these optional access methods.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (selectedTab == 2) {
            GlassCard {
                Text("OEM background settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "ColorOS / OxygenOS: enable Auto-launch and allow background activity. HyperOS: set Battery saver to No restrictions and enable autostart if available. One UI: set battery usage to Unrestricted and remove SysBattery from Sleeping apps. Also allow notifications. Menu names vary by OS version.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium
                )
            }
            }
            if (selectedTab == 2) {
            GlassCard {
                Text("Advanced battery statistics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "SysBattery uses public battery APIs for live readings and Usage Access for app foreground time. Android restricts per-app power attribution on many production builds; if access is denied, the app reports that limitation instead of showing invented mAh values.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
                )
                if (!usageAccessGranted) Button(onClick = onUsageAccess, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("Enable Usage Access") }
                else Text("Usage Access enabled", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                Text(
                    "No privileged ADB grant is required for live battery level, voltage, temperature, and supported current readings. Usage Access is managed in Android Settings; ADB may be blocked from changing it on some OEM builds.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
                )
            }
            }
            if (selectedTab == 2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Advanced access", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("ADB tools and permission status", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SettingsSection(
                    title = "ADB tools",
                    subtitle = "Copy commands for diagnostics and access setup",
                    icon = { Icon(Icons.Rounded.Bolt, contentDescription = null) },
                    initiallyExpanded = false
                ) {
                    Text("Optional commands for advanced diagnostics. Connect a computer with USB debugging enabled, approve the computer on your device, and run copied commands in a terminal.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = CircleShape, color = if (batteryStatsPermissionGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                            Text(if (batteryStatsPermissionGranted) "Granted" else "Not granted", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = if (batteryStatsPermissionGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("BATTERY_STATS", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("PERMISSION CHECKS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    AdbCommandRow(
                        title = "Check Usage Access",
                        description = "Read-only check. Expected result includes GET_USAGE_STATS: allow.",
                        command = "adb shell appops get com.nlab.sysbattery GET_USAGE_STATS"
                    )
                    AdbCommandRow(
                        title = "Try granting BatteryStats",
                        description = if (batteryStatsPermissionGranted) "Permission is granted. Refresh advanced statistics to check available readings." else "Optional diagnostic command. Android may reject this permission on production devices; it cannot bypass system-level restrictions.",
                        command = "adb shell pm grant com.nlab.sysbattery android.permission.BATTERY_STATS"
                    )
                    AdbCommandRow(
                        title = "Try enabling Usage Access through ADB",
                        description = "May be blocked on production devices. If it fails, enable Usage Access from Android Settings.",
                        command = "adb shell appops set com.nlab.sysbattery GET_USAGE_STATS allow"
                    )
                    Text("BACKGROUND BEHAVIOR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    AdbCommandRow(
                        title = "Allow background monitoring in Doze",
                        description = "Optional. This changes battery-optimization behavior; you can undo it with the remove command below.",
                        command = "adb shell cmd deviceidle whitelist +com.nlab.sysbattery"
                    )
                    AdbCommandRow(
                        title = "Remove background allowlist entry",
                        description = "Undo the optional Doze allowlist change.",
                        command = "adb shell cmd deviceidle whitelist -com.nlab.sysbattery"
                    )
                    Text("READ-ONLY DIAGNOSTICS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    AdbCommandRow(
                        title = "Read battery state",
                        description = "Diagnostic output from Android; does not grant permission to SysBattery.",
                        command = "adb shell dumpsys battery"
                    )
                    AdbCommandRow(
                        title = "Read battery history",
                        description = "System battery accounting output for troubleshooting.",
                        command = "adb shell dumpsys batterystats --charged"
                    )
                    Text("Android controls access to privileged battery statistics. Availability varies by device and OS version. Live battery readings work without these optional commands; Usage Access, Shizuku, or root may provide additional data when supported.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (selectedTab == 2) {
            SettingsSection(
                title = "Shizuku access",
                subtitle = shizukuStatus,
                icon = { Icon(Icons.Rounded.Security, contentDescription = null) },
                initiallyExpanded = false
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Advanced battery statistics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
            }
            if (selectedTab == 2) {
            SettingsSection(
                title = "Root diagnostics",
                subtitle = if (rootReport?.rooted == true) "Root available" else "Optional · rooted devices only",
                icon = { Icon(Icons.Rounded.Security, contentDescription = null) },
                initiallyExpanded = false
            ) {
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
            if (selectedTab == 2) {
                SettingsSection(
                    title = "Logs & diagnostics",
                    subtitle = "View or export local diagnostic logs",
                    icon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                    initiallyExpanded = false
                ) {
                    Text("Local crash and app diagnostics logs. Nothing is uploaded automatically.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("View recent logs") }
                    OutlinedButton(onClick = onExportDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("Export logs…") }
                    OutlinedButton(onClick = onClearDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("Clear logs") }
                }
            }
            if (showDiagnostics) {
                AlertDialog(
                    onDismissRequest = onDismissDiagnostics,
                    title = { Text("Logs & diagnostics") },
                    text = {
                        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                            Text(diagnosticText, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = { TextButton(onClick = onExportDiagnostics) { Text("Export") } },
                    dismissButton = { TextButton(onClick = onDismissDiagnostics) { Text("Close") } }
                )
            }
            if (selectedTab == 0) Text("Readings depend on device and kernel support.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        NavigationBar(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp
        ) {
            listOf("Status", "App usage", "Settings").forEachIndexed { index, label ->
                val selected = selectedTab == index
                val icon = when (index) {
                    0 -> Icons.Rounded.BatteryChargingFull
                    1 -> Icons.Rounded.Apps
                    else -> Icons.Rounded.Settings
                }
                NavigationBarItem(
                    selected = selected,
                    onClick = { selectedTab = index },
                    icon = {
                        Icon(
                            icon,
                            contentDescription = label,
                            modifier = Modifier.size(24.dp),
                            tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    label = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
    }
}

@Composable
private fun AccessStatusRow(label: String, status: String, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(8.dp).background(
                if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                CircleShape
            )
        )
        Text(label, modifier = Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
        Text(
            status,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Surface(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = if (expanded) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimaryContainer) { icon() } }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = if (expanded) "Collapse $title" else "Expand $title", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
            }
        }
    }
}

@Composable
private fun AdbCommandRow(
    title: String,
    description: String,
    command: String
) {
    val context = LocalContext.current
    var copied by rememberSaveable(command) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText(title, command))
                        copied = true
                        Toast.makeText(context, "Command copied", Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.height(40.dp)
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (copied) "Copied" else "Copy", style = MaterialTheme.typography.labelMedium)
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
            ) {
                Text(
                    command,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
    val icon = when (title.lowercase(Locale.ROOT)) {
        "current" -> Icons.Rounded.Bolt
        "temperature" -> null
        "voltage" -> Icons.Rounded.Bolt
        "drain rate" -> Icons.Rounded.BatteryFull
        "power" -> Icons.Rounded.Bolt
        "capacity" -> Icons.Rounded.BatteryFull
        else -> null
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (icon != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.padding(8.dp).size(20.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                } else {
                    Text(symbol, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
