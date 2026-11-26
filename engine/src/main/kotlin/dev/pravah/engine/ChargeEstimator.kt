package dev.pravah.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Charging speed class, from sustained (smoothed, pre-taper) charging power. */
enum class ChargingSpeed(val label: String) {
    SLOW("Slow"), NORMAL("Normal"), FAST("Fast"), SUPER_FAST("Super fast");

    companion object {
        /**
         * Wired bands follow Android's legacy lock-screen split (≈7.5 W) and modern
         * 15 W / 25 W fast-charge tiers. Wireless pads top out lower, so they get their own bands.
         */
        fun fromWatts(watts: Double, wireless: Boolean): ChargingSpeed = if (wireless) when {
            watts < 5.0  -> SLOW
            watts < 9.0  -> NORMAL
            watts < 15.0 -> FAST
            else         -> SUPER_FAST
        } else when {
            watts < 7.5  -> SLOW
            watts < 15.0 -> NORMAL
            watts < 25.0 -> FAST
            else         -> SUPER_FAST
        }

        /**
         * Speed class shown to the user. Watts measure power *into the battery*, which the phone's
         * own draw (screen, apps) eats into; when the OS reports a fast-charge adapter (Samsung
         * `hv_charger`) the session is at least FAST, matching the system's own "Fast charging" label.
         */
        fun classify(watts: Double, wireless: Boolean, fastAdapter: Boolean): ChargingSpeed {
            val byWatts = fromWatts(watts, wireless)
            return if (fastAdapter && byWatts < FAST) FAST else byWatts
        }
    }
}

/** What the estimate is about. */
enum class EstimateKind { TO_FULL, TO_EMPTY, NONE }

/**
 * A display-ready, stabilised time estimate.
 *
 * @param minutes   rounded minutes remaining, null while [calculating]
 * @param atMillis  wall-clock time the event is expected (e.g. "Full at 22:40"), null while calculating
 * @param calculating true during the warm-up window — UI should show "Calculating…"
 */
data class Estimate(
    val kind: EstimateKind,
    val minutes: Int?,
    val atMillis: Long?,
    val calculating: Boolean,
) {
    companion object {
        val NONE = Estimate(EstimateKind.NONE, null, null, false)
    }
}

/** Mode of the battery as far as estimation is concerned. */
enum class FlowMode { CHARGING, DISCHARGING, IDLE }

/**
 * Turns a noisy 1 Hz stream of (current, charge counter, level) into a stable ETA.
 *
 * Pipeline:
 *  1. Current is smoothed with a time-based EMA (τ = 45 s charging, 4 min discharging)
 *     so irregular sampling intervals are handled correctly.
 *  2. The charge-counter slope over a sliding window (up to 6 min) gives the *actual* net
 *     charge rate; it is blended 50/50 with the EMA once it has enough data.
 *  3. Charging: Li-ion charges at constant current until ~80 % and then tapers (CV phase).
 *     We integrate per-percent using a taper model whose strength depends on C-rate
 *     (a 0.1 C trickle barely tapers; a 1 C fast charger tapers hard), up to the charge
 *     limit (e.g. 85 % when the OS battery protection stops charging there).
 *     An optional idle-rate floor keeps a short screen-on glance from inflating the ETA.
 *  4. Display stabilisation: we keep a target wall-clock time and count down to it.
 *     The target moves only when the fresh estimate disagrees by more than
 *     max(2 min, 8 %), re-checked at most every 20 s (charging) / 60 s (discharging).
 *     The shown number therefore ticks down smoothly instead of flickering.
 *
 * Not thread-safe; call from a single coroutine.
 */
class ChargeEstimator {

    private data class Point(val t: Long, val counterUah: Long)

    private var mode: FlowMode? = null
    private var modeSince = 0L
    private var lastT = 0L
    private var emaMa: Double? = null
    private val counterWindow = ArrayDeque<Point>()

    private var targetMillis: Long? = null
    private var lastCheck = 0L
    private var lastLevel = -1
    private var warmCount = 0
    private var idleSince = 0L
    private var lastTargetPercent = 100

    fun reset() {
        warmCount = 0
        mode = null; emaMa = null; counterWindow.clear()
        targetMillis = null; lastCheck = 0L; lastLevel = -1
    }

    /** Smoothed signed current (mA) — exposed for rate displays. */
    fun smoothedCurrentMa(): Double? = emaMa

