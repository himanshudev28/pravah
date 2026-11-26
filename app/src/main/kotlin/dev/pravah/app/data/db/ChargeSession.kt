package dev.pravah.app.data.db

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import dev.pravah.engine.ChargingSpeed

@Entity(tableName = "charge_sessions")
data class ChargeSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMillis: Long,
    val endMillis: Long,
    val startPercent: Int,
    val endPercent: Int,
    /** mAh actually added, derived from charge_counter delta */
    val mAhAdded: Double,
    /** Per-mille of design capacity consumed this session (wear indicator) */
    val wearPermille: Int,
    /** Sustained (smoothed, pre-80 %) peak power. 0 = unknown (legacy rows). */
    val peakWatts: Double,
    /** Energy-weighted average power over the session. */
    val avgWatts: Double,
    /** BatteryTelemetry.PlugType name, or "UNKNOWN" for legacy rows. */
    val plugType: String = "UNKNOWN",
    /** The OS reported a fast-charge adapter during the session (Samsung hv_charger). */
    val fastCharger: Boolean = false,
) {
    @Ignore val durationMillis: Long = endMillis - startMillis

    /** Null when the session predates speed tracking (peakWatts == 0). */
    @Ignore val speed: ChargingSpeed? =
        if (peakWatts > 0.5) ChargingSpeed.classify(peakWatts, wireless = plugType == "WIRELESS", fastAdapter = fastCharger) else null
}
