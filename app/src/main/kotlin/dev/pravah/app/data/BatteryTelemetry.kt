package dev.pravah.app.data

import dev.pravah.engine.ChargingSpeed
import dev.pravah.engine.Estimate

/**
 * One immutable snapshot of the battery, already normalised:
 *  - [currentMa] is signed: positive = flowing INTO the battery, negative = draining.
 *  - [watts] is signed the same way (V × I).
 *  - [estimate] is the *stabilised* time-to-full (charging) or time-to-empty (on battery).
 *    It is safe to render directly — it does not jump every second.
 */
data class BatteryTelemetry(
    val timestampMillis: Long,

    val levelPercent: Int,
    val powerState: PowerState,
    val plugType: PlugType,

    val voltageMillivolts: Int,
    val temperatureTenths: Int,          // 0.1 °C units
    val technology: String,
    val batteryHealth: BatteryHealth,

    /** Signed, lightly de-spiked (median of last 5 reads). + = charging. */
    val currentMa: Double,
    /** Signed power in W. */
    val watts: Double,
    /** Smoothed signed rate in % per hour (+ charging, − draining). Null while warming up. */
    val ratePercentPerHour: Double?,

    /** Speed class of the current charge session (latched, does not drop during taper). Null when not charging. */
    val chargingSpeed: ChargingSpeed?,
    /** Time to the charge limit (charging) / time to empty (on battery). Never null — check [Estimate.kind]. */
    val estimate: Estimate,
    /** Where charging stops (OS battery protection, user setting, or 100 %). The ETA counts to this. */
    val chargeLimit: ChargeLimit,
    /** Limit the phone itself enforces (battery protection), null if unknown. May differ from a manual [chargeLimit]. */
    val systemLimitPercent: Int?,
    /** The OS reports a fast-charge adapter (Samsung Adaptive / Super Fast). */
    val fastAdapter: Boolean,
    /** True for a few seconds after plugging/unplugging while the fuel gauge still reports the old current. */
    val currentSettling: Boolean,

    val mAhRemaining: Double,            // from charge counter
    val estimatedFullMah: Double?,       // counter / level
    val designCapacityMah: Double?,
    val cycleCount: Int?,
    val thermalHeadroom: Float?,         // 0..1+, PowerManager thermal headroom; null if unsupported

    val manufacturer: String,
    val model: String,
    val androidVersion: Int,
) {
    val temperatureCelsius: Float get() = temperatureTenths / 10f
    val isPluggedIn: Boolean get() = plugType != PlugType.NONE
    val isCharging: Boolean get() = powerState == PowerState.CHARGING
    /** Plugged in, not charging, and sitting at the charge limit — i.e. battery protection is holding it. */
    val heldAtLimit: Boolean
        get() = stopPercent < 100 && isPluggedIn && powerState != PowerState.CHARGING && levelPercent >= stopPercent - 1
    /** Where charging actually stops: the lower of the shown limit and the phone's own. */
    val stopPercent: Int get() = minOf(chargeLimit.percent, systemLimitPercent ?: 100)
    /** The phone's battery protection (not a Pravah setting) is what is holding the charge. */
    val heldBySystem: Boolean get() = heldAtLimit && systemLimitPercent != null && levelPercent >= systemLimitPercent - 1

    enum class PowerState {
        /** Plugged and energy is flowing in. */
        CHARGING,
        /** Plugged, at 100% (or OS reports full). */
        FULL,
        /** Plugged but not charging (charge limit / Protect Battery / too hot / phone draws more than charger). */
        PLUGGED_IDLE,
        /** Unplugged — running on battery. */
        DISCHARGING,
    }

    enum class PlugType { NONE, AC, USB, WIRELESS, DOCK }

    enum class BatteryHealth {
        UNKNOWN, GOOD, OVERHEAT, DEAD, OVER_VOLTAGE, COLD, FAILURE;

        fun label(): String = when (this) {
            GOOD          -> "Good"
            OVERHEAT      -> "Overheat"
            DEAD          -> "Dead"
            OVER_VOLTAGE  -> "Over voltage"
            COLD          -> "Cold"
            FAILURE       -> "Failure"
            else          -> "Unknown"
        }
    }
}

/** A point on the live current chart (kept in the repository so it survives navigation). */
data class CurrentPoint(val tMillis: Long, val currentMa: Float)