    /**
     * @param currentMa  signed current, + = into battery
     * @param counterUah charge counter in µAh, ≤0 if unavailable
     * @param fullMah    full-charge capacity estimate in mAh
     * @param targetPercent where charging will stop (charge limit); 100 when there is none
     * @param idleFloorMa  charging current recently measured with the screen off; when the
     *                     measured rate is lower (screen on, apps running) this is used instead
     */
    fun update(
        nowMillis: Long,
        mode: FlowMode,
        levelPercent: Int,
        currentMa: Double,
        counterUah: Long,
        fullMah: Double?,
        targetPercent: Int = 100,
        idleFloorMa: Double? = null,
    ): Estimate {
        // Samsung flaps CHARGING ↔ NOT_CHARGING during top-off; only reset if the flow direction
        // really changed, or the idle spell lasted over a minute.
        if (mode == FlowMode.IDLE) {
            if (idleSince == 0L) idleSince = nowMillis
            if (nowMillis - idleSince > 60_000) { reset(); this.mode = FlowMode.IDLE }
            lastT = nowMillis
            return Estimate.NONE
        }
        idleSince = 0L
        if (mode != this.mode) {
            reset()
            this.mode = mode
            modeSince = nowMillis
        }
        if (fullMah == null || fullMah < 500) {
            lastT = nowMillis
            return Estimate.NONE
        }
        val kind = if (mode == FlowMode.CHARGING) EstimateKind.TO_FULL else EstimateKind.TO_EMPTY

        // 1. Warm-up: plain running mean, so one launch spike (screen wake, app start) can't
        //    seed the slow EMA. Afterwards: time-based EMA.
        val tau = if (mode == FlowMode.CHARGING) TAU_CHARGE_MS else TAU_DISCHARGE_MS
        val warmUpWindow = if (mode == FlowMode.CHARGING) WARMUP_CHARGE_MS else WARMUP_DISCHARGE_MS
        val prev = emaMa
        warmCount++
        emaMa = if (prev == null) currentMa else if (nowMillis - modeSince < warmUpWindow) {
            prev + (currentMa - prev) / warmCount
        } else {
            val dt = (nowMillis - lastT).coerceIn(1, 60_000).toDouble()
            val a = 1 - exp(-dt / tau)
            prev + a * (currentMa - prev)
        }
        lastT = nowMillis

        // 2. charge counter slope
        // Many fuel gauges update the counter in coarse steps (Samsung S21 FE: ~4 mAh every ~10 s). Only keep the moments it
        // changed, so the slope is measured between real steps instead of being biased by a stale plateau.
        if (counterUah > 0 && counterWindow.lastOrNull()?.counterUah != counterUah) {
            counterWindow.addLast(Point(nowMillis, counterUah))
            val windowMs = if (mode == FlowMode.CHARGING) WINDOW_CHARGE_MS else WINDOW_DISCHARGE_MS
            while (counterWindow.size > 2 && nowMillis - counterWindow.first().t > windowMs) counterWindow.removeFirst()
        }
        val slopeMa = counterSlopeMa()
        val ema = emaMa!!
        val measuredMa = if (slopeMa != null && slopeMa * ema > 0) 0.5 * slopeMa + 0.5 * ema else ema
        val effectiveMa = if (mode == FlowMode.CHARGING && idleFloorMa != null && idleFloorMa > measuredMa) idleFloorMa else measuredMa
        val target = targetPercent.coerceIn(1, 100)
        // Limit changed (battery protection toggled): drop the stabilised target so the new ETA shows at once.
        if (target != lastTargetPercent) { lastTargetPercent = target; targetMillis = null }

        val elapsed = nowMillis - modeSince
        val warmUp = if (mode == FlowMode.CHARGING) WARMUP_CHARGE_MS else WARMUP_DISCHARGE_MS

        val rawMinutes: Double? = when (mode) {
            FlowMode.CHARGING ->
                if (levelPercent >= target || effectiveMa <= MIN_MA) null
                else minutesToFull(levelPercent, effectiveMa, fullMah, target)
            FlowMode.DISCHARGING ->
                if (effectiveMa >= -MIN_MA) null
                else levelPercent / 100.0 * fullMah / -effectiveMa * 60.0
            FlowMode.IDLE -> null
        }

        if (elapsed < warmUp || rawMinutes == null || !rawMinutes.isFinite()) {
            return Estimate(kind, null, null, calculating = rawMinutes != null || elapsed < warmUp)
        }

        // 4. stabilise
        val checkEvery = if (mode == FlowMode.CHARGING) RECHECK_CHARGE_MS else RECHECK_DISCHARGE_MS
        val fresh = nowMillis + (rawMinutes * 60_000).toLong()
        val shownTarget = targetMillis
        val levelChanged = levelPercent != lastLevel
        if (shownTarget == null) {
            targetMillis = fresh; lastCheck = nowMillis
        } else if (nowMillis - lastCheck >= checkEvery || (levelChanged && nowMillis - lastCheck >= 5_000)) {
            lastCheck = nowMillis
            val shownRemaining = max(0L, shownTarget - nowMillis) / 60_000.0
            val diff = abs(rawMinutes - shownRemaining)
            if (diff > max(2.0, shownRemaining * 0.08) || shownTarget <= nowMillis) targetMillis = fresh
        }
        lastLevel = levelPercent

        val remainingMin = max(1.0, (targetMillis!! - nowMillis) / 60_000.0)
        return Estimate(kind, roundForDisplay(remainingMin), targetMillis, calculating = false)
    }

