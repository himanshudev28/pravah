package dev.pravah.app.data.repository

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import dev.pravah.app.data.BatteryTelemetry
import dev.pravah.app.data.BatteryTelemetrySource
import dev.pravah.app.data.ChargeLimit
import dev.pravah.app.data.CurrentPoint
import dev.pravah.app.data.SystemChargeLimitReader
import dev.pravah.app.data.db.BatteryDatabase
import dev.pravah.app.data.db.ChargeSession
import dev.pravah.app.data.store.AppPreferences
import dev.pravah.engine.CapacityEstimator
import dev.pravah.engine.ChargeEstimator
import dev.pravah.engine.ChargingSpeed
import dev.pravah.engine.Estimate
import dev.pravah.engine.EstimateKind
import dev.pravah.engine.FlowMode
import dev.pravah.engine.SessionSpeedTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.exp

/**
 * Process-wide single source of truth. The foreground service and the UI share ONE instance,
 * so there is exactly one sampler, one estimator and one session recorder.
 * (Previously each created its own → double polling and duplicate history rows.)
 *
 * Sampling is adaptive for battery efficiency:
 *   UI visible → 1 s · plugged in (background) → 10 s · on battery (background) → 60 s.
 * Plug/unplug/level broadcasts and battery-protection setting changes wake the sampler immediately.
 */
class BatteryRepository private constructor(context: Context) {

    companion object {
        @Volatile private var INSTANCE: BatteryRepository? = null
        fun get(context: Context): BatteryRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BatteryRepository(context.applicationContext).also { INSTANCE = it; it.start() }
            }

        private const val HISTORY_WINDOW_MS = 10 * 60_000L
        private const val MEDIAN_N = 5

