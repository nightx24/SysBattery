package com.nlab.sysbattery.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nlab.sysbattery.service.BatteryMonitorService

/** Restarts monitoring only when the user previously enabled it. */
class MonitorRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val enabled = context.getSharedPreferences(
            BatteryMonitorService.PREFS_NAME, Context.MODE_PRIVATE
        ).getBoolean(BatteryMonitorService.PREF_MONITOR_ENABLED, false)
        if (enabled) BatteryMonitorService.start(context.applicationContext)
    }
}
