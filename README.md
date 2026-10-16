# SysBattery

SysBattery is an open-source Android battery telemetry monitor built with Kotlin and Jetpack Compose.

## Features

- Material 3 dashboard with rounded translucent cards, light/dark themes, and a modern glass-inspired appearance.
- At-a-glance battery history chart for the current app session, with minimum and maximum recorded charge labels; history is intentionally session-only and is not presented as persistent system history.
- Settings access overview for optional Usage Access, Shizuku, and root diagnostics, with clear distinction between basic monitoring and advanced access.
- Battery percentage, current, voltage, temperature, charging direction, and estimated discharge rate when supported by the device.
- User-started foreground monitoring with an ongoing notification that updates with live battery information.
- Drain estimates show a collection state until the first sample interval elapses; monitoring iterations log recoverable failures and retry instead of silently ending.
- Background-settings shortcut falls back to general battery optimization and app-info pages when a vendor ROM does not expose the direct exemption screen.
- Notification tap opens the dashboard; if Android permits the ongoing notification to be dismissed, a delete receiver requests restoration.
- Remembers whether monitoring was enabled and attempts recovery after device reboot or app update.
- Best-effort battery background guidance for ColorOS/OxygenOS, HyperOS, and One UI.
- Safe read-only `/sys/class/power_supply/` fallbacks where the app UID has access.
- Coroutine-based sampling and `StateFlow` state updates.

## Background reliability

For reliable long-running monitoring:

1. Allow SysBattery notifications.
2. Start monitoring from inside the app.
3. On ColorOS/OxygenOS, allow Auto-launch and background activity.
4. On HyperOS, set battery restrictions to No restrictions and enable autostart if offered.
5. On Samsung One UI, set battery usage to Unrestricted and remove SysBattery from Sleeping apps.

Android and individual manufacturers can still stop background work, restrict notification visibility, or delay boot recovery. The app cannot override those system policies. A foreground service and ongoing notification improve reliability but cannot guarantee uninterrupted monitoring on every ROM.

## Build

Open this repository in Android Studio with JDK 17 and Android SDK 35, or run `./gradlew assembleDebug` after generating the Gradle wrapper. The project uses Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Jetpack Compose, and Material 3.

## Optional ADB permissions for advanced statistics

SysBattery includes a helper in the dashboard to copy these commands for a connected, authorized ADB session:

```sh
adb shell pm grant com.nlab.sysbattery android.permission.BATTERY_STATS
adb shell appops set com.nlab.sysbattery GET_USAGE_STATS allow
```

The app also provides a shortcut to Android's **Usage Access** settings. Depending on Android version and OEM security policy, the `BATTERY_STATS` grant can be rejected for ordinary apps; the command is best-effort, not a bypass of signature/privileged restrictions. Usage access is controlled separately by an AppOp and may require enabling SysBattery in Settings.

These permissions are only access prerequisites. **They do not automatically create FKM-style per-app mAh attribution.** UsageStats can report app activity time, while battery attribution requires accessible BatteryStats data and supported APIs/permissions. Per-app energy attribution, wake-lock breakdowns, component power estimates, and FKM-equivalent history remain future work until implemented and validated on real devices. Do not treat usage duration as energy consumption.

## Rooted-device support

On rooted devices, the dashboard's **Check root & read system stats** action requests access through the installed `su` provider (for example, Magisk). Approve or deny that request in the superuser manager. If granted, SysBattery reads the output of the read-only commands `dumpsys batterystats --charged` and `dumpsys power` for diagnostics. The command output is capped before it is shown in the UI.

Root access is optional. Denying it does not affect standard battery monitoring. This first root backend exposes system diagnostics; it does not yet parse every vendor's battery-history format into validated deep-sleep percentages, wake-lock summaries or per-app mAh charts. Those metrics must be parsed from the device's actual output and checked against known system reports before being presented as calculated results.

## Notes and limitations

- The default sampling interval is 10 seconds.
- BatteryManager current/charge-counter values can be unsupported or vendor-dependent. Sysfs access is best-effort and often restricted on retail devices.
- The Shizuku button currently checks/request permission only; privileged Shizuku-backed sysfs reading is **not implemented yet**. Permission alone does not grant access to protected nodes.
- Device-specific full-charge capacity, state-of-health, and cycle-count metrics are not yet implemented. PlusPlusBattery demonstrates useful OPlus-specific FCC/SOH estimates and cycle-count history, but these require device-specific data sources and careful validation before inclusion.
- Per-app power attribution, deep-sleep accounting, and component power estimates are not implemented.
- The glass effect uses translucent surfaces and gradients rather than platform-specific backdrop blur, keeping the UI compatible across Android skins.

See [docs/battery_logic_spec.md](docs/battery_logic_spec.md) for evidence tags and formula assumptions.

## Battery measurement accuracy notes

SysBattery reads standard battery properties through `BatteryManager` and `ACTION_BATTERY_CHANGED`, with read-only sysfs fallback where Android permits it. Current and power are device-dependent: the app validates plausible current values, normalizes the displayed sign using Android's charging status, and displays power from current × battery-cell voltage. The displayed drain rate uses a persisted, direction-specific rolling window; it needs enough observations before reporting a percentage-per-hour estimate.

Cycle count and battery health are shown only when the Android battery broadcast reports them. Full-charge capacity and state-of-health values are vendor-specific on many phones and may require root-accessible files. SysBattery does not invent unavailable values or claim FKM-equivalent vendor algorithms. Screen-on/off time is measured from screen-state transitions observed while the service is alive; periods when Android stops the process cannot be reconstructed precisely without system usage/history access.

On One UI and other OEM builds, an inaccessible sysfs node is a permission/device limitation, not a parser error. Compare reported numbers against Android Settings or a trusted battery diagnostic app on the same device before treating them as calibrated physical measurements.

## Measurement sources and estimates

- **Current and power:** Android's `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` is reported in microamps by the standard API. SysBattery converts this to mA and estimates battery power from the measured current magnitude and battery-cell voltage; current/power signs are displayed from Android's charging status. Vendor drivers can still expose missing or incorrect readings, so implausible values are filtered.
- **Drain rate:** A persisted, direction-specific ten-minute sample window is used. A current-based rate is shown only after a minimum of three plausible samples spanning at least 45 seconds and when a measured/estimated full-charge capacity is available. Otherwise, the app falls back to a battery-level trend only after a three-minute window.
- **Full-charge capacity and health:** Read-only sysfs `charge_full` and `charge_full_design` paths are used only if the app UID can read them. A separately labelled full-charge-capacity estimate is saved only when the phone reports 100% and current magnitude is at most 25 mA. An estimated health percentage requires a design capacity; if Android/vendor data does not expose it, enter the rated capacity under Monitor settings. It is an estimate, not a manufacturer-certified state-of-health result.
- **Cycle count and health status:** Read from the Android battery broadcast on supported Android versions and from common readable sysfs paths where available. Devices that do not expose these values show “Not reported”; the app does not fabricate them.
- **Screen-on/off statistics:** Time is accumulated at screen-state transitions while the monitor service is running, and drain is attributed according to measured screen-on/off duration between samples. If Android force-stops the process, the OEM kills it, or the device restarts, an ordinary app cannot reconstruct all unobserved intervals exactly. Privileged system BatteryStats history may provide better historical data where access is granted.
