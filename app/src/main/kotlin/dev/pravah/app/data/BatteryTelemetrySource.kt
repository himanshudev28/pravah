package dev.pravah.app.data

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File
import kotlin.math.abs

/**
 * Reads raw battery state and normalises it. Stateless w.r.t. estimation — the repository
 * owns timing, smoothing and ETA.
 *
 * Normalisation rules (learned on-device, persisted by the repository):
 *  - Units: CURRENT_NOW is µA per spec, but Samsung and others report mA. Real phone currents
 *    are 0–15 A, so once any |raw| > 20 000 is seen the device is µA — latched forever.
 *  - Sign: spec says + = charging, many fuel gauges invert it. We vote: while unplugged the
 *    battery must be draining; while CHARGING it must be filling.
 */
class BatteryTelemetrySource(private val context: Context) {

    data class Raw(
        val tMillis: Long,
        val levelPercent: Int,
        val status: Int,
        val plugged: Int,
        val voltageMv: Int,
        val tempTenths: Int,
        val technology: String,
        val health: Int,
        val rawCurrent: Long,
        val counterUah: Long,
        /** OEM flag for a fast-charge adapter (Samsung `hv_charger`: Adaptive / Super Fast). */
        val fastAdapter: Boolean,
    )

    private val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    val designCapacityMah: Double? by lazy { readDesignCapacity() }
    /** Android 14+ exposes the cycle count in the battery intent; older devices via sysfs (often blocked). */
    fun cycleCount(intent: Intent): Int? =
        intent.getIntExtra("android.os.extra.CYCLE_COUNT", -1).takeIf { it in 1..5000 } ?: readCycleCount()

    // Learned normalisation, restored/persisted by the repository
    var unitIsMicro: Boolean = false
    var signScore: Int = 0          // >0 → raw sign is inverted
        private set
    val signInverted: Boolean get() = signScore > 3

    private var lastThermalRead = 0L
    private var lastThermal: Float? = null

    /** [inverted] null = never learned → start neutral so the first unplugged samples decide quickly. */
    fun restoreNormalisation(micro: Boolean, inverted: Boolean?) {
        unitIsMicro = micro
        signScore = when (inverted) { true -> 20; false -> -20; null -> 0 }
    }

    fun read(intent: Intent, now: Long = System.currentTimeMillis()): Raw? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val pct = (level * 100 / scale).coerceIn(0, 100)

