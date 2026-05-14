package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

data class MainScreenState(
    val lat: String = "",
    val lng: String = "",
    val custom: String = "",
    val watchLabel: String = "Watch: none selected",
    val status: String = "Ready.",
    val sending: Boolean = false,
    val latLngValid: Boolean = false,
    val watchSelected: Boolean = false,
)

data class MainScreenCallbacks(
    val onLatChange: (String) -> Unit,
    val onLngChange: (String) -> Unit,
    val onCustomChange: (String) -> Unit,
    val onSelectWatch: () -> Unit,
    val onUseMyLocation: () -> Unit,
    val onSendTemperature: () -> Unit,
    val onSendSunTime: () -> Unit,
    val onSendCustom: () -> Unit,
)

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
        Text("FreeOllee Faces", style = MaterialTheme.typography.headlineSmall)
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

        Button(
            onClick = callbacks.onSendTemperature,
            enabled = state.latLngValid && state.watchSelected && !state.sending,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Send temperature") }

        Button(
            onClick = callbacks.onSendSunTime,
            enabled = state.latLngValid && state.watchSelected && !state.sending,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Send next sun event") }

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
