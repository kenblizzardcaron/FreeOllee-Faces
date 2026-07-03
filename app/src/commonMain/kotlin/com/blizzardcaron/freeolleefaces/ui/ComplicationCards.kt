package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.auto.ActiveComplication
import com.blizzardcaron.freeolleefaces.auto.displayLabel
import com.blizzardcaron.freeolleefaces.format.BatteryReadout
import com.blizzardcaron.freeolleefaces.format.DisplayFormatter
import com.blizzardcaron.freeolleefaces.format.TempUnit
import com.blizzardcaron.freeolleefaces.glyph.NameplateSanitizer

private data class FacePreview(val preview: PreviewState, val updated: String? = null, val next: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemperatureCard(
    state: HomeState,
    callbacks: HomeCallbacks,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    ComplicationCard(
        title = ActiveComplication.TEMPERATURE.displayLabel(),
        active = state.activeComplication == ActiveComplication.TEMPERATURE,
        onActivate = { callbacks.onActivate(ActiveComplication.TEMPERATURE) },
        face = FacePreview(state.tempPreview, state.tempUpdated, state.tempNext),
        expanded = expanded,
        onToggle = onToggle,
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.tempUnit == TempUnit.FAHRENHEIT,
                onClick = { callbacks.onTempUnitChange(TempUnit.FAHRENHEIT) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("°F") }
            SegmentedButton(
                selected = state.tempUnit == TempUnit.CELSIUS,
                onClick = { callbacks.onTempUnitChange(TempUnit.CELSIUS) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("°C") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BatteryCard(
    state: HomeState,
    callbacks: HomeCallbacks,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    ComplicationCard(
        title = ActiveComplication.BATTERY.displayLabel(),
        active = state.activeComplication == ActiveComplication.BATTERY,
        onActivate = { callbacks.onActivate(ActiveComplication.BATTERY) },
        face = FacePreview(state.batteryPreview, state.batteryUpdated, state.batteryNext),
        expanded = expanded,
        onToggle = onToggle,
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.batteryReadout == BatteryReadout.PERCENT,
                onClick = { callbacks.onSetBatteryReadout(BatteryReadout.PERCENT) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("Percent") }
            SegmentedButton(
                selected = state.batteryReadout == BatteryReadout.VOLTS,
                onClick = { callbacks.onSetBatteryReadout(BatteryReadout.VOLTS) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("Volts") }
        }
    }
}

@Composable
internal fun PressureCard(state: HomeState, callbacks: HomeCallbacks) {
    ComplicationCard(
        title = ActiveComplication.PRESSURE.displayLabel(),
        active = state.activeComplication == ActiveComplication.PRESSURE,
        onActivate = { callbacks.onActivate(ActiveComplication.PRESSURE) },
        face = FacePreview(state.pressurePreview, state.pressureUpdated, state.pressureNext),
        expanded = false,
        onToggle = null,
    )
}

@Composable
internal fun AltitudeCard(state: HomeState, callbacks: HomeCallbacks) {
    ComplicationCard(
        title = ActiveComplication.ALTITUDE.displayLabel(),
        active = state.activeComplication == ActiveComplication.ALTITUDE,
        onActivate = { callbacks.onActivate(ActiveComplication.ALTITUDE) },
        face = FacePreview(state.altitudePreview, state.altitudeUpdated, state.altitudeNext),
        expanded = false,
        onToggle = null,
    )
}

@Composable
internal fun StepsCard(
    state: HomeState,
    callbacks: HomeCallbacks,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    ComplicationCard(
        title = ActiveComplication.STEPS.displayLabel(),
        active = state.activeComplication == ActiveComplication.STEPS,
        onActivate = { callbacks.onActivate(ActiveComplication.STEPS) },
        face = FacePreview(state.stepsPreview, state.stepsUpdated, next = null),
        expanded = expanded,
        onToggle = onToggle,
    ) {
        if (!state.stepsHealthGranted) {
            Text("Health access needed", style = MaterialTheme.typography.titleSmall)
            Text(
                "Steps come from Health Connect, where your step-tracking app writes them. " +
                    "Grant read access to show today's count on your watch.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = callbacks.onGrantHealth, modifier = Modifier.fillMaxWidth()) {
                Text("Grant Health access")
            }
        } else {
            Text(
                "Pushed every ${state.updateIntervalMinutes} min while awake.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Read live steps from RingConn", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        state.ringConnName?.let { "Ring: $it" }
                            ?: "Pair your ring in the RingConn app first",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = state.ringConnStepsEnabled,
                    onCheckedChange = callbacks.onToggleRingConnSteps,
                    modifier = Modifier.semantics { contentDescription = "Read live steps from RingConn" },
                )
            }
        }
    }
}

@Composable
internal fun CustomCard(
    state: HomeState,
    callbacks: HomeCallbacks,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    ComplicationCard(
        title = ActiveComplication.CUSTOM.displayLabel(),
        active = state.activeComplication == ActiveComplication.CUSTOM,
        onActivate = { callbacks.onActivate(ActiveComplication.CUSTOM) },
        face = FacePreview(
            // Preview through the same sanitizer the send path applies (ComplicationController
            // sanitizes nameplate-target values), so the on-screen glyphs match the watch.
            preview = PreviewState.Ready(
                NameplateSanitizer.sanitize(DisplayFormatter.custom(state.custom)),
                "'${state.custom}'",
            ),
        ),
        expanded = expanded,
        onToggle = onToggle,
    ) {
        OutlinedTextField(
            value = state.custom,
            onValueChange = callbacks.onCustomChange,
            label = { Text("Custom (up to 6 chars)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = callbacks.onSendCustom,
            enabled = state.watchSelected && !state.sending,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Send to watch") }
        if (state.customSent != null) {
            Text(state.customSent, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ComplicationCard(
    title: String,
    active: Boolean,
    onActivate: () -> Unit,
    face: FacePreview,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    expandedContent: (@Composable () -> Unit)? = null,
) {
    val cardColors = if (active) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    val border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    Card(modifier = Modifier.fillMaxWidth(), colors = cardColors, border = border) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val headerModifier = Modifier
                .fillMaxWidth()
                .let { if (onToggle != null) it.clickable { onToggle() } else it }
                .padding(12.dp)
            Column(modifier = headerModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = active,
                            onClick = onActivate,
                            modifier = Modifier.semantics { contentDescription = "Activate $title" },
                        )
                        Text(title, style = MaterialTheme.typography.titleMedium)
                    }
                    if (onToggle != null) {
                        Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                FaceValue(face)
            }
            if (expanded && expandedContent != null) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { expandedContent() }
            }
        }
    }
}

@Composable
private fun FaceValue(face: FacePreview) {
    val (preview, updated, next) = face
    when (preview) {
        is PreviewState.WaitingForCoords -> Text(
            "Waiting for coordinates…",
            style = MaterialTheme.typography.bodyMedium
        )
        is PreviewState.Loading -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
        is PreviewState.Ready -> {
            Text(preview.human, style = MaterialTheme.typography.headlineMedium)
            LcdReadout(value = preview.payload, size = LcdSize.Md)
        }
        is PreviewState.Error -> Text(preview.message, style = MaterialTheme.typography.bodyMedium)
    }
    if (updated != null) Text(updated, style = MaterialTheme.typography.bodySmall)
    if (next != null) Text(next, style = MaterialTheme.typography.bodySmall)
}
