package com.nlab.sysbattery.data

import com.nlab.sysbattery.diagnostics.DiagnosticLogger

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
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
            val manager = appContext.getSystemService("batterystats")
                ?: return Triple(null, emptyList(), "BatteryStatsManager unavailable.")
            // Android exposes getBatteryUsageStats(BatteryUsageStatsQuery), not a no-argument method.
            // Build the query reflectively so OEM/API differences fail gracefully rather than crashing.
            val queryBuilderClass = Class.forName("android.os.BatteryUsageStatsQuery\$Builder")
            val builder = queryBuilderClass.getDeclaredConstructor().newInstance()
            queryBuilderClass.methods.firstOrNull { it.name == "includePowerModels" && it.parameterCount == 0 }
                ?.invoke(builder)
            val query = queryBuilderClass.getMethod("build").invoke(builder)
                ?: return Triple(null, emptyList(), "Android could not create a battery usage query.")
            val stats = manager.javaClass.methods.firstOrNull {
                it.name == "getBatteryUsageStats" && it.parameterCount == 1 &&
                    it.parameterTypes[0].name == "android.os.BatteryUsageStatsQuery"
            }?.invoke(manager, query)
                ?: return Triple(null, emptyList(), "Android returned no battery usage report.")
            val total = (stats.javaClass.methods.firstOrNull {
                it.name == "getConsumedPower" && it.parameterCount == 0
            }?.invoke(stats) as? Number)?.toDouble()
            val consumers = stats.javaClass.methods.firstOrNull {
                it.name == "getUidBatteryConsumers" && it.parameterCount == 0
            }?.invoke(stats) as? Iterable<*>
            val apps: List<AppEnergy> = (consumers ?: emptyList<Any>()).mapNotNull { consumer ->
                if (consumer == null) return@mapNotNull null
                val uid = (consumer.javaClass.methods.firstOrNull {
                    it.name == "getUid" && it.parameterCount == 0
                }?.invoke(consumer) as? Number)?.toInt() ?: return@mapNotNull null
                val power = (consumer.javaClass.methods.firstOrNull {
                    it.name == "getConsumedPower" && it.parameterCount == 0
                }?.invoke(consumer) as? Number)?.toDouble() ?: return@mapNotNull null
                if (!power.isFinite() || power < 0.0 || uid < 0) null
                else AppEnergy(uid, appContext.packageManager.getPackagesForUid(uid)?.firstOrNull(), power)
            }.sortedWith(compareByDescending<AppEnergy> { app -> app.consumedMah })
            Triple(total?.takeIf { it.isFinite() && it >= 0.0 }, apps, null)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.targetException
            DiagnosticLogger.error("BatteryUsageStats query failed", cause)
            if (cause is SecurityException) {
                Triple(null, emptyList(), "Android restricts per-app power attribution on this build (BATTERY_STATS is privileged). Live battery readings and Usage Access data remain available.")
            } else {
                Triple(null, emptyList(), "Battery statistics unavailable: ${cause.javaClass.simpleName}")
            }
        } catch (e: SecurityException) {
            DiagnosticLogger.error("BatteryUsageStats permission denied", e)
            Triple(null, emptyList(), "Android restricts per-app power attribution on this build (BATTERY_STATS is privileged). Live battery readings and Usage Access data remain available.")
        } catch (e: Exception) {
            DiagnosticLogger.error("BatteryUsageStats read failed", e)
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
            DiagnosticLogger.warn("UsageStats query failed", e)
            Pair(emptyList(), "Usage data unavailable: ${e.javaClass.simpleName}")
        }
    }
}
