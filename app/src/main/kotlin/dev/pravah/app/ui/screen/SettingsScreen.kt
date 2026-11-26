package dev.pravah.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import dev.pravah.app.ui.components.Hairline
import dev.pravah.app.ui.components.ScreenColumn
import dev.pravah.app.ui.components.SectionLabel
import dev.pravah.app.ui.theme.pravah
import dev.pravah.app.ui.viewmodel.UiState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UiState,
    onAlarmPercentChange: (Int) -> Unit,
    onAlarmEnabledChange: (Boolean) -> Unit,
    onUseFahrenheit: (Boolean) -> Unit,
    onAmoledTheme: (Boolean) -> Unit,
    onNotificationLive: (Boolean) -> Unit,
    onResetData: () -> Unit,
    onThemeMode: (Int) -> Unit,
    onChargeLimit: (Int) -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }
    ScreenColumn("Settings") {
        SectionLabel("Charge limit")
        val sys = state.systemChargeLimit
        val autoNow = when {
            sys == null -> "Auto uses 100%, or the level your phone is seen holding at."
            sys < 100 -> "Auto follows this phone's Battery protection — it stops charging at $sys%."
            else -> "Auto follows this phone's Battery protection — it charges to 100%."
        }
        Text(autoNow, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.pravah.dim, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        val limits = listOf(0 to "Auto", 80 to "80", 85 to "85", 90 to "90", 100 to "100")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            limits.forEachIndexed { i, (v, label) ->
                SegmentedButton(
                    selected = state.chargeLimitManual == v,
                    onClick = { onChargeLimit(v) },
                    shape = SegmentedButtonDefaults.itemShape(i, limits.size),
                    icon = {},
                    modifier = Modifier.semantics {
                        contentDescription = if (v == 0) "Automatic charge limit" else "Charge limit $v percent"
                    },
                ) { Text(label, maxLines = 1) }
            }
        }
        Text(
            "Time-to-full counts to this level. Pravah can't change where your phone stops charging.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.pravah.dim,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )
        Hairline()

        SectionLabel("Charge alarm")
        ToggleRow("Alert when charged", "Notify at the target level", state.alarmEnabled, onAlarmEnabledChange)
        if (state.alarmEnabled) {
            var drag by remember(state.alarmPercent) { mutableStateOf(state.alarmPercent.toFloat()) }
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = drag,
                    onValueChange = { drag = it },
                    onValueChangeFinished = { onAlarmPercentChange(drag.roundToInt()) },
                    valueRange = 50f..100f,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Alarm percent" },
                )
                Text("${drag.roundToInt()}%", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
            }
        }
        Hairline()

        SectionLabel("Display")
        Text("Theme", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.pravah.dim, modifier = Modifier.padding(vertical = 8.dp))
        val modes = listOf("System", "Light", "Dark")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            modes.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = state.themeMode == i,
                    onClick = { onThemeMode(i) },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                ) { Text(label) }
            }
        }
        ToggleRow("Pure black", "AMOLED background in dark mode", state.amoledTheme, onAmoledTheme)
        ToggleRow("Temperature in °F", null, state.useFahrenheit, onUseFahrenheit)

        SectionLabel("Notification")
        ToggleRow("Live status notification", "Level, current and time left in the shade", state.notificationLive, onNotificationLive, last = true)

        SectionLabel("Data")
        Column(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { confirmReset = true }.padding(vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Reset all data", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            Text("Deletes history and learned capacity", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.pravah.dim)
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset all data?") },
            text = { Text("Charge history and learned capacity will be permanently deleted. Settings are kept.") },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; onResetData() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, last: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.pravah.dim)
            }
            Switch(checked = checked, onCheckedChange = null)
        }
        if (!last) Hairline()
    }
}
