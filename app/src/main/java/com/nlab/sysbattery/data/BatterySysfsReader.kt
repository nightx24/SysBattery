package com.nlab.sysbattery.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Reads battery telemetry using Android's public API first and read-only sysfs fallbacks.
 * Raw current is preserved (signed, microamps); consumers must calibrate sign and plausible units
 * before interpreting it as charge/discharge current.
 */
class BatterySysfsReader(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BatteryManager::class.java)

    data class Snapshot(
        val timestampMillis: Long = System.currentTimeMillis(),
        val levelPercent: Int?,
        val currentMicroAmps: Long?,
        val voltageMilliVolts: Long?,
        val chargeCounterMicroAmpHours: Long?,
        val temperatureTenthsCelsius: Int?,
        val health: Int? = null,
        val cycleCount: Int? = null,
        val status: Int? = null,
        val source: String
    ) {
        val currentMilliAmpsSigned: Double?
            get() = currentMicroAmps?.div(1_000.0)
        val powerMilliWattsSigned: Double?
            get() = currentMilliAmpsSigned?.let { current -> voltageMilliVolts?.let { current * it / 1_000.0 } }
    }

    fun readSnapshot(): Snapshot {
        val batteryIntent = try {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: RuntimeException) {
            null
        }
        val level = intProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
            ?: batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                ?.takeIf { it in 0..100 }
            ?: readSysfs(listOf("/sys/class/power_supply/battery/capacity"))?.toInt()?.takeIf { it in 0..100 }

        // AOSP defines CURRENT_NOW in microamps but some OEM kernels expose mA instead.
        // Keep the raw value intact; converting its unit belongs in a calibrated reader.
        val current = signedProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?: readSysfs(listOf(
                "/sys/class/power_supply/battery/current_now",
                "/sys/class/power_supply/bms/current_now",
                "/sys/class/power_supply/battery/current_avg"
            ))

        val batteryVoltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it in 2_000..6_000 }?.toLong()
        val voltageUv = readSysfs(listOf(
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/bms/voltage_now"
        ))
        val voltageMv = batteryVoltageMv
            ?: voltageUv?.takeIf { it in 2_000_000L..30_000_000L }?.div(1_000L)

        val chargeCounter = longProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeIf { it > 0L }
            ?: readSysfs(listOf(
                "/sys/class/power_supply/battery/charge_counter",
                "/sys/class/power_supply/bms/charge_counter"
            ))?.takeIf { it > 0L }
        val temperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            ?.takeIf { it in 0..1_000 }
        val health = batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)?.takeIf { it >= 0 }
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?.takeIf { it >= 0 }
        val cycleCount = if (Build.VERSION.SDK_INT >= 34) {
            batteryIntent?.getIntExtra("android.os.extra.CYCLE_COUNT", -1)?.takeIf { it >= 0 }
        } else null
        val sources = mutableListOf<String>()
        if (level != null) sources += "level"
        if (current != null) sources += "current"
        if (voltageMv != null) sources += "voltage"
        if (chargeCounter != null) sources += "charge-counter"
        if (health != null) sources += "health"
        if (cycleCount != null) sources += "cycle-count"

        return Snapshot(
            levelPercent = level,
            currentMicroAmps = current,
            voltageMilliVolts = voltageMv,
            chargeCounterMicroAmpHours = chargeCounter,
            temperatureTenthsCelsius = temperature,
            health = health,
            cycleCount = cycleCount,
            status = status,
            source = sources.joinToString(",").ifBlank { "no-readable-telemetry" }
        )
    }

    private fun intProperty(id: Int): Int? = try {
        manager.getIntProperty(id).takeIf { it >= 0 }
    } catch (_: RuntimeException) { null }

    private fun longProperty(id: Int): Long? = try {
        manager.getLongProperty(id).takeIf { it >= 0L && it != Long.MIN_VALUE }
    } catch (_: RuntimeException) { null }

    private fun signedProperty(id: Int): Long? = try {
        manager.getLongProperty(id).takeIf { it != Long.MIN_VALUE && it != 0L }
    } catch (_: RuntimeException) { null }

    private fun readSysfs(paths: List<String>): Long? {
        for (path in paths) {
            try {
                val file = File(path)
                if (file.isFile && file.canRead()) {
                    file.readText(Charsets.US_ASCII).trim().toLongOrNull()?.let { return it }
                }
            } catch (_: IOException) {
                // Protected paths are not readable to a normal application UID.
            } catch (_: SecurityException) {
                // Root or Shizuku integration must be implemented separately to access protected nodes.
            }
        }
        return null
    }
}
