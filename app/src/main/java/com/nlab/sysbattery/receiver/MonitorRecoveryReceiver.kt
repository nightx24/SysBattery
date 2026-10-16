package com.nlab.sysbattery.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nlab.sysbattery.service.BatteryMonitorService

/** Restarts monitoring only when the user previously enabled it. */
class MonitorRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val preferences = context.getSharedPreferences(
            BatteryMonitorService.PREFS_NAME, Context.MODE_PRIVATE
        )
        val resetOnReboot = preferences.getBoolean("reset_on_reboot", true)

        if (intent?.action == Intent.ACTION_SHUTDOWN) {
            if (resetOnReboot) BatteryMonitorService.resetStoredCounters(context)
            return
        }

        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && resetOnReboot) {
            BatteryMonitorService.resetStoredCounters(context)
        }

        val enabled = preferences.getBoolean(BatteryMonitorService.PREF_MONITOR_ENABLED, false)
        if (enabled) BatteryMonitorService.start(context.applicationContext)
    }
}
