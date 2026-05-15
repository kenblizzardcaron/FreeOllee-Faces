package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.format.TempUnit

data class MainScreenState(
    val lat: String = "",
    val lng: String = "",
    val custom: String = "",
    val watchLabel: String = "Watch: none selected",
    val status: String = "Ready.",
    val sending: Boolean = false,
    val watchSelected: Boolean = false,
    val tempUnit: TempUnit = TempUnit.FAHRENHEIT,
    val tempPreview: PreviewState = PreviewState.Loading,
    val sunPreview: PreviewState = PreviewState.Loading,
)

data class MainScreenCallbacks(
    val onLatChange: (String) -> Unit,
    val onLngChange: (String) -> Unit,
    val onCustomChange: (String) -> Unit,
    val onSelectWatch: () -> Unit,
    val onUseMyLocation: () -> Unit,
    val onRefresh: () -> Unit,
    val onTempUnitChange: (TempUnit) -> Unit,
    val onSendTemperature: () -> Unit,
    val onSendSunTime: () -> Unit,
    val onSendCustom: () -> Unit,
    val onRetryTemperature: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainScreenState,
    callbacks: MainScreenCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("FreeOllee Faces", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = callbacks.onRefresh) { Text("Refresh") }
        }

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

        Text(state.watchLabel, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = callbacks.onSelectWatch, modifier = Modifier.fillMaxWidth()) {
            Text("Select watch")
        }

        OutlinedTextField(
            value = state.lat,
            onValueChange = callbacks.onLatChange,
            label = { Text("Latitude") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.lng,
            onValueChange = callbacks.onLngChange,
            label = { Text("Longitude") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = callbacks.onUseMyLocation, modifier = Modifier.fillMaxWidth()) {
            Text("Use my location")
        }

        HorizontalDivider()

        PreviewCard(
            title = "Temperature",
            state = state.tempPreview,
            onRetry = callbacks.onRetryTemperature,
            onSend = callbacks.onSendTemperature,
            sendEnabled = state.tempPreview is PreviewState.Ready && state.watchSelected && !state.sending,
        )

        PreviewCard(
            title = "Next sun event",
            state = state.sunPreview,
            onRetry = null, // sun is local; no retry path
            onSend = callbacks.onSendSunTime,
            sendEnabled = state.sunPreview is PreviewState.Ready && state.watchSelected && !state.sending,
        )

        HorizontalDivider()

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
        ) { Text("Send custom") }

        HorizontalDivider()

        Text(state.status, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PreviewCard(
    title: String,
    state: PreviewState,
    onRetry: (() -> Unit)?,
    onSend: () -> Unit,
    sendEnabled: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            when (state) {
                is PreviewState.Loading -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                is PreviewState.Ready -> {
                    Text(
                        "Watch: '${state.payload}'",
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    )
                    Text(state.human, style = MaterialTheme.typography.bodyMedium)
                }
                is PreviewState.Error -> {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    if (onRetry != null) {
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                }
                PreviewState.NoEvent -> Text(
                    "No sunrise/sunset in next 24 h.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = onSend,
                enabled = sendEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send to watch") }
        }
    }
}
