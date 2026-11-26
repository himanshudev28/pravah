package dev.pravah.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.pravah.app.MainActivity
import dev.pravah.app.R
import dev.pravah.app.data.BatteryTelemetry
import dev.pravah.app.data.repository.BatteryRepository
import dev.pravah.engine.EstimateKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Keeps the shared [BatteryRepository] alive in the background so charge sessions are recorded
 * even when the UI is closed. Notification is re-posted only when its text actually changes.
 */
class BatteryMonitorService : LifecycleService() {

    companion object {
        const val CHANNEL_LIVE = "pravah_live"
        const val CHANNEL_ALARM = "pravah_alarm"
        const val NOTIF_ID_LIVE = 1
        const val NOTIF_ID_ALARM = 2
    }

    private lateinit var repo: BatteryRepository
    private lateinit var nm: NotificationManager
    private var job: Job? = null
    private var lastText: String? = null
    private var alarmFiredThisPlug = false

    override fun onCreate() {
        super.onCreate()
        repo = BatteryRepository.get(applicationContext)
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Re-posted on every start command (Activity recreation calls startForegroundService again);
        // forget the last text so the real status replaces the placeholder on the next sample.
        val placeholder = buildLive("Battery monitor", "Reading battery…")
        lastText = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID_LIVE, placeholder, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIF_ID_LIVE, placeholder)

        if (job == null) job = lifecycleScope.launch {
            combine(repo.telemetry, repo.alarmPercent, repo.alarmEnabled, repo.notificationLive) { t, pct, en, live ->
                Quad(t, pct, en, live)
            }.collect { (t, alarmPct, alarmOn, live) ->
                if (t == null) return@collect

                if (!t.isPluggedIn) alarmFiredThisPlug = false
                if (alarmOn && t.isPluggedIn && !alarmFiredThisPlug && t.levelPercent >= alarmPct) {
                    alarmFiredThisPlug = true
                    nm.notify(NOTIF_ID_ALARM, buildAlarm(alarmPct))
                }

                // The foreground notification must stay while the service runs; when the user turned
                // "live" off we show a static, minimal one instead of cancelling (which Android forbids).
                val (title, text) = if (live) describe(t) else "Pravah" to "Monitoring battery"
                val key = "$title|$text"
                if (key != lastText) {
                    lastText = key
                    nm.notify(NOTIF_ID_LIVE, buildLive(title, text))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    /** Coarse text (rounded) so the notification doesn't re-post every second. */
    private fun describe(t: BatteryTelemetry): Pair<String, String> {
        val est = t.estimate
        val eta = est.minutes?.let { formatMinutes(it) }
        return when (t.powerState) {
            BatteryTelemetry.PowerState.CHARGING -> {
                val speed = t.chargingSpeed?.label?.let { "$it · " } ?: ""
                "Charging · ${t.levelPercent}%" to buildString {
                    append(speed)
                    append("%.0f W".format(abs(t.watts)))
                    val to = if (t.chargeLimit.isLimited) "${t.chargeLimit.percent}%" else "full"
                    if (est.kind == EstimateKind.TO_FULL) append(if (eta != null) " · $to in $eta" else " · calculating time")
                }
            }
            BatteryTelemetry.PowerState.FULL -> "Fully charged" to "100% · you can unplug"
            BatteryTelemetry.PowerState.PLUGGED_IDLE ->
                if (t.heldAtLimit) "Paused at ${t.levelPercent}%" to "Battery protection is holding the charge"
                else "Plugged in · ${t.levelPercent}%" to "Not charging right now"
            BatteryTelemetry.PowerState.DISCHARGING -> "On battery · ${t.levelPercent}%" to buildString {
                append("${(abs(t.currentMa) / 10).roundToInt() * 10} mA draw")
                if (eta != null) append(" · about $eta left")
            }
        }
    }

    private fun formatMinutes(m: Int): String = if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"

    private fun createChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LIVE, "Live battery status", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "Charge alarm", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun contentIntent(req: Int) = PendingIntent.getActivity(
        this, req, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildLive(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent(0))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    private fun buildAlarm(targetPercent: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("Reached $targetPercent%")
            .setContentText("Unplug now to reduce battery wear.")
            .setAutoCancel(true)
            .setContentIntent(contentIntent(1))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

    private data class Quad(val t: BatteryTelemetry?, val pct: Int, val en: Boolean, val live: Boolean)
}
