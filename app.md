# Pravah — Charging & Battery Meter
**Complete app PRD / Claude Code build brief** · single build, full app
**Platform:** Android · Kotlin · Jetpack Compose (Material 3)
**Distribution:** personal, sideloaded · **Working title:** Pravah (प्रवाह — "flow/current"), rename freely

> Build the entire app in one pass. The telemetry calibration described in §4 is a **runtime feature**, not a separate build phase — the app self-calibrates on the user's first charge and degrades gracefully where a value isn't reachable.

---

## 1. Summary

A privacy-first Android charging and battery monitor. Reads all telemetry through **public APIs only** (no root, no ADB, no Shizuku), runs **fully offline with zero network access**, and needs essentially **no dangerous permissions** — a deliberate counter to the permission-hungry apps in this category. Premium animated dashboard. The one hard problem — Android battery *current* readings have unreliable units and signs across OEMs — is solved by a self-calibrating telemetry layer that anchors to the trustworthy `charge_counter` signal.

## 2. Constraints (non-negotiable)

- **Data access:** public Android APIs only. No root, no ADB, no Shizuku, no device-owner/privileged APIs.
- **Network:** zero. No analytics, no remote config, no uploads. Offline-only ⇒ zero egress.
- **Permissions:** minimal — `FOREGROUND_SERVICE` (+ appropriate `FOREGROUND_SERVICE_*` type) and `POST_NOTIFICATIONS` (API 33+), only for the live-stats notification and charge alarm. Nothing dangerous (no location, contacts, or user-data storage).
- **Licensing:** OSI-permissive only. Compose, Room, Kotlin Coroutines, Material 3 are all Apache-2.0 (clean). **Blocked:** AGPL, SSPL, CC-BY-NC. Check any added dependency at the point of adding it.
- **Min SDK 29** (Android 10), target current stable. (29 unlocks `getThermalHeadroom()` and `computeChargeTimeRemaining()`.)
- **The app must not itself drain the battery:** sample at 2–5 s while charging, back off on idle/screen-off, hold no wakelocks.

## 3. Tech stack & module layout

- Kotlin, Jetpack Compose + Material 3, Coroutines/Flow for the live telemetry stream.
- **Room** for persistence (charge sessions, settings). **DataStore** for the calibration profile + simple prefs. Local only.
- A **foreground service** for the optional live notification + charge alarm.
- **`:engine`** — pure Kotlin/JVM, **zero `android.*` imports**: `CalibrationEngine`, `CapacityEstimator`, `MetricsCalculator`, all pure logic + unit tests (run on the JVM, no emulator).
- **`:app`** — Android: telemetry source readers, persistence, services, all UI. Depends on `:engine`.

## 4. Telemetry layer (must be correct)

Every derived number (ampere, watts, health %, mAh, time-to-full) comes from battery **current**, which is the least trustworthy Android signal: OEMs report it in µA *or* mA, and use inconsistent sign conventions. **`charge_counter` (µAh) is the trusted anchor.**

**`CalibrationEngine` (pure JVM) — runs at runtime:**

```kotlin
data class Sample(val tMillis: Long, val rawCurrent: Long, val chargeCounterUah: Long) // rawCurrent units/sign UNKNOWN; chargeCounter TRUSTED
enum class Confidence { HIGH, LOW, UNKNOWN }
data class CalibrationProfile(val unitDivisor: Long, val signMultiplier: Int, val confidence: Confidence) // unitDivisor 1=µA,1000=mA; signMultiplier ±1 so charging is POSITIVE

fun calibrate(samples: List<Sample>): CalibrationProfile
```

- Over a window where `chargeCounterUah` changes monotonically, integrate `rawCurrent` (trapezoidal, time→hours) and compare its magnitude to `Δ chargeCounterUah`: ratio ≈ 1 ⇒ `unitDivisor=1`; ≈ 1000 ⇒ `unitDivisor=1000`. `HIGH` only within tolerance, else `LOW`/`UNKNOWN`.
- `chargeCounter` rising ⇒ charging ⇒ raw current must normalize positive ⇒ set `signMultiplier`. Confirm against a discharge window if present.
- Current ~0 / noisy-straddling-zero ⇒ `UNKNOWN`.
- **Never assume the spec default (µA, +charging). Derive unit/sign only from the `charge_counter` correlation. If undeterminable, return `UNKNOWN` — do not guess.**

