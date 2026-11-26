package dev.pravah.app.ui.screen

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.pravah.app.data.db.ChargeSession
import dev.pravah.app.ui.components.Hairline
import dev.pravah.app.ui.components.SectionLabel
import dev.pravah.app.ui.components.clockTime
import dev.pravah.app.ui.components.duration
import dev.pravah.app.ui.components.plugLabel
import dev.pravah.app.ui.theme.Figure
import dev.pravah.app.ui.theme.pravah
import dev.pravah.app.ui.viewmodel.UiState
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

private fun dayStart(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
fun HistoryScreen(state: UiState) {
    val ctx = LocalContext.current
    val sessions = remember(state.sessions) { state.sessions.sortedByDescending { it.startMillis } }
    // Keyed on the session list so "Today"/"Yesterday" refresh after midnight when data changes or screen reopens.
    val today = remember(sessions) { dayStart(System.currentTimeMillis()) }
    val groups = remember(sessions) { sessions.groupBy { dayStart(it.startMillis) }.toList() }
    val weekCount = remember(sessions) {
        val cutoff = System.currentTimeMillis() - 7 * DateUtils.DAY_IN_MILLIS
        sessions.filter { it.startMillis >= cutoff }
    }
    val dim = MaterialTheme.pravah.dim

    // Lazy: only visible rows are composed, so years of history stay cheap.
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        item(key = "title") {
            Text(
                "History", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() },
            )
        }
        if (sessions.isEmpty()) {
            item(key = "empty") {
                Column {
                    Text("No charge sessions yet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 48.dp))
                    Text(
                        "Plug in and charge for a few minutes. Each session is recorded here once you unplug.",
                        style = MaterialTheme.typography.bodyLarge, color = dim,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            return@LazyColumn
        }
        item(key = "summary") {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column {
                    Text("${weekCount.size}", style = Figure, color = MaterialTheme.colorScheme.onBackground)
                    Text("sessions this week", style = MaterialTheme.typography.bodyMedium, color = dim)
                }
                Column {
                    val avg = if (weekCount.isEmpty()) "—" else String.format(Locale.getDefault(), "%,d", weekCount.map { it.mAhAdded }.average().roundToInt())
                    Text(avg, style = Figure, color = MaterialTheme.colorScheme.onBackground)
                    Text("avg mAh / session", style = MaterialTheme.typography.bodyMedium, color = dim)
                }
            }
        }
        groups.forEach { (day, list) ->
            item(key = "day-$day") {
                val label = when (day) {
                    today -> "Today"
                    today - DateUtils.DAY_IN_MILLIS -> "Yesterday"
                    else -> DateUtils.formatDateTime(ctx, day, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL)
                }
                SectionLabel(label)
            }
            itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                SessionRow(s, clockTime(ctx, s.startMillis), last = i == list.lastIndex)
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SessionRow(s: ChargeSession, startTime: String, last: Boolean) {
    val mins = (s.durationMillis / 60_000L).toInt().coerceAtLeast(1)
    val dim = MaterialTheme.pravah.dim
    val hours = s.durationMillis / 3_600_000.0
    // Legacy rows have no measured watts: derive real average power from mAh ÷ time at ~3.85 V nominal.
    val avgW = if (s.avgWatts > 0.5) s.avgWatts else if (hours > 0.02) s.mAhAdded / hours * 3.85 / 1000.0 else 0.0
    val speedLine = when {
        s.speed != null -> String.format(Locale.getDefault(), "%s · %.0f W peak", s.speed.label, s.peakWatts)
        avgW > 0.5 -> String.format(Locale.getDefault(), "≈ %.1f W average", avgW)
        else -> ""
    }
    val speed = s.speed?.label ?: "unknown"
    val peak = speedLine
    val added = String.format(Locale.getDefault(), "+%,d mAh", s.mAhAdded.roundToInt())
    val plug = plugLabel(s.plugType).takeIf { it != "Unknown" }
    val meta = listOfNotNull(startTime, duration(mins), plug).joinToString(" · ")
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "$meta, ${s.startPercent} to ${s.endPercent} percent, speed $speed, $added, $peak"
        },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("${s.startPercent} → ${s.endPercent}%", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = dim)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(added, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Text(peak, style = MaterialTheme.typography.bodyMedium, color = dim)
            }
        }
        if (!last) Hairline()
    }
}
