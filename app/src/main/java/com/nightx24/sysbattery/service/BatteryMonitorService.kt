package com.nlab.sysbattery.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
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

/** User-started foreground sampler. The 10-second interval is a recreation default. */
class BatteryMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var reader: BatterySysfsReader
    private var previous: BatteryStatsCalculator.PreviousSample? = null
    private var monitorJob: Job? = null

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
        private const val NOTIFICATION_ID = 4102
        private val mutableState = MutableStateFlow(MonitorState())
        val state: StateFlow<MonitorState> = mutableState.asStateFlow()

        fun start(context: Context, intervalMs: Long = DEFAULT_INTERVAL_MS) {
            val intent = Intent(context, BatteryMonitorService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_INTERVAL_MS, intervalMs.coerceIn(2_000L, 60_000L))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BatteryMonitorService::class.java).setAction(ACTION_STOP))
        }
    }

    override fun onCreate() {
        super.onCreate()
        reader = BatterySysfsReader(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            monitorJob?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification("Monitoring battery telemetry"))
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
                val rates = BatteryStatsCalculator.calculate(previous, sample, charging)
                mutableState.value = MonitorState(sample, rates)
                previous = BatteryStatsCalculator.PreviousSample(sample.timestampMillis,
                    sample.levelPercent, sample.currentMicroAmps, sample.chargeCounterMicroAmpHours)
                delay(interval)
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL_ID, "SysBattery monitoring", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("SysBattery")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    override fun onDestroy() {
        monitorJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