**Runtime behavior of calibration (this replaces "Phase 0"):**
- The engine accumulates samples in the background. Persist the `CalibrationProfile` once `HIGH` is reached (re-validate occasionally).
- While `confidence != HIGH`: current-derived tiles (ampere, watts, charging-speed) show a small **"Calibrating — plug in to charge"** state (or raw current with an "uncalibrated" badge). Everything else (level, voltage, temp, status) shows immediately.
- After `HIGH`: full fidelity, no badges.

**`CapacityEstimator` (pure JVM):** measures *real* full capacity AccuBattery-style from `Δ charge_counter` ÷ (Δ % / 100) across charge sessions (needs no root). Persist a running estimate; only count sessions with substantial charge for accuracy.

**Design capacity** (for health % = measured ÷ design): try `com.android.internal.os.PowerProfile.getBatteryCapacity()` via reflection (greylisted, often blocked on Android 9+); if unavailable, show a one-time **"Enter your phone's rated capacity (mAh)"** card. Health % shows `—` until design is known.

## 5. Features → source & unavailable-data behavior

| Feature | Source | If unavailable |
|---|---|---|
| Charging status, plug type, % level | `ACTION_BATTERY_CHANGED` | always available |
| **Ampere** (current) | `CURRENT_NOW`, normalized via profile | "calibrating" until profile HIGH |
| **Charging & discharge watts** | voltage × normalized current | "calibrating" until profile HIGH |
| Voltage, battery technology | `ACTION_BATTERY_CHANGED` | always available |
| **Battery temperature** (°C) | `ACTION_BATTERY_CHANGED` temp | always available |
| **mAh remaining** | `CHARGE_COUNTER` (µAh)→mAh | always available |
| **Total mAh** (real full capacity) | `CapacityEstimator` | shows "measuring…" until ≥1–2 sessions |
| **Battery health %** | measured ÷ design | `—` until design capacity known (manual card) |
| **Time-to-full** | own estimate from rate + remaining | hide while uncalibrated |
| Charge/discharge rate | normalized current | calibrating-gated |
| **Charge alarm** (user-set %, e.g. 80) | notification when level ≥ target | — |
| Charging-speed class (slow/normal/fast/turbo) | from watts | calibrating-gated |
| Charge-session history + per-session wear | Room | — |
| **"Thermal load"** (device thermal pressure, 0–1) | `PowerManager.getThermalHeadroom()` (API 29+) | hide if `unavailable`. **Label as "thermal load", not °C** — SoC temp in °C needs device-owner access and is out of scope |
| Charge cycle count | best-effort (`cycle_count` usually OEM-blocked) | hide if absent |

(Per-app usage / wakelocks are intentionally **excluded** — they require special access incompatible with a no-setup app.)

## 6. Screens

1. **Dashboard** — the hero. Large animated circular gauge: shows **charging power (W)** with an upward "flow" animation when plugged, or **battery %** when on battery. Surrounding live tiles: current (A), watts, voltage, battery temp, status, charging-speed badge, time-to-full. A small **current sparkline** (last ~30 s). Calibrating states render here per §4/§5.
2. **Health** — measured full capacity (mAh), health %, cumulative wear, design capacity (with the manual-entry card if not auto-detected), and a wear-over-time chart.
3. **History** — list of charge sessions: % range, mAh added, wear incurred, duration, avg speed, peak watts.
4. **Settings** — charge alarm %, units (A/mA, °C/°F), theme (incl. AMOLED black), live-notification toggle, widget toggle, design-capacity entry, reset data.

## 7. UI / animation direction

