package dev.pravah.engine

/**
 * Determines the unit (µA vs mA) and sign convention of CURRENT_NOW by correlating
 * its integral with the trusted CHARGE_COUNTER signal.
 *
 * Design decisions baked in:
 *   - Tolerance band: ±20% for HIGH, beyond that LOW/UNKNOWN
 *   - Minimum window: charge_counter must change by at least MIN_DELTA_UAH µAh
 *   - Trapezoidal integration over time in hours
 *   - Never assumes the spec default — derives everything from correlation
 */
object CalibrationEngine {

    // Minimum charge_counter delta (µAh) required before we trust the integral
    private const val MIN_DELTA_UAH = 1_000L          // 1 mAh
    private const val HIGH_TOLERANCE = 0.20            // ±20%
    private const val LOW_TOLERANCE = 0.60             // ±60%

    fun calibrate(samples: List<Sample>): CalibrationProfile {
        if (samples.size < 2) return CalibrationProfile.UNKNOWN

        // Split samples into monotonic windows (charging or discharging separately)
        val chargingResult = tryCalibrate(samples.filter { it.chargeCounterUah >= 0 }, rising = true)
        val dischargingResult = tryCalibrate(samples, rising = false)

        // Prefer HIGH from either window; fall back through LOW → UNKNOWN
        return listOf(chargingResult, dischargingResult)
            .firstOrNull { it.confidence == Confidence.HIGH }
            ?: listOf(chargingResult, dischargingResult)
                .firstOrNull { it.confidence == Confidence.LOW }
            ?: CalibrationProfile.UNKNOWN
    }

    private fun tryCalibrate(samples: List<Sample>, rising: Boolean): CalibrationProfile {
        // Find longest monotonic window in charge_counter
        val window = longestMonotonicWindow(samples, rising) ?: return CalibrationProfile.UNKNOWN

        val deltaUah = window.last().chargeCounterUah - window.first().chargeCounterUah
        val absDeltaUah = if (rising) deltaUah else -deltaUah

        if (absDeltaUah < MIN_DELTA_UAH) return CalibrationProfile.UNKNOWN

        // Trapezoidal integration of rawCurrent over hours → raw µAh equivalent
        val rawIntegralRaw = trapezoidalIntegral(window)
        if (rawIntegralRaw == 0.0) return CalibrationProfile.UNKNOWN

        // Determine sign: if rising window, integral should be positive for correct sign
        val signMultiplier = if (rising) {
            if (rawIntegralRaw > 0) 1 else -1
        } else {
            if (rawIntegralRaw < 0) 1 else -1
        }

        val absIntegral = Math.abs(rawIntegralRaw)

        // Ratio test for unit divisor
        // absIntegral is in raw_units·hours; absDeltaUah is µAh
        // If raw units are µA: ratio ≈ 1.0 (µA·h ≈ µAh)
        // If raw units are mA: ratio ≈ 1000.0 (mA·h ≈ 1000 µAh)
        val ratioToOne = absIntegral / absDeltaUah.toDouble()       // expect ≈ 1
        val ratioToThousand = absIntegral / (absDeltaUah / 1000.0)  // expect ≈ 1 for mA

        return when {
            isWithinTolerance(ratioToOne, HIGH_TOLERANCE) ->
                CalibrationProfile(1L, signMultiplier, Confidence.HIGH)
            isWithinTolerance(ratioToThousand, HIGH_TOLERANCE) ->
                CalibrationProfile(1000L, signMultiplier, Confidence.HIGH)
            isWithinTolerance(ratioToOne, LOW_TOLERANCE) ->
                CalibrationProfile(1L, signMultiplier, Confidence.LOW)
            isWithinTolerance(ratioToThousand, LOW_TOLERANCE) ->
                CalibrationProfile(1000L, signMultiplier, Confidence.LOW)
            else -> CalibrationProfile.UNKNOWN
        }
    }

    /** Trapezoidal integration: Σ (I_i + I_{i+1})/2 * Δt_hours → raw_units·hours */
    private fun trapezoidalIntegral(samples: List<Sample>): Double {
        var total = 0.0
        for (i in 0 until samples.size - 1) {
            val dt = (samples[i + 1].tMillis - samples[i].tMillis) / 3_600_000.0
            val avgI = (samples[i].rawCurrent + samples[i + 1].rawCurrent) / 2.0
            total += avgI * dt
        }
        return total
    }

    /** Returns the longest sub-list where chargeCounterUah is strictly monotonic (rising or falling). */
    private fun longestMonotonicWindow(samples: List<Sample>, rising: Boolean): List<Sample>? {
        if (samples.size < 2) return null

        var bestStart = 0
        var bestLen = 1
        var curStart = 0
        var curLen = 1

        for (i in 1 until samples.size) {
            val delta = samples[i].chargeCounterUah - samples[i - 1].chargeCounterUah
            val continues = if (rising) delta > 0 else delta < 0
            if (continues) {
                curLen++
                if (curLen > bestLen) {
                    bestLen = curLen
                    bestStart = curStart
                }
            } else {
                curStart = i
                curLen = 1
            }
        }

        return if (bestLen < 2) null else samples.subList(bestStart, bestStart + bestLen)
    }

    private fun isWithinTolerance(ratio: Double, tolerance: Double): Boolean =
        ratio.isFinite() && Math.abs(ratio - 1.0) <= tolerance

    /**
     * Normalize a raw current reading → µA, positive = charging.
     * unitDivisor=1 means device reports µA (multiply by 1), =1000 means mA (multiply by 1000).
     */
    fun normalize(rawCurrent: Long, profile: CalibrationProfile): Long =
        rawCurrent * profile.signMultiplier * profile.unitDivisor
}
