# SysBattery

SysBattery is an open-source Android battery telemetry monitor built with Kotlin.

## Features
- Reads Android `BatteryManager` properties first, with safe read-only `/sys/class/power_supply/` fallbacks.
- Shows battery percentage, current, voltage, temperature, direction, and estimated rate values when available.
- User-started foreground monitoring with a persistent notification.
- Coroutine-based sampling and `StateFlow` state updates.
- Handles missing/restricted sysfs nodes without requiring root.

## Build
Open this repository in a current Android Studio version with JDK 17 and Android SDK 35. Gradle will resolve Android Gradle Plugin 8.7.3, Kotlin 2.0.21, AndroidX Core, Lifecycle Runtime, and Coroutines. Build using **Build > Make Project** or `./gradlew assembleDebug` after generating the Gradle wrapper in Android Studio.

## Notes
- The default 10-second sampling interval is a recreation choice, not a verified interval from the reference app.
- `BatteryManager` current/charge-counter values can be unsupported or vendor-dependent. Sysfs access is best-effort and often restricted on retail devices.
- Per-app power attribution, deep-sleep accounting, and component power estimates are not implemented yet.
- Foreground-service rules and notification permission behavior can vary by Android version and distribution policy; review requirements before publishing.

See [`docs/battery_logic_spec.md`](docs/battery_logic_spec.md) for evidence tags and formula assumptions.