Material 3, dynamic color optional, **dark + AMOLED-black** themes. The dashboard gauge animates its sweep smoothly (`animateFloatAsState`) and, while charging, shows a flowing gradient/particle motion in the ring whose **color shifts with charge speed** (e.g. cool→green→amber for slow→fast→turbo). Animated number transitions on all live values. Fluid, 60 fps, no jank. Premium and clean — this should feel better than the incumbents, not busier.

## 8. Persistence (Room + DataStore, local only)

- Room: `ChargeSession` (id, start/end ts, start/end %, mAh added, wear, peak W, avg W), settings if preferred.
- DataStore: `CalibrationProfile`, design capacity, alarm %, theme/unit prefs.

## 9. Live service

Foreground service powering the optional persistent notification (live %, current, watts, temp) and the charge alarm. Sane sampling per §2; no wakelocks; stop/relax when not charging and the notification is disabled.

## 10. Engine tests (`:engine`, must be green)

`CalibrationEngine` — fixture builder synthesizes `List<Sample>` from `(unitDivisor, signMultiplier, currentMilliamps, durationMinutes)`; cover: µA/+ → `(1,+1,HIGH)`; mA/+ (Samsung-style) → `(1000,+1,HIGH)`; µA inverted → `(1,-1,HIGH)`; mA inverted → `(1000,-1,HIGH)`; flat/zero → `UNKNOWN`; noisy-straddling-zero → `LOW`/`UNKNOWN` (never `HIGH`); discharge window → correct sign; sub-minimum window → not `HIGH`. `CapacityEstimator` — a clean session yields capacity within tolerance; a too-short session is rejected. Use Kotest 5.9 data tests or JUnit5 params, whichever compiles cleanly.

## 11. Hard constraints for Claude Code (do not violate to make a value look right)

1. `charge_counter` is the trusted anchor; current is the suspect signal. Unit/sign come from their correlation, **never** the API contract.
2. Do not hardcode this device's unit or sign anywhere — derive at runtime.
3. When unit/sign or a capacity isn't determinable, render the **calibrating / `—` / manual-entry** state — do not invent a plausible number.
4. `:engine` stays pure JVM — zero `android.*` imports — so its logic is testable without an emulator.
5. Zero network, zero egress, no analytics SDKs.
6. Every source read is null-safe and exception-safe; a missing/blocked source must never crash the app — it drives the unavailable-data behavior in §5.
7. Surface genuine design questions (tolerances, minimum session length, sampling cadence) in PR notes — don't silently bake in a number that changes a metric's meaning.

## 12. Expected friction (flag it, don't paper over it)

- **Gradle wrapper + JDK toolchain:** pin `jvmToolchain(...)` and AGP/Gradle versions that actually resolve; state them in the README.
- **Kotest 5.9:** `io.kotest.datatest` import paths differ from older docs — verify they compile, or fall back to JUnit5 params.
- **Reflection + sysfs:** `PowerProfile` reflection and sysfs nodes are widely blocked on newer Android (hidden-API + SELinux). Treat `unavailable` as normal and route to the manual-entry / hidden behaviors.
- **API 28/29+ quirks:** `computeChargeTimeRemaining()`, `ENERGY_COUNTER`, and `getThermalHeadroom()` can return -1/0/NaN even when present — handle and hide gracefully.

## 13. Definition of done

- `:app` builds, installs, and runs; `:engine:test` green.
- On a real charge, the current/watts tiles transition from "calibrating" to live correct values (normalized current integrates to within ~5% of the `charge_counter` delta).
- All four screens functional; charge alarm fires at the set %; sessions are recorded; capacity/health populate (health % after design capacity is auto-detected or entered).
- Thermal load shows where available; everything degrades gracefully where a source is blocked. No crashes from missing sources. No network traffic.

## 14. To confirm/observe on-device

- Whether `PowerProfile` reflection yields design capacity (auto health %) or the manual-entry card is needed.
- Exact device model + OEM skin (for any per-OEM tuning).
- Confirm min SDK 29 against the device.