        /** A cable wiggle shorter than this continues the same charge session. */
        private const val MERGE_GRACE_MS = 2 * 60_000L
        /** After a plug change the gauge may repeat the old current for up to ~30 s (Samsung). */
        private const val SETTLE_MAX_MS = 60_000L
        /** No sign learning this long after a plug change, stale or not. */
        private const val SIGN_QUIET_MS = 45_000L
        /** Screen-off charging rate stays valid as an ETA floor this long after the screen turns on. */
        private const val IDLE_FLOOR_VALID_MS = 10 * 60_000L
        private const val IDLE_TAU_MS = 60_000.0
        /** Held below 100 % while plugged this long ⇒ learn it as the phone's charge limit. */
        private const val LEARN_HOLD_MS = 3 * 60_000L
    }

    private val app = context
    private val prefs = AppPreferences(app)
    private val db = BatteryDatabase.get(app)
    private val source = BatteryTelemetrySource(app)
    private val limitReader = SystemChargeLimitReader(app.contentResolver)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _telemetry = MutableStateFlow<BatteryTelemetry?>(null)
    val telemetry: StateFlow<BatteryTelemetry?> = _telemetry.asStateFlow()

    private val _history = MutableStateFlow<List<CurrentPoint>>(emptyList())
    val currentHistory: StateFlow<List<CurrentPoint>> = _history.asStateFlow()

    val sessions = db.chargeSessionDao().observeAll()
    val designCapacityMah = prefs.designCapacityMah
    val capacityEstMah = prefs.capacityEstMah
    val capacityEstCount = prefs.capacityEstCount
    val alarmPercent = prefs.alarmPercent
    val alarmEnabled = prefs.alarmEnabled
    val useFahrenheit = prefs.useFahrenheit
    val amoledTheme = prefs.amoledTheme
    val notificationLive = prefs.notificationLive
    val themeMode = prefs.themeMode
    val chargeLimitManual = prefs.chargeLimitManual
    private val _systemChargeLimit = MutableStateFlow<Int?>(null)
    /** Limit set by the phone's battery protection (100 = charges fully), null if the phone doesn't expose one. */
    val systemChargeLimit: StateFlow<Int?> = _systemChargeLimit.asStateFlow()

    // ── sampler state (only touched from the sampler coroutine) ─────────────
    private val uiVisible = MutableStateFlow(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var lastIntent: Intent? = null
    @Volatile private var learnedCapacityMah: Double? = null
    @Volatile private var userDesignMah: Double? = null
    @Volatile private var systemLimit: Int? = null
    @Volatile private var manualLimit: Int = 0
    @Volatile private var learnedLimit: Int = 0

    private val estimator = ChargeEstimator()
    private val speedTracker = SessionSpeedTracker()
    private val recent = ArrayDeque<Pair<Long, Double>>(MEDIAN_N)
    private var lastPlugged = -1
    private var savedMicro: Boolean? = null
    private var savedInverted: Boolean? = null

    // stale-current guard
    private var plugChangeAt = 0L
    private var preChangeRaw: Long? = null
    private var lastRaw: Long? = null
    private var settling = false

    // screen-off charging rate (ETA floor while the user glances at the phone)
    private var idleMa: Double? = null
    private var idleT = 0L
    private var idleN = 0
    private var idleLevel = 0

    // learned limit on phones without a readable setting
    private var heldSince = 0L
    private var heldLevel = -1
    /** Set from any thread by [resetAllData]; applied on the sampler coroutine. */
    @Volatile private var resetRequested = false
    private var lastLimitRead = 0L

    private data class OpenSession(
        val startMillis: Long, val startPercent: Int, val startCounterUah: Long, val plugType: BatteryTelemetry.PlugType,
    )
    /** Cable came out; the session is finished only if it stays out for [MERGE_GRACE_MS]. */
    private data class PendingEnd(val atMillis: Long, val percent: Int, val counterUah: Long)
    private var session: OpenSession? = null
    private var pendingEnd: PendingEnd? = null
    private var sessionFastAdapter = false
    private var lastChargingMillis = 0L

    fun setUiVisible(visible: Boolean) {
        uiVisible.value = visible
        wake.trySend(Unit)
    }

    private fun start() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val prev = lastIntent
                lastIntent = intent
                // Wake immediately on meaningful changes; ignore voltage/temperature-only chatter.
                if (prev == null ||
                    prev.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) != intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ||
                    prev.getIntExtra(BatteryManager.EXTRA_STATUS, -1) != intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ||
                    prev.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) != intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                ) wake.trySend(Unit)
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        lastIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)

        // Battery protection: read now and whenever the user changes it in system settings.
        systemLimit = limitReader.read().also { _systemChargeLimit.value = it }
        limitReader.observe {
            systemLimit = limitReader.read().also { _systemChargeLimit.value = it }
            wake.trySend(Unit)
        }

        prefs.capacityEstMah.onEach { learnedCapacityMah = it.toDouble().takeIf { v -> v > 500 } }.launchIn(scope)
        prefs.designCapacityMah.onEach { userDesignMah = it.toDouble().takeIf { v -> v > 500 } }.launchIn(scope)
        prefs.chargeLimitManual.onEach { manualLimit = it; wake.trySend(Unit) }.launchIn(scope)
        prefs.chargeLimitLearned.onEach { learnedLimit = it }.launchIn(scope)

        scope.launch {
            val micro = prefs.currentUnitMicro.first()
            val inverted = prefs.currentSignInverted.first()
            source.restoreNormalisation(micro, inverted)
            savedMicro = micro; savedInverted = inverted
            // Resume a charge session that was open when the process died.
            prefs.openSession.first()?.split('|')?.let { p ->
                runCatching {
                    session = OpenSession(p[0].toLong(), p[1].toInt(), p[2].toLong(), BatteryTelemetry.PlugType.valueOf(p[3]))
                    if (p.size >= 7) pendingEnd = PendingEnd(p[4].toLong(), p[5].toInt(), p[6].toLong())
                }
            }
            session?.let { lastChargingMillis = pendingEnd?.atMillis ?: System.currentTimeMillis() }

            while (isActive) {
                val intent = lastIntent ?: BatteryTelemetrySource.stickyIntent(app)
                if (intent != null) runCatching { sample(intent) }
                val plugged = (lastIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
                val interval = when {
                    uiVisible.value -> 1_000L
                    plugged -> 10_000L
                    else -> 60_000L
                }
                withTimeoutOrNull(interval) { wake.receive() }
            }
        }
    }

    private fun currentLimit(): ChargeLimit {
        val manual = manualLimit
        val sys = systemLimit
        val learned = learnedLimit
        return when {
            manual in 50..100 -> ChargeLimit(manual, ChargeLimit.Source.MANUAL)
            sys != null -> ChargeLimit(sys, ChargeLimit.Source.SYSTEM)
            learned in 50..99 -> ChargeLimit(learned, ChargeLimit.Source.LEARNED)
            else -> ChargeLimit.NONE
        }
    }

    private suspend fun sample(intent: Intent) {
        if (resetRequested) {
            resetRequested = false
            session = null; pendingEnd = null; sessionFastAdapter = false
            speedTracker.reset(); estimator.reset()
            idleMa = null; idleN = 0; heldSince = 0L; heldLevel = -1
            learnedLimit = 0
        }
        val r = source.read(intent) ?: return
        val now = r.tMillis

        // ── plug change: drop stale smoothing, start the settling window ──────
        if (r.plugged != lastPlugged) {
            recent.clear()
            if (lastPlugged != -1) {
                plugChangeAt = now
                preChangeRaw = lastRaw
                settling = true
            }
            lastPlugged = r.plugged
        }
        val sinceChange = now - plugChangeAt
        val plugged = r.plugged != 0
        val statusCharging = r.status == BatteryManager.BATTERY_STATUS_CHARGING

        // Settling: the gauge still reports the pre-change current (same raw value, or a sign that
        // contradicts the new state). Those samples are not fed to anything.
        val provisionalMa = source.normaliseCurrentMa(r, allowSignVote = false)
        if (settling) {
            val repeatsOld = r.rawCurrent == preChangeRaw
            val contradicts = (!plugged && provisionalMa > 40) || (plugged && statusCharging && provisionalMa < -40)
            if (sinceChange > SETTLE_MAX_MS || !(repeatsOld || contradicts)) settling = false
        }
        lastRaw = r.rawCurrent
        val stale = settling

        val instMa = if (stale) provisionalMa
            else source.normaliseCurrentMa(r, allowSignVote = sinceChange > SIGN_QUIET_MS)

        val currentMa: Double
        if (stale) {
            currentMa = 0.0
        } else {
            // Median-of-5 de-spike, only across samples close in time.
            while (recent.isNotEmpty() && now - recent.first().first > 8_000) recent.removeFirst()
            recent.addLast(now to instMa)
            while (recent.size > MEDIAN_N) recent.removeFirst()
            currentMa = recent.map { it.second }.sorted()[recent.size / 2]
        }

        val state = source.powerState(r, if (stale) 0.0 else currentMa)
        val plug = source.plugType(r.plugged)
        val watts = currentMa / 1000.0 * (r.voltageMv / 1000.0)

        // counter/level is too quantised at low levels (±1 % of a small number) to be useful
        val counterFull = if (r.counterUah > 0 && r.levelPercent >= 30) r.counterUah / 1000.0 / (r.levelPercent / 100.0) else null
        val design = userDesignMah ?: source.designCapacityMah
        val fullMah = learnedCapacityMah ?: counterFull ?: design

        val mode = when (state) {
            BatteryTelemetry.PowerState.CHARGING -> FlowMode.CHARGING
            BatteryTelemetry.PowerState.DISCHARGING -> FlowMode.DISCHARGING
            else -> FlowMode.IDLE
        }

        // ── charge limit ─────────────────────────────────────────────────────
        // The ContentObserver can be delivered late (seen ~13 s on One UI); a cheap re-read every 5 s backs it up.
        if (now - lastLimitRead >= 5_000) {
            lastLimitRead = now
            limitReader.read().let { if (it != systemLimit) { systemLimit = it; _systemChargeLimit.value = it } }
        }
        learnLimit(now, plugged, state, r.levelPercent, if (stale) null else currentMa, interactive = source.isInteractive())
        val limit = currentLimit()

        // ── screen-off charging rate → ETA floor while the screen is on ──────
        val interactive = source.isInteractive()
        if (mode != FlowMode.CHARGING) { idleMa = null; idleN = 0 }
        else if (!stale && !interactive) {
            val prev = idleMa
            val dt = (now - idleT).coerceIn(1, 60_000).toDouble()
            idleMa = if (prev == null) instMa else prev + (1 - exp(-dt / IDLE_TAU_MS)) * (instMa - prev)
            idleT = now; idleN++; idleLevel = r.levelPercent
        }
        // Only near the level it was measured at: higher up the CV taper would make it overstate the rate.
        val idleFloor = idleMa?.takeIf {
            interactive && idleN >= 3 && now - idleT < IDLE_FLOOR_VALID_MS && r.levelPercent - idleLevel <= 3
        }

        val estimate = if (stale) {
            when (mode) {
                FlowMode.CHARGING -> Estimate(EstimateKind.TO_FULL, null, null, calculating = true)
                FlowMode.DISCHARGING -> Estimate(EstimateKind.TO_EMPTY, null, null, calculating = true)
                FlowMode.IDLE -> Estimate.NONE
            }
        } else estimator.update(now, mode, r.levelPercent, instMa, r.counterUah, fullMah, limit.percent, idleFloor)
        val rate = estimator.smoothedCurrentMa()?.let { ma ->
            if (fullMah != null && mode != FlowMode.IDLE && !stale) ma / fullMah * 100.0 else null
        }

        // ── session tracking: plugged → unplugged (with a grace period for cable wiggles) ──
        val speed: ChargingSpeed?
        var finished: Pair<OpenSession, PendingEnd>? = null
        if (plugged) {
            val p = pendingEnd
            val s = session
            if (p != null && s != null && now - p.atMillis >= MERGE_GRACE_MS) {
                // Out longer than the grace period (sampler may have slept through it): close the old one.
                finished = s to p
                session = null; pendingEnd = null
            } else if (p != null) {
                // Re-plugged within the grace period: same session continues (adapter re-detected below).
                pendingEnd = null
                sessionFastAdapter = false
                s?.let { prefs.setOpenSession(encode(it, null)) }
            }
            val late = finished
            if (late != null) {
                prefs.setOpenSession(null)
                try { finishSession(late.first, late.second) } catch (e: Exception) { android.util.Log.w("Pravah", "session save failed", e) }
                finished = null
            }
            if (session == null) {
                val s = OpenSession(now, r.levelPercent, r.counterUah, plug)
                session = s
                speedTracker.reset()
                sessionFastAdapter = false
                lastChargingMillis = now
                prefs.setOpenSession(encode(s, null))
            }
            if (r.fastAdapter) sessionFastAdapter = true
            if (state == BatteryTelemetry.PowerState.CHARGING) {
                if (!stale) speedTracker.update(now, watts, r.levelPercent)
                lastChargingMillis = now
            }
            val wireless = plug == BatteryTelemetry.PlugType.WIRELESS
            speed = when {
                speedTracker.chargingMillis >= 30_000 ->
                    ChargingSpeed.classify(speedTracker.sustainedPeakWatts, wireless, sessionFastAdapter)
                sessionFastAdapter -> ChargingSpeed.FAST
                else -> null
            }
        } else {
            speed = null
            val s = session
            if (s != null) {
                val p = pendingEnd ?: PendingEnd(now, r.levelPercent, r.counterUah).also {
                    pendingEnd = it
                    prefs.setOpenSession(encode(s, it))
                }
                if (now - p.atMillis >= MERGE_GRACE_MS) {
                    finished = s to p
                    session = null      // cleared BEFORE persisting so a failed write can never be retried into a duplicate
                    pendingEnd = null
                }
            }
        }

        _telemetry.value = BatteryTelemetry(
            timestampMillis = now,
            levelPercent = r.levelPercent,
            powerState = state,
            plugType = plug,
            voltageMillivolts = r.voltageMv,
            temperatureTenths = r.tempTenths,
            technology = r.technology,
            batteryHealth = source.health(r.health),
            currentMa = currentMa,
            watts = watts,
            ratePercentPerHour = rate,
            chargingSpeed = if (state == BatteryTelemetry.PowerState.CHARGING) speed else null,
            estimate = estimate,
            chargeLimit = limit,
            systemLimitPercent = systemLimit,
            fastAdapter = plugged && r.fastAdapter,
            currentSettling = stale,
            mAhRemaining = r.counterUah / 1000.0,
            estimatedFullMah = counterFull,
            designCapacityMah = source.designCapacityMah,
            cycleCount = source.cycleCount(intent),
            thermalHeadroom = source.thermalHeadroom(now),
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            androidVersion = Build.VERSION.SDK_INT,
        )

        val done = finished
        if (done != null) {
            prefs.setOpenSession(null)
            try { finishSession(done.first, done.second) } catch (e: Exception) { android.util.Log.w("Pravah", "session save failed", e) }
        }

        // Chart buffer (settling samples are skipped — they are the previous state's current)
        if (!stale) {
            val cutoff = now - HISTORY_WINDOW_MS
            val hist = _history.value
            val firstKept = hist.indexOfFirst { it.tMillis >= cutoff }.let { if (it < 0) hist.size else it }
            _history.value = hist.subList(firstKept, hist.size) + CurrentPoint(now, currentMa.toFloat())
        }

        // Persist learned normalisation only when it changes (DataStore writes are not free).
        if (source.unitIsMicro != savedMicro || source.signInverted != savedInverted) {
            savedMicro = source.unitIsMicro; savedInverted = source.signInverted
            prefs.saveCurrentNormalisation(source.unitIsMicro, source.signInverted)
        }
    }

    /**
     * Phones without a readable protection setting: if the phone sits plugged in, not charging,
     * below 100 % for a few minutes, that level is its limit. Charging past it clears the guess.
     */
    private suspend fun learnLimit(
        now: Long, plugged: Boolean, state: BatteryTelemetry.PowerState, level: Int, currentMa: Double?, interactive: Boolean,
    ) {
        if (systemLimit != null) return
        // A real hold: screen off, almost no current, level steady. (Not "phone out-draws a weak charger".)
        val genuineHold = plugged && state == BatteryTelemetry.PowerState.PLUGGED_IDLE && level in 50..99 &&
            !interactive && currentMa != null && kotlin.math.abs(currentMa) < 50
        if (genuineHold && heldSince != 0L && level != heldLevel) heldSince = 0L
        if (genuineHold) {
            if (heldSince == 0L) { heldSince = now; heldLevel = level }
            if (now - heldSince >= LEARN_HOLD_MS && learnedLimit != level) {
                learnedLimit = level; prefs.setChargeLimitLearned(level)
            }
        } else {
            heldSince = 0L
            if (state == BatteryTelemetry.PowerState.CHARGING && learnedLimit in 1..99 && level > learnedLimit) {
                learnedLimit = 0; prefs.setChargeLimitLearned(0)
            }
        }
    }

    private fun encode(s: OpenSession, p: PendingEnd?): String =
        "${s.startMillis}|${s.startPercent}|${s.startCounterUah}|${s.plugType.name}" +
            (p?.let { "|${it.atMillis}|${it.percent}|${it.counterUah}" } ?: "")

    private suspend fun finishSession(s: OpenSession, end: PendingEnd) {
        val mAhAdded = if (s.startCounterUah > 0 && end.counterUah > 0) (end.counterUah - s.startCounterUah) / 1000.0 else 0.0
        val gained = end.percent - s.startPercent
        if (gained < 1 && mAhAdded < 20) { speedTracker.reset(); sessionFastAdapter = false; return }  // brief plug-in, nothing charged

        val design = userDesignMah ?: source.designCapacityMah ?: 0.0
        db.chargeSessionDao().insert(
            ChargeSession(
                startMillis = s.startMillis,
                // Don't count time parked at the limit — end at the last moment energy flowed in.
                endMillis = lastChargingMillis.coerceIn(s.startMillis, end.atMillis),
                startPercent = s.startPercent,
                endPercent = end.percent,
                mAhAdded = mAhAdded.coerceAtLeast(0.0),
                wearPermille = if (design > 0) (mAhAdded / design * 1000).toInt().coerceAtLeast(0) else 0,
                peakWatts = speedTracker.sustainedPeakWatts,
                avgWatts = speedTracker.averageWatts,
                plugType = s.plugType.name,
                fastCharger = sessionFastAdapter,
            )
        )

        if (s.startCounterUah > 0 && end.counterUah > 0) {
            val state = CapacityEstimator.EstimatorState(
                estimatedCapacityMah = prefs.capacityEstMah.first().toDouble().takeIf { it > 0 },
                sessionCount = prefs.capacityEstCount.first(),
            )
            val next = CapacityEstimator.addSession(
                state,
                CapacityEstimator.SessionData(s.startPercent, end.percent, s.startCounterUah, end.counterUah),
            )
            if (next !== state) next.estimatedCapacityMah?.let { prefs.saveCapacityEstimate(it.toFloat(), next.sessionCount) }
        }
        speedTracker.reset()
        sessionFastAdapter = false
    }

    // Settings write-through
    suspend fun setAlarmPercent(v: Int) = prefs.setAlarmPercent(v)
    suspend fun setAlarmEnabled(v: Boolean) = prefs.setAlarmEnabled(v)
    suspend fun setUseFahrenheit(v: Boolean) = prefs.setUseFahrenheit(v)
    suspend fun setAmoledTheme(v: Boolean) = prefs.setAmoledTheme(v)
    suspend fun setNotificationLive(v: Boolean) = prefs.setNotificationLive(v)
    suspend fun setDesignCapacityMah(v: Float) = prefs.setDesignCapacityMah(v)
    suspend fun setThemeMode(v: Int) = prefs.setThemeMode(v)
    suspend fun setChargeLimitManual(v: Int) = prefs.setChargeLimitManual(v)
    suspend fun resetAllData() {
        prefs.resetAll()
        db.chargeSessionDao().deleteAll()
        savedMicro = null; savedInverted = null   // re-persist learned normalisation on next sample
        resetRequested = true
        wake.trySend(Unit)
    }
}
