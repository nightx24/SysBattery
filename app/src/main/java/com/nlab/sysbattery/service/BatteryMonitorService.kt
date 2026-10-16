package com.nlab.sysbattery.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.nlab.sysbattery.data.BatterySysfsReader
import com.nlab.sysbattery.domain.BatteryStatsCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** User-started foreground sampler with a compact, FKM-inspired telemetry notification. */
class BatteryMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var reader: BatterySysfsReader
    private var previous: BatteryStatsCalculator.PreviousSample? = null
    private var previousLevel: Int? = null
    private var previousTimestamp = 0L
    private var monitorJob: Job? = null
    private var screenOnMillis = 0L
    private var screenOffMillis = 0L
    private var activeDrainPercent = 0
    private var idleDrainPercent = 0
    private var activeDrainMillis = 0L
    private var idleDrainMillis = 0L
    private var previousCharging: Boolean? = null
    private var thresholdResetTriggered = false

    data class MonitorState(
        val sample: BatterySysfsReader.Snapshot? = null,
        val rates: BatteryStatsCalculator.Rates? = null
    )

    companion object {
        const val ACTION_START = "com.nlab.sysbattery.action.START"
        const val ACTION_STOP = "com.nlab.sysbattery.action.STOP"
        const val EXTRA_INTERVAL_MS = "interval_ms"
        private const val DEFAULT_INTERVAL_MS = 10_000L
        private const val CHANNEL_ID = "sysbattery_monitor"
        const val PREFS_NAME = "sysbattery"
        const val PREF_MONITOR_ENABLED = "monitor_enabled"
        private const val NOTIFICATION_ID = 4102

        fun resetStoredCounters(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong("screen_on_ms", 0L)
                .putLong("screen_off_ms", 0L)
                .putInt("active_drain_percent", 0)
                .putInt("idle_drain_percent", 0)
                .putLong("active_drain_ms", 0L)
                .putLong("idle_drain_ms", 0L)
                .putBoolean("reset_counters_requested", true)
                .apply()
        }
        private val mutableState = MutableStateFlow(MonitorState())
        val state: StateFlow<MonitorState> = mutableState.asStateFlow()

        fun start(context: Context, intervalMs: Long = DEFAULT_INTERVAL_MS) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_MONITOR_ENABLED, true).apply()
            val intent = Intent(context, BatteryMonitorService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_INTERVAL_MS, intervalMs.coerceIn(2_000L, 60_000L))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_MONITOR_ENABLED, false).apply()
            context.stopService(Intent(context, BatteryMonitorService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        reader = BatterySysfsReader(applicationContext)
        restoreCounters()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && !getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_MONITOR_ENABLED, false)) {
            monitorJob?.cancel()
            stopForeground(true)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            monitorJob?.cancel()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(null, null, null))
        val interval = (intent?.getLongExtra(EXTRA_INTERVAL_MS, DEFAULT_INTERVAL_MS) ?: DEFAULT_INTERVAL_MS)
            .coerceIn(2_000L, 60_000L)
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                val sample = reader.readSnapshot()
                val status = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
                    BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
                    else -> null
                }
                val preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (preferences.getBoolean("reset_counters_requested", false)) {
                    resetCounters()
                    preferences.edit().putBoolean("reset_counters_requested", false).apply()
                }
                if (previousCharging == false && charging == true &&
                    preferences.getBoolean("reset_on_plug", true)) {
                    resetCounters()
                    thresholdResetTriggered = false
                }
                if (charging != null) previousCharging = charging
                val rates = BatteryStatsCalculator.calculate(previous, sample, charging)
                val now = sample.timestampMillis
                val elapsed = if (previousTimestamp > 0L) (now - previousTimestamp).coerceAtLeast(0L) else 0L
                val interactive = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
                if (interactive) screenOnMillis += elapsed else screenOffMillis += elapsed
                val oldLevel = previousLevel
                val newLevel = sample.levelPercent
                val resetAtThreshold = preferences.getBoolean("reset_at_threshold", true)
                val threshold = preferences.getInt("reset_threshold_percent", 90).coerceIn(50, 100)
                if (charging != true || newLevel == null || newLevel < threshold) {
                    thresholdResetTriggered = false
                } else if (resetAtThreshold && !thresholdResetTriggered) {
                    resetCounters()
                    thresholdResetTriggered = true
                }
                if (oldLevel != null && newLevel != null && newLevel < oldLevel && charging != true) {
                    val drop = oldLevel - newLevel
                    if (interactive) activeDrainPercent += drop else idleDrainPercent += drop
                }
                if (interactive) activeDrainMillis += elapsed else idleDrainMillis += elapsed

                mutableState.value = MonitorState(sample, rates)
                notificationManager().notify(NOTIFICATION_ID, buildNotification(sample, rates, charging))
                previous = BatteryStatsCalculator.PreviousSample(sample.timestampMillis,
                    sample.levelPercent, sample.currentMicroAmps, sample.chargeCounterMicroAmpHours)
                previousLevel = newLevel
                previousTimestamp = now
                persistCounters()
                delay(interval)
            }
        }
        return START_STICKY
    }

    private fun resetCounters() {
        screenOnMillis = 0L
        screenOffMillis = 0L
        activeDrainPercent = 0
        idleDrainPercent = 0
        activeDrainMillis = 0L
        idleDrainMillis = 0L
        persistCounters()
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SysBattery battery monitor", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Ongoing battery telemetry and discharge estimates"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(
        sample: BatterySysfsReader.Snapshot?,
        rates: BatteryStatsCalculator.Rates?,
        charging: Boolean?
    ): Notification {
        val preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val showBatteryPercentage = preferences.getBoolean("show_battery_icon", true)
        val showChargingPower = preferences.getBoolean("show_charging_power", true)
        val temperatureUnit = preferences.getString("temperature_unit", "Celsius (°C)") ?: "Celsius (°C)"
        val level = if (showBatteryPercentage) sample?.levelPercent?.let { "$it%" } else null
        val temperature = sample?.temperatureTenthsCelsius?.let {
            val celsius = it / 10.0
            val value = if (temperatureUnit == "Fahrenheit (°F)") celsius * 9.0 / 5.0 + 32.0 else celsius
            val unit = if (temperatureUnit == "Fahrenheit (°F)") "°F" else "°C"
            String.format(Locale.getDefault(), "%.1f%s", value, unit)
        }
        val current = if (showChargingPower) rates?.currentMilliAmpsMagnitude else null
        val powerWatts = if (showChargingPower && current != null && sample?.voltageMilliVolts != null) {
            current * sample.voltageMilliVolts / 1_000_000.0
        } else null
        val mode = when (charging) {
            true -> "Charging"
            false -> "Discharging"
            null -> "Battery status unavailable"
        }
        val summary = buildString {
            if (level != null) append(level).append(" · ")
            append(mode)
            if (temperature != null) append(" · ").append(temperature)
            if (current != null) append(" · ").append(String.format(Locale.getDefault(), "%.0f mA", current))
            if (powerWatts != null && powerWatts.isFinite()) {
                append(" · ").append(String.format(Locale.getDefault(), "%.2f W", powerWatts))
            }
        }
        val activeRate = ratePerHour(activeDrainPercent, activeDrainMillis)
        val idleRate = ratePerHour(idleDrainPercent, idleDrainMillis)
        val idleWarningEnabled = preferences.getBoolean("idle_warning", true)
        val idleWarningLimit = preferences.getInt("idle_warning_percent", 5).coerceIn(1, 20)
        val idleWarningText = if (idleWarningEnabled && idleRate != "--/hr" &&
            idleRate.removeSuffix("%/hr").toDoubleOrNull()?.let { it > idleWarningLimit } == true) {
            "High idle drain detected: $idleRate (limit " + idleWarningLimit + "%/h)"
        } else null
        val observedMillis = (screenOnMillis + screenOffMillis).coerceAtLeast(0L)
        val screenOnPercent = if (observedMillis > 0L) screenOnMillis * 100.0 / observedMillis else 0.0
        val screenOffPercent = if (observedMillis > 0L) screenOffMillis * 100.0 / observedMillis else 0.0
        val details = listOfNotNull(
            idleWarningText,
            "Active drain: $activeRate · idle drain: $idleRate",
            "Screen on: " + formatDuration(screenOnMillis) + " (" + String.format(Locale.getDefault(), "%.1f", screenOnPercent) + "%)",
            "Screen off: " + formatDuration(screenOffMillis) + " (" + String.format(Locale.getDefault(), "%.1f", screenOffPercent) + "%)",
            "Awake/deep sleep: unavailable from screen state alone"
        ).joinToString("\n")
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("SysBattery")
            .setContentText(summary)
            .setContentIntent(PendingIntent.getActivity(
                this, 4103,
                Intent(this, com.nlab.sysbattery.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .setDeleteIntent(PendingIntent.getBroadcast(
                this, 4104,
                Intent(this, com.nlab.sysbattery.receiver.NotificationDismissedReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$summary\n$details"))
            .setSubText("Battery monitor")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun restoreCounters() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        screenOnMillis = prefs.getLong("screen_on_ms", 0L)
        screenOffMillis = prefs.getLong("screen_off_ms", 0L)
        activeDrainPercent = prefs.getInt("active_drain_percent", 0)
        idleDrainPercent = prefs.getInt("idle_drain_percent", 0)
        activeDrainMillis = prefs.getLong("active_drain_ms", 0L)
        idleDrainMillis = prefs.getLong("idle_drain_ms", 0L)
    }

    private fun persistCounters() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong("screen_on_ms", screenOnMillis)
            .putLong("screen_off_ms", screenOffMillis)
            .putInt("active_drain_percent", activeDrainPercent)
            .putInt("idle_drain_percent", idleDrainPercent)
            .putLong("active_drain_ms", activeDrainMillis)
            .putLong("idle_drain_ms", idleDrainMillis)
            .apply()
    }

    /** Rates are based on whole-percent level drops and may remain -- until a drop is observed. */
    private fun ratePerHour(drainPercent: Int, durationMillis: Long): String {
        if (durationMillis <= 0L || drainPercent == 0) return "--/hr"
        val rate = drainPercent * 3_600_000.0 / durationMillis
        return String.format(Locale.getDefault(), "%.2f%%/hr", rate)
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m ${seconds}s"
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
