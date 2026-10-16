package com.nlab.sysbattery.data

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.BatteryStatsManager
import android.os.BatteryUsageStats
import android.os.Process
import java.util.concurrent.TimeUnit

/**
 * Reads Android's public BatteryUsageStats and UsageStats APIs.
 * Results are device/OS dependent; missing permission is returned as an explicit error.
 */
class AdvancedBatteryStatsReader(context: Context) {
    private val appContext = context.applicationContext

    data class AppUsage(
        val packageName: String,
        val foregroundMillis: Long
    )

    data class AppEnergy(
        val uid: Int,
        val packageName: String?,
        val consumedMah: Double
    )

    data class Report(
        val generatedAtMillis: Long,
        val windowStartMillis: Long,
        val windowEndMillis: Long,
        val totalConsumedMah: Double?,
        val apps: List<AppEnergy>,
        val usage: List<AppUsage>,
        val batteryStatsError: String?,
        val usageStatsError: String?
    )

    fun read(windowMillis: Long = TimeUnit.HOURS.toMillis(24)): Report {
        val now = System.currentTimeMillis()
        val start = now - windowMillis.coerceAtLeast(TimeUnit.MINUTES.toMillis(1))
        val appsUsage = readUsage(start, now)
        val (total, energies, error) = readEnergy()
        return Report(now, start, now, total, energies, appsUsage.first, error, appsUsage.second)
    }

    private fun readEnergy(): Triple<Double?, List<AppEnergy>, String?> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Triple(null, emptyList(), "BatteryUsageStats requires Android 12 or newer.")
        }
        return try {
            val manager = appContext.getSystemService(BatteryStatsManager::class.java)
                ?: return Triple(null, emptyList(), "BatteryStatsManager unavailable.")
            val stats: BatteryUsageStats = manager.getBatteryUsageStats()
            val apps = stats.uidBatteryConsumers.mapNotNull { consumer ->
                val uid = consumer.uid
                val mah = consumer.consumedPower
                if (!mah.isFinite() || mah < 0.0 || uid == Process.SYSTEM_UID) null
                else AppEnergy(uid, appContext.packageManager.getPackagesForUid(uid)?.firstOrNull(), mah)
            }.sortedByDescending { it.consumedMah }
            Triple(stats.consumedPower.takeIf { it.isFinite() && it >= 0.0 }, apps, null)
        } catch (_: SecurityException) {
            Triple(null, emptyList(), "Android denied BATTERY_STATS. Grant it with ADB if this OS permits it.")
        } catch (e: RuntimeException) {
            Triple(null, emptyList(), "Battery statistics unavailable: ${e.javaClass.simpleName}")
        }
    }

    private fun readUsage(start: Long, end: Long): Pair<List<AppUsage>, String?> {
        return try {
            val manager = appContext.getSystemService(UsageStatsManager::class.java)
                ?: return Pair(emptyList(), "UsageStatsManager unavailable.")
            val rows = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
                .orEmpty()
                .filter { it.totalTimeInForeground > 0L }
                .groupBy { it.packageName }
                .map { (pkg, values) -> AppUsage(pkg, values.sumOf { it.totalTimeInForeground }) }
                .sortedByDescending { it.foregroundMillis }
            Pair(rows, if (rows.isEmpty()) "No usage data. Enable Usage Access for SysBattery." else null)
        } catch (e: RuntimeException) {
            Pair(emptyList(), "Usage data unavailable: ${e.javaClass.simpleName}")
        }
    }
}
