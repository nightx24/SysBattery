package com.nlab.sysbattery

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nlab.sysbattery.service.BatteryMonitorService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/** Minimal native UI with optional, explicitly authorized Shizuku sysfs access. */
class MainActivity : ComponentActivity() {
    private lateinit var status: TextView

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST && ::status.isInitialized) {
            status.text = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                "SysBattery\n\nShizuku access granted. Restricted sysfs readings will be tried when needed."
            } else {
                "SysBattery\n\nShizuku permission denied. Standard Android battery readings remain available."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        status = TextView(this).apply {
            textSize = 16f
            text = "SysBattery\n\nMonitoring is stopped."
            setPadding(0, 24, 0, 24)
        }
        val start = Button(this).apply {
            text = "Start monitoring"
            setOnClickListener {
                BatteryMonitorService.start(this@MainActivity)
                status.text = "SysBattery\n\nMonitoring started. See the ongoing notification."
            }
        }
        val stop = Button(this).apply {
            text = "Stop monitoring"
            setOnClickListener {
                BatteryMonitorService.stop(this@MainActivity)
                status.text = "SysBattery\n\nMonitoring stopped."
            }
        }
        val shizuku = Button(this).apply {
            text = "Enable Shizuku access"
            setOnClickListener {
                when {
                    !Shizuku.pingBinder() -> status.text =
                        "SysBattery\n\nShizuku is not running. Install/start Shizuku and connect its service first."
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                        status.text = "SysBattery\n\nShizuku access is already granted."
                    else -> Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
                }
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 32, 28, 24)
            addView(status)
            addView(start)
            addView(stop)
            addView(shizuku)
        }
        setContentView(ScrollView(this).apply { addView(layout) })

        lifecycleScope.launch {
            while (isActive) {
                val sample = BatteryMonitorService.state.value.sample
                if (sample != null) {
                    val rates = BatteryMonitorService.state.value.rates
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

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST = 7204
    }
}
