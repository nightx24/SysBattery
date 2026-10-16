package com.nlab.sysbattery.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import java.io.File
import java.io.IOException

/** Uses Android's public API first; sysfs fallbacks are read-only and never require root. */
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
        val source: String
    )

    fun readSnapshot(): Snapshot {
        val level = intProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
            ?: readSysfs(listOf("/sys/class/power_supply/battery/capacity"))?.toInt()?.takeIf { it in 0..100 }

        val current = signedProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?: readSysfs(listOf(
                "/sys/class/power_supply/battery/current_now",
                "/sys/class/power_supply/bms/current_now"
            ))

        // ACTION_BATTERY_CHANGED reports battery voltage in mV. USB input voltage
        // can be ~5 V and is not the battery cell voltage.
        val batteryVoltageMv = try {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                ?.takeIf { it in 2_000..6_000 }
                ?.toLong()
        } catch (_: RuntimeException) { null }
        val voltageUv = readSysfs(listOf(
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/bms/voltage_now"
        ))
        val voltageMv = batteryVoltageMv
            ?: voltageUv?.takeIf { it in 2_000_000L..30_000_000L }?.div(1_000L)

        val chargeCounter = longProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?: readSysfs(listOf(
                "/sys/class/power_supply/battery/charge_counter",
                "/sys/class/power_supply/bms/charge_counter"
            ))
        val temperature = try {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it >= 0 }
        } catch (_: RuntimeException) { null }

        val sources = mutableListOf<String>()
        if (level != null) sources += "level"
        if (current != null) sources += "current"
        if (voltageMv != null) sources += "voltage"
        if (chargeCounter != null) sources += "charge-counter"

        return Snapshot(levelPercent = level, currentMicroAmps = current,
            voltageMilliVolts = voltageMv, chargeCounterMicroAmpHours = chargeCounter,
            temperatureTenthsCelsius = temperature,
            source = sources.joinToString(",").ifBlank { "no-readable-telemetry" })
    }

    private fun intProperty(id: Int): Int? = try {
        manager.getIntProperty(id).takeIf { it >= 0 }
    } catch (_: RuntimeException) { null }

    private fun longProperty(id: Int): Long? = try {
        manager.getLongProperty(id).takeIf { it >= 0L && it != Long.MIN_VALUE }
    } catch (_: RuntimeException) { null }

    private fun signedProperty(id: Int): Long? = try {
        manager.getLongProperty(id).takeIf { it != Long.MIN_VALUE }
    } catch (_: RuntimeException) { null }

    private fun readSysfs(paths: List<String>): Long? {
        for (path in paths) {
            try {
                val file = File(path)
                if (file.isFile && file.canRead()) {
                    file.readText(Charsets.US_ASCII).trim().toLongOrNull()?.let { return it }
                }
            } catch (_: IOException) {
                // A restricted node may still be readable through an explicitly authorized Shizuku shell.
            } catch (_: SecurityException) {
                // Try Shizuku only when the user has granted its permission.
            }
        }
        return null
    }

}
