package dev.pravah.engine

/**
 * Estimates the battery's real full capacity (mAh) by accumulating data across charge sessions.
 *
 * Algorithm: capacity = Δcharge_counter_µAh / (Δpercent / 100)
 * Requires a substantial session (MIN_DELTA_PERCENT and MIN_DELTA_UAH) for a reliable estimate.
 * Running estimate uses an exponential moving average to track degradation over time.
 */
object CapacityEstimator {

    private const val MIN_DELTA_PERCENT = 10
    private const val MIN_DELTA_UAH = 50_000L   // 50 mAh minimum swing before we count a session
    private const val EMA_ALPHA = 0.3            // weight for new samples (30% new, 70% history)

    data class SessionData(
        val startPercent: Int,
        val endPercent: Int,
        val startChargeCounterUah: Long,
        val endChargeCounterUah: Long,
    )

    data class EstimatorState(
        val estimatedCapacityMah: Double?,   // null = not enough data yet
        val sessionCount: Int,
    ) {
        companion object {
            val EMPTY = EstimatorState(null, 0)
        }
    }

    /**
     * Incorporate a new completed session into the running estimate.
     * Returns an updated [EstimatorState] (does not mutate; caller persists it).
     */
    fun addSession(state: EstimatorState, session: SessionData): EstimatorState {
        val deltaPercent = session.endPercent - session.startPercent
        val deltaUah = session.endChargeCounterUah - session.startChargeCounterUah

        // Reject short / unreliable sessions
        if (deltaPercent < MIN_DELTA_PERCENT || deltaUah < MIN_DELTA_UAH) return state

        val measuredMah = deltaUah / 1000.0 / (deltaPercent / 100.0)

        val newEstimate = if (state.estimatedCapacityMah == null) {
            measuredMah
        } else {
            EMA_ALPHA * measuredMah + (1 - EMA_ALPHA) * state.estimatedCapacityMah
        }

        return EstimatorState(newEstimate, state.sessionCount + 1)
    }

    /** Health percentage: 0–100, or null if design capacity is unknown. */
    fun healthPercent(measuredMah: Double, designMah: Double): Double =
        (measuredMah / designMah * 100.0).coerceIn(0.0, 100.0)
}
