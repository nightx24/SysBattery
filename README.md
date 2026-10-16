# SysBattery

SysBattery is an open-source Android battery telemetry monitor built with Kotlin and Jetpack Compose.

## Features

- Material 3 dashboard with rounded translucent cards, light/dark themes, and a modern glass-inspired appearance.
- Battery percentage, current, voltage, temperature, charging direction, and estimated discharge rate when supported by the device.
- User-started foreground monitoring with an ongoing notification that updates with live battery information.
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
