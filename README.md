# Pravah — Charging & Battery Meter

Privacy-first, offline-only Android battery monitor. No root. No network. No dangerous permissions.

## Toolchain

| Tool | Version |
|------|---------|
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| KSP | 2.0.21-1.0.27 |
| Gradle | 8.11.1 (`./gradlew` runs the locally cached distribution) |
| JDK (toolchain) | 19 |
| Min SDK | 29 (Android 10) |
| Target SDK | 34 |

## Build

```bash
./gradlew :engine:test          # engine unit tests (pure JVM, no device)
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## What's new in 1.1

- **ETA follows the charge limit.** Samsung *Battery protection → Maximum* stops charging at 80–95 %;
  the time estimate now counts to that level and re-targets the moment the setting changes
  (Basic/Adaptive → 100 %). A manual limit can be set in Settings; other phones learn the level
  they hold at.
- **"Paused at 85 %"** when the phone holds the charge instead of "not charging".
- **Speed label matches the system**: a fast adapter (Samsung `hv_charger`) is at least *Fast*,
  even while the screen and apps eat most of the power. The power row now says *Into battery*.
- **Short unplugs don't split sessions** (2 min grace); old split rows are merged by a DB migration.
- **Stale current after plug/unplug is ignored** (the gauge repeats the old value for ~30 s),
  so it can't flip the learned sign or skew the ETA.
- **Glancing at the phone doesn't stretch the ETA**: the screen-off charging rate is used as a
  floor for 10 min after the screen turns on.
- New launcher icon.

## Module layout

```
:engine  — pure JVM, zero android.* imports
           CalibrationEngine  — determines current unit/sign from charge_counter correlation
           CapacityEstimator  — real full capacity via AccuBattery-style session accumulation
           MetricsCalculator  — watts, time-to-full, charging speed, thermal clamp

:app     — Android module, depends on :engine
           data/BatteryTelemetrySource  — reads ACTION_BATTERY_CHANGED + BatteryManager
           data/repository/BatteryRepository — session tracking, calibration persistence
           service/BatteryMonitorService — foreground service for notification + charge alarm
           ui/  — Compose Material 3 screens: Dashboard, Health, History, Settings
```

## Design notes (§7 PRD)

- **Calibration:** `:engine` self-calibrates on first charge. The dashboard shows "Calibrating — plug in to charge" until `CalibrationProfile.confidence == HIGH`. All current-derived tiles (A, W, speed, time-to-full) are gated on HIGH.
- **Design capacity:** Tried via `PowerProfile` reflection first (greylisted, often blocked). If unavailable, a one-time card in the Health screen prompts manual entry. Health % shows `—` until design is known.
- **Thermal load:** `PowerManager.getThermalHeadroom()` (API 29+). Returns 0–1; labeled "thermal load" (not °C). Hidden if unavailable or returns NaN/-1.
- **Charge alarm:** fires once when `levelPercent >= alarmPercent` while charging. Uses a high-priority notification channel.
- **Charge limit:** read from `Settings.Global` `protect_battery` / `battery_protection_threshold` (Samsung; readable by normal apps), observed with a `ContentObserver`.
- **Zero network, zero egress:** no analytics, no remote config, no uploads. All data is local.

## Known friction (§12 PRD)

- Kotest 5.9 `io.kotest.datatest` compiles clean under Kotlin 2.0. If you hit import errors on older Kotest builds, fall back to JUnit5 `@ParameterizedTest`.
- `PowerProfile` reflection is widely blocked on Android 9+. The app routes to manual-entry normally.
- `getThermalHeadroom()` can return -1 or NaN even on API 29+. All such cases are treated as "unavailable" (tile hidden).
- `cycle_count` is OEM-blocked on most devices; tile is hidden if `BATTERY_PROPERTY_CYCLE_COUNT` returns ≤ 0.
