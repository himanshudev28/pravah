package dev.pravah.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.pravah.app.ui.components.DetailRow
import dev.pravah.app.ui.components.ScreenColumn
import dev.pravah.app.ui.components.SectionLabel
import dev.pravah.app.ui.components.mah
import dev.pravah.app.ui.theme.HeroNumber
import dev.pravah.app.ui.theme.HeroUnit
import dev.pravah.app.ui.theme.pravah
import dev.pravah.app.ui.viewmodel.UiState
import kotlin.math.roundToInt

@Composable
fun HealthScreen(state: UiState, onSetDesignCapacity: (Float) -> Unit) {
    val t = state.telemetry
    val health = state.healthPercent
    ScreenColumn("Health") {
        Row(Modifier.padding(top = 20.dp)) {
            Text(
                health?.let { "${it.roundToInt()}" } ?: "—",
                style = HeroNumber, color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.alignByBaseline(),
            )
            if (health != null) Text("%", style = HeroUnit, color = MaterialTheme.pravah.dim,
                modifier = Modifier.alignByBaseline().padding(start = 4.dp))
        }
        Text(
            when {
                health == null && state.effectiveDesignMah == null -> "Not enough data yet. Complete a few charges, and set the design capacity below if it isn't detected."
                health == null -> "Not enough data yet. Capacity is learned from completed charge sessions."
                else -> "of original capacity · learned from ${state.capacitySessionCount} charge session${if (state.capacitySessionCount == 1) "" else "s"}"
            },
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.pravah.dim,
            modifier = Modifier.padding(top = 8.dp),
        )

        SectionLabel("Capacity")
        DetailRow("Learned full capacity", state.estimatedCapacityMah.takeIf { it > 0 }?.let { mah(it.toDouble()) } ?: "—")
        DetailRow("Design capacity", state.effectiveDesignMah?.let { mah(it) } ?: "Unknown")
        DetailRow("Charge cycles", t?.cycleCount?.toString() ?: "Not reported", last = true)

        SectionLabel("Battery")
        DetailRow("Condition", t?.batteryHealth?.label() ?: "—")
        DetailRow("Technology", t?.technology?.ifBlank { null } ?: "—", last = true)

        SectionLabel("Device")
        DetailRow("Model", t?.let { "${it.manufacturer.replaceFirstChar(Char::uppercase)} ${it.model}" } ?: "—")
        DetailRow("Android", t?.androidVersion?.let { "API $it" } ?: "—", last = true)

        SectionLabel("Design capacity override")
        Text(
            "If the detected value is wrong, enter the rated mAh printed on your battery. Enter 0 to go back to auto-detect.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.pravah.dim,
        )
        var text by remember(state.designCapacityMah) {
            mutableStateOf(state.designCapacityMah.takeIf { it > 0 }?.roundToInt()?.toString() ?: "")
        }
        val value = text.toIntOrNull()
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter(Char::isDigit).take(5) },
                label = { Text("mAh") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { value?.let { onSetDesignCapacity(it.toFloat()) } },
                enabled = value != null && (value == 0 || value in 500..30000),
                modifier = Modifier.heightIn(min = 56.dp),
            ) { Text("Save") }
        }
    }
}
