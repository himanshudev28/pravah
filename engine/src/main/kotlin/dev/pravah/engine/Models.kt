package dev.pravah.engine

/**
 * A raw telemetry sample from the device battery.
 *
 * @param tMillis      wall-clock timestamp in milliseconds
 * @param rawCurrent   raw CURRENT_NOW value — units and sign are UNKNOWN until calibrated
 * @param chargeCounterUah  CHARGE_COUNTER in µAh — the trusted anchor signal
 */
data class Sample(
    val tMillis: Long,
    val rawCurrent: Long,
    val chargeCounterUah: Long,
)

enum class Confidence { HIGH, LOW, UNKNOWN }

/**
 * Persisted result of calibration.
 *
 * - [unitDivisor] 1 = device reports µA, 1000 = device reports mA
 * - [signMultiplier] +1 = charging is positive (normal), -1 = inverted sign
 * - [confidence] HIGH only when the integration matches charge_counter within tolerance
 */
data class CalibrationProfile(
    val unitDivisor: Long,
    val signMultiplier: Int,
    val confidence: Confidence,
) {
    companion object {
        val UNKNOWN = CalibrationProfile(1L, 1, Confidence.UNKNOWN)
    }
}

/** A single charge session summary, suitable for Room storage. */
data class SessionSummary(
    val id: Long = 0,
    val startMillis: Long,
    val endMillis: Long,
    val startPercent: Int,
    val endPercent: Int,
    val mAhAdded: Double,
    val wearPermille: Int,       // per-mille (‰) of design capacity lost this session
    val peakWatts: Double,
    val avgWatts: Double,
)