        val rawCurrent = try { bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) } catch (_: Exception) { null }
            ?.takeIf { it != Long.MIN_VALUE } ?: 0L
        var counter = try { bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) } catch (_: Exception) { null }
            ?.takeIf { it > 0 && it != Long.MIN_VALUE } ?: 0L
        // A few devices report mAh here; < 50 000 "µAh" (50 mAh) above 5 % is impossible.
        if (counter in 1 until 50_000 && pct > 5) counter *= 1000

        return Raw(
            tMillis = now,
            levelPercent = pct,
            status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0).let { if (it in 1..100) it * 1000 else it },
            tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
            technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.ifBlank { null } ?: "Li-ion",
            health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN),
            rawCurrent = rawCurrent,
            counterUah = counter,
            fastAdapter = intent.getBooleanExtra(EXTRA_SAMSUNG_FAST, false),
        )
    }

    /** Screen on? Used to tell a screen-on dip in charging current from a real slowdown. */
    fun isInteractive(): Boolean = pm?.isInteractive ?: true

    /**
     * Raw → signed mA (+ = into battery). Also updates the learned unit/sign unless [allowSignVote]
     * is false — right after a plug change the fuel gauge keeps reporting the previous current for
     * up to ~30 s (seen on Samsung), which would otherwise vote the sign the wrong way.
     */
    fun normaliseCurrentMa(r: Raw, allowSignVote: Boolean = true): Double {
        if (abs(r.rawCurrent) > 20_000) unitIsMicro = true
        val ma = if (unitIsMicro) r.rawCurrent / 1000.0 else r.rawCurrent.toDouble()

        // Sign voting only on unambiguous evidence
        if (allowSignVote && abs(ma) > 40) {
            val unplugged = r.plugged == 0
            val charging = r.plugged != 0 && r.status == BatteryManager.BATTERY_STATUS_CHARGING && r.levelPercent < 95
            val vote = when {
                unplugged -> if (ma > 0) 1 else -1         // positive while unplugged ⇒ inverted
                charging  -> if (ma < 0) 1 else -1         // negative while charging ⇒ inverted (weak: phone may out-draw charger)
                else -> 0
            }
            signScore = (signScore + vote * if (unplugged) 2 else 1).coerceIn(-20, 20)
        }
        return if (signInverted) -ma else ma
    }

    fun powerState(r: Raw, currentMa: Double): BatteryTelemetry.PowerState = when {
        r.plugged == 0 -> BatteryTelemetry.PowerState.DISCHARGING
        r.status == BatteryManager.BATTERY_STATUS_FULL ||
            (r.levelPercent >= 100 && currentMa < 150) -> BatteryTelemetry.PowerState.FULL
        r.status == BatteryManager.BATTERY_STATUS_CHARGING -> BatteryTelemetry.PowerState.CHARGING
        else -> BatteryTelemetry.PowerState.PLUGGED_IDLE
    }

    fun plugType(plugged: Int): BatteryTelemetry.PlugType = when (plugged) {
        0 -> BatteryTelemetry.PlugType.NONE
        BatteryManager.BATTERY_PLUGGED_AC -> BatteryTelemetry.PlugType.AC
        BatteryManager.BATTERY_PLUGGED_USB -> BatteryTelemetry.PlugType.USB
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> BatteryTelemetry.PlugType.WIRELESS
        8 -> BatteryTelemetry.PlugType.DOCK
        else -> BatteryTelemetry.PlugType.AC
    }

    fun health(code: Int): BatteryTelemetry.BatteryHealth = when (code) {
        BatteryManager.BATTERY_HEALTH_GOOD -> BatteryTelemetry.BatteryHealth.GOOD
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryTelemetry.BatteryHealth.OVERHEAT
        BatteryManager.BATTERY_HEALTH_DEAD -> BatteryTelemetry.BatteryHealth.DEAD
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryTelemetry.BatteryHealth.OVER_VOLTAGE
        BatteryManager.BATTERY_HEALTH_COLD -> BatteryTelemetry.BatteryHealth.COLD
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> BatteryTelemetry.BatteryHealth.FAILURE
        else -> BatteryTelemetry.BatteryHealth.UNKNOWN
    }

    /** getThermalHeadroom is rate-limited by the OS (≈1/s, NaN otherwise) — cache for 10 s. */
    fun thermalHeadroom(now: Long): Float? {
        if (now - lastThermalRead < 10_000) return lastThermal
        lastThermalRead = now
        lastThermal = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                pm?.getThermalHeadroom(0)?.takeIf { !it.isNaN() && it >= 0f } else null
        } catch (_: Exception) { null }
        return lastThermal
    }

    private fun readDesignCapacity(): Double? {
        val paths = listOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/Battery/charge_full_design",
            "/sys/class/power_supply/battery/batt_full_charge_val",
        )
        for (p in paths) {
            try {
                val raw = File(p).readText().trim().toLongOrNull() ?: continue
                val mah = if (raw < 20_000L) raw.toDouble() else raw / 1000.0
                if (mah > 500) return mah
            } catch (_: Exception) { }
        }
        return try {
            val clazz = Class.forName("com.android.internal.os.PowerProfile")
            val profile = clazz.getConstructor(Context::class.java).newInstance(context)
            (clazz.getMethod("getBatteryCapacity").invoke(profile) as Double).takeIf { it > 500 }
        } catch (_: Exception) { null }
    }

    private var cachedCycles: Int? = null
    private var cyclesRead = false
    private fun readCycleCount(): Int? {
        if (cyclesRead) return cachedCycles
        cyclesRead = true
        val paths = listOf(
            "/sys/class/power_supply/battery/cycle_count",
            "/sys/class/power_supply/battery/battery_cycle",
        )
        for (p in paths) {
            try {
                val v = File(p).readText().trim().toIntOrNull()
                if (v != null && v in 1..5000) { cachedCycles = v; return v }
            } catch (_: Exception) { }
        }
        return null
    }

    companion object {
        private const val EXTRA_SAMSUNG_FAST = "hv_charger"

        /** Sticky read of the last ACTION_BATTERY_CHANGED without registering a receiver. */
        fun stickyIntent(context: Context): Intent? =
            context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }
}