    private fun counterSlopeMa(): Double? {
        if (counterWindow.size < 2) return null
        val a = counterWindow.first(); val b = counterWindow.last()
        val dtH = (b.t - a.t) / 3_600_000.0
        val dMah = (b.counterUah - a.counterUah) / 1000.0
        if (counterWindow.size < 3 || b.t - a.t < MIN_SLOPE_SPAN_MS || abs(dMah) < MIN_SLOPE_MAH) return null
        return dMah / dtH
    }

    companion object {
        private const val TAU_CHARGE_MS = 45_000.0
        private const val TAU_DISCHARGE_MS = 240_000.0
        private const val WINDOW_CHARGE_MS = 6 * 60_000L
        private const val WINDOW_DISCHARGE_MS = 15 * 60_000L
        private const val MIN_SLOPE_SPAN_MS = 90_000L
        private const val MIN_SLOPE_MAH = 5.0
        private const val WARMUP_CHARGE_MS = 25_000L
        private const val WARMUP_DISCHARGE_MS = 90_000L
        private const val RECHECK_CHARGE_MS = 20_000L
        private const val RECHECK_DISCHARGE_MS = 60_000L
        private const val MIN_MA = 20.0

        /**
         * Per-percent integration with a CC→CV taper model.
         * Taper start and floor depend on C-rate: fast chargers start slowing at ~78 % and end at
         * ~15 % of peak current; trickle chargers barely taper at all.
         */
        fun minutesToFull(levelPercent: Int, currentMa: Double, fullMah: Double, targetPercent: Int = 100): Double {
            // Back out the un-tapered (CC) current from what we measure now, then integrate forward.
            val fNow = taperFactor(levelPercent, cRateGuess(levelPercent, currentMa, fullMah))
            val iCc = currentMa / fNow
            val cRate = iCc / fullMah
            val mahPerPercent = fullMah / 100.0
            var hours = 0.0
            for (p in levelPercent until targetPercent.coerceIn(1, 100)) {
                // the current percent is on average half done
                val share = if (p == levelPercent) 0.5 else 1.0
                hours += share * mahPerPercent / (iCc * taperFactor(p, cRate))
            }
            return hours * 60.0
        }

        private fun cRateGuess(level: Int, ma: Double, fullMah: Double): Double {
            // two passes are enough to converge on the un-tapered C-rate
            var c = ma / fullMah
            repeat(2) { c = ma / taperFactor(level, c) / fullMah }
            return c
        }

        /** Fraction of CC current flowing at [percent] for a charger at [cRate]. */
        fun taperFactor(percent: Int, cRate: Double): Double {
            val strength = ((cRate - 0.15) / 0.6).coerceIn(0.0, 1.0)   // 0 = trickle, 1 = ≥0.75 C
            val start = 92.0 - 14.0 * strength                          // 92 % → 78 %
            val floor = 0.6 - 0.45 * strength                           // 0.60 → 0.15
            if (percent <= start) return 1.0
            val x = ((percent - start) / (100.0 - start)).coerceIn(0.0, 1.0)
            return 1.0 - (1.0 - floor) * x
        }

        /** < 1 h → 1 min steps, < 3 h → 5 min, otherwise 10 min. */
        fun roundForDisplay(minutes: Double): Int {
            val step = when {
                minutes < 60 -> 1
                minutes < 180 -> 5
                else -> 10
            }
            return max(step, (minutes / step).roundToInt() * step)
        }
    }
}

/**
 * Tracks a charge session's speed: the peak of *smoothed* power while below 80 %, latched.
 * Using the session average would label every session "slow" because of the CV taper.
 */
class SessionSpeedTracker {
    private var emaW: Double? = null
    private var lastT = 0L
    var sustainedPeakWatts = 0.0; private set
    var energyWh = 0.0; private set
    var chargingMillis = 0L; private set

    private var startLevel = -1

    fun reset() { emaW = null; lastT = 0L; sustainedPeakWatts = 0.0; energyWh = 0.0; chargingMillis = 0L; startLevel = -1 }

    fun update(nowMillis: Long, watts: Double, levelPercent: Int) {
        val w = max(0.0, watts)
        val prev = emaW
        val dt = if (lastT == 0L) 0L else (nowMillis - lastT).coerceIn(0, 60_000)
        emaW = if (prev == null) w else prev + (1 - exp(-dt / 20_000.0)) * (w - prev)
        lastT = nowMillis
        energyWh += w * dt / 3_600_000.0
        chargingMillis += dt
        if (startLevel < 0) startLevel = levelPercent
        // Skip the first 30 s: chargers negotiate (PD/AFC ramp) and the first raw samples are noise.
        if (chargingMillis < 30_000) return
        // Normal case: peak of smoothed power before the CV taper (<80 %).
        // Session started at ≥80 %: there is no pre-taper phase, so use the first 5 minutes.
        val eligible = if (startLevel < 80) levelPercent < 80 else chargingMillis < 5 * 60_000
        if (eligible) sustainedPeakWatts = max(sustainedPeakWatts, min(emaW!!, 200.0))
    }

    val averageWatts: Double get() = if (chargingMillis > 0) energyWh / (chargingMillis / 3_600_000.0) else 0.0
}
