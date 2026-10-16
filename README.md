# SysBattery

SysBattery is an open-source Android battery telemetry monitor built with Kotlin.

## Features
- Reads Android `BatteryManager` properties first, with safe read-only `/sys/class/power_supply/` fallbacks.
- Optional [Shizuku](https://github.com/rikkaapps/shizuku) integration to try reading allowlisted battery sysfs nodes when the normal app UID is denied access; permission must be granted by the user.
- Shows battery percentage, current, voltage, temperature, direction, and estimated rate values when available.
- User-started foreground monitoring with a persistent notification.
- Coroutine-based sampling and `StateFlow` state updates.
- Handles missing/restricted sysfs nodes without requiring root.

## Build
Open this repository in a current Android Studio version with JDK 17 and Android SDK 35. Gradle will resolve Android Gradle Plugin 8.7.3, Kotlin 2.0.21, AndroidX Core, Lifecycle Runtime, and Coroutines. Build using **Build > Make Project** or `./gradlew assembleDebug` after generating the Gradle wrapper in Android Studio.

## Notes
- The default 10-second sampling interval is a recreation choice, not a verified interval from the reference app.
- `BatteryManager` current/charge-counter values can be unsupported or vendor-dependent. Sysfs access is best-effort and often restricted on retail devices.
- Shizuku support is optional: install and start Shizuku separately, then tap **Enable Shizuku access** in SysBattery and grant permission. It does not unlock every protected node; SELinux and device policies may still deny reads. No commands are run beyond reading fixed battery paths.
- Per-app power attribution, deep-sleep accounting, and component power estimates are not implemented yet.
- Foreground-service rules and notification permission behavior can vary by Android version and distribution policy; review requirements before publishing.

See [`docs/battery_logic_spec.md`](docs/battery_logic_spec.md) for evidence tags and formula assumptions.
