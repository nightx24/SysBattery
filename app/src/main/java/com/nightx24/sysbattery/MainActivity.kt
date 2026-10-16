package com.nlab.sysbattery

import android.Manifest
import androidx.activity.ComponentActivity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nlab.sysbattery.service.BatteryMonitorService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Minimal native UI. Sampling starts only after the user presses Start. */
class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        status = TextView(this).apply { textSize = 16f; text = "SysBattery\n\nMonitoring is stopped."; setPadding(0, 24, 0, 24) }
        val start = Button(this).apply { text = "Start monitoring"; setOnClickListener { BatteryMonitorService.start(this@MainActivity); status.text = "SysBattery\n\nMonitoring started. See the ongoing notification." } }
        val stop = Button(this).apply { text = "Stop monitoring"; setOnClickListener { BatteryMonitorService.stop(this@MainActivity); status.text = "SysBattery\n\nMonitoring stopped." } }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(28, 32, 28, 24); addView(status); addView(start); addView(stop) }
        setContentView(ScrollView(this).apply { addView(layout) })
        lifecycleScope.launch {
            while (isActive) {
                val state = BatteryMonitorService.state.value
                val sample = state.sample
                if (sample != null) {
                    val rates = state.rates
                    status.text = "SysBattery\n\n" +
                        "Battery: " + (sample.levelPercent?.toString()?.plus("%") ?: "Unavailable") + "\n" +
                        "Current: " + (sample.currentMicroAmps?.div(1000.0)?.toString()?.plus(" mA") ?: "Unavailable") + "\n" +
                        "Voltage: " + (sample.voltageMilliVolts?.toString()?.plus(" mV") ?: "Unavailable") + "\n" +
                        "Temperature: " + (sample.temperatureTenthsCelsius?.div(10.0)?.toString()?.plus(" °C") ?: "Unavailable") + "\n" +
                        "Direction: " + (rates?.direction?.name ?: "UNKNOWN") + "\n" +
                        "Current rate: " + (rates?.currentMilliAmpsMagnitude?.let { String.format("%.1f mA", it) } ?: "Unavailable") + "\n" +
                        "Drain rate: " + (rates?.batteryPercentPerHour?.let { String.format("%.2f%%/hour", it) } ?: "Collecting samples…") + "\n\n" +
                        "Source: " + sample.source + "\n\n" +
                        "Readings depend on device/kernel support; some values may be unavailable."
                }
                delay(1000)
            }
        }
    }
}
