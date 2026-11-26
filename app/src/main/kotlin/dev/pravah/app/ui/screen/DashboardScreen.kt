package dev.pravah.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.pravah.app.data.BatteryTelemetry
import dev.pravah.app.data.BatteryTelemetry.PowerState
import dev.pravah.app.ui.components.ChargeBar
import dev.pravah.app.ui.components.CurrentChart
import dev.pravah.app.ui.components.DetailRow
import dev.pravah.app.ui.components.ScreenColumn
import dev.pravah.app.ui.components.SectionLabel
import dev.pravah.app.ui.components.clockTime
import dev.pravah.app.ui.components.duration
import dev.pravah.app.ui.components.plugLabel
import dev.pravah.app.ui.components.signed
import dev.pravah.app.ui.components.temperature
import dev.pravah.app.ui.theme.BigLine
import dev.pravah.app.ui.theme.HeroNumber
import dev.pravah.app.ui.theme.HeroUnit
import dev.pravah.app.ui.theme.pravah
import dev.pravah.app.ui.viewmodel.UiState
import dev.pravah.engine.EstimateKind
import java.util.Locale

@Composable
fun DashboardScreen(state: UiState) {
    val t = state.telemetry
    ScreenColumn("Pravah") {
        if (t == null) {
            Text("Reading battery…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.pravah.dim,
                modifier = Modifier.padding(top = 48.dp))
            return@ScreenColumn
        }
        val colors = MaterialTheme.pravah
        val accent = when (t.powerState) {
            PowerState.CHARGING -> colors.charge
            PowerState.DISCHARGING -> colors.drain
            else -> colors.idle
        }
        val ctx = LocalContext.current

        Hero(t, accent)

        // Estimate (the only place time remaining appears)
        val est = t.estimate
        val estMin = est.minutes
        val limit = t.chargeLimit
        val line: String?
        val sub: String?
        when {
            t.heldAtLimit -> {
                line = "Paused at ${t.levelPercent}%"
                sub = if (t.heldBySystem) "Battery protection is holding the charge"
                    else "Charge limit reached · ${t.stopPercent}%"
            }
            est.calculating && est.kind != EstimateKind.NONE -> { line = "Calculating…"; sub = null }
            est.kind == EstimateKind.TO_FULL && estMin != null && limit.isLimited -> {
                line = "${duration(estMin)} to ${limit.percent}%"
                sub = est.atMillis?.let { "Stops at ${limit.percent}% around ${clockTime(ctx, it)}" }
            }
            est.kind == EstimateKind.TO_FULL && estMin != null -> {
                line = "${duration(estMin)} to full"
                sub = est.atMillis?.let { "Full by ${clockTime(ctx, it)}" }
            }
            est.kind == EstimateKind.TO_EMPTY && estMin != null -> {
                line = "~${duration(estMin)} left"
                sub = est.atMillis?.let { "Lasts until ~${clockTime(ctx, it)}" }
            }
            else -> { line = null; sub = null }
        }
        if (line != null) {
            Text(line, style = BigLine, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 20.dp))
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyLarge, color = colors.dim, modifier = Modifier.padding(top = 2.dp))
        }

        ChargeBar(
            percent = t.levelPercent,
            fill = accent,
            track = colors.hairline,
            tick = colors.dim,
            alarmPercent = if (state.alarmEnabled) state.alarmPercent else null,
            modifier = Modifier.padding(top = 28.dp),
            limitPercent = t.stopPercent.takeIf { it < 100 },
        )
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (state.alarmEnabled) "Alarm ${state.alarmPercent}%" else "",
                style = MaterialTheme.typography.labelSmall, color = colors.dim,
            )
            val caption = when {
                t.stopPercent < 100 && t.stopPercent == t.systemLimitPercent -> "Stops at ${t.stopPercent}% · Battery protection"
                t.stopPercent < 100 -> "Stops at ${t.stopPercent}%"
                t.isPluggedIn -> "80% · charging slows"
                else -> null
            }
            if (caption != null) Text(caption, style = MaterialTheme.typography.labelSmall, color = colors.dim)
        }

        SectionLabel("Current")
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
            Text(
                if (t.currentSettling) "Settling…" else "${signed(t.currentMa)} mA",
                style = MaterialTheme.typography.titleLarge,
                color = if (t.currentSettling) colors.dim else accent,
            )
        }
        CurrentChart(state.currentHistory, accent)

        SectionLabel("Live")
        val power = remember(t.watts) { "${signed(t.watts, 1)} W" }
        val rate = remember(t.ratePercentPerHour) {
            t.ratePercentPerHour?.let { "${signed(it, 1)} %/h" } ?: "—"
        }
        if (t.isPluggedIn) DetailRow("Charger", chargerLabel(t))
        DetailRow(if (t.watts >= 0) "Into battery" else "Battery draw", power)
        DetailRow("Rate", rate)
        DetailRow("Voltage", String.format(Locale.getDefault(), "%.2f V", t.voltageMillivolts / 1000.0))
        DetailRow("Temperature", temperature(t.temperatureCelsius, state.useFahrenheit), last = true)
    }
}

private fun chargerLabel(t: BatteryTelemetry): String {
    val kind = when {
        t.plugType == BatteryTelemetry.PlugType.WIRELESS && t.fastAdapter -> "Fast wireless"
        t.plugType == BatteryTelemetry.PlugType.WIRELESS -> "Wireless"
        t.fastAdapter -> "Fast charger"
        else -> "Standard charger"
    }
    return if (t.plugType == BatteryTelemetry.PlugType.WIRELESS) kind else "$kind · ${plugLabel(t.plugType)}"
}

@Composable
private fun Hero(t: BatteryTelemetry, accent: androidx.compose.ui.graphics.Color) {
    val status = when (t.powerState) {
        PowerState.CHARGING -> (t.chargingSpeed?.let { "${it.label} charging" } ?: "Charging") + " · " + plugLabel(t.plugType)
        PowerState.FULL -> "Fully charged"
        PowerState.PLUGGED_IDLE ->
            if (t.heldAtLimit) "Charging paused · " + plugLabel(t.plugType) else "Plugged in · not charging"
        PowerState.DISCHARGING -> "On battery"
    }
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "${t.levelPercent} percent. $status" }) {
        Row(Modifier.padding(top = 20.dp).clearAndSetSemantics { }) {
            Text(
                "${t.levelPercent}",
                style = HeroNumber,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                "%",
                style = HeroUnit,
                color = MaterialTheme.pravah.dim,
                modifier = Modifier.alignByBaseline().padding(start = 4.dp),
            )
        }
        Text(status, style = MaterialTheme.typography.titleMedium, color = accent, modifier = Modifier.padding(top = 8.dp))
    }
}
