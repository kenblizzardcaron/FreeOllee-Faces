package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.blizzardcaron.freeolleefaces.auto.ActiveComplication
import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus
import com.blizzardcaron.freeolleefaces.ble.connectionChip
import com.blizzardcaron.freeolleefaces.ble.wakeHint

@Composable
fun HomeScreen(
    state: HomeState,
    callbacks: HomeCallbacks,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf<ComplicationCardId?>(null) }
    fun toggle(id: ComplicationCardId) { expanded = if (expanded == id) null else id }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppBar(title = "Complications")

        ConnectionRow(status = state.connectionStatus, onReconnect = callbacks.onReconnect)

        ComplicationCardsList(state, callbacks, expanded) { toggle(it) }

        Button(
            onClick = callbacks.onUpdateNow,
            // No-op for CUSTOM (its card has its own send button); also needs a watch and no send in flight.
            enabled = state.activeComplication != ActiveComplication.CUSTOM && state.watchSelected && !state.sending,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Update active now")
        }

        Text(
            state.versionLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ColumnScope.ComplicationCardsList(
    state: HomeState,
    callbacks: HomeCallbacks,
    expanded: ComplicationCardId?,
    onToggle: (ComplicationCardId) -> Unit,
) {
    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.watchSelected && state.activeComplication != ActiveComplication.CUSTOM) {
            SettingsHint("No watch selected — open Settings (⚙)")
        }

        SectionLabel("Weekday slot")

        NotificationsCard(
            state = state,
            expanded = expanded == ComplicationCardId.NOTIFICATIONS,
            onToggle = { onToggle(ComplicationCardId.NOTIFICATIONS) },
            onToggleEnabled = callbacks.onToggleNotifications,
            onGrantAccess = callbacks.onGrantNotificationAccess,
            onUpdateNow = callbacks.onNotificationsUpdateNow,
        )

        WorldTimeCard(
            state = state,
            callbacks = callbacks,
            expanded = expanded == ComplicationCardId.WORLD_TIME,
            onToggle = { onToggle(ComplicationCardId.WORLD_TIME) },
        )

        SectionLabel("Name tag")

        TemperatureCard(
            state = state,
            callbacks = callbacks,
            expanded = expanded == ComplicationCardId.TEMPERATURE,
            onToggle = { onToggle(ComplicationCardId.TEMPERATURE) },
        )

        BatteryCard(
            state = state,
            callbacks = callbacks,
            expanded = expanded == ComplicationCardId.BATTERY,
            onToggle = { onToggle(ComplicationCardId.BATTERY) },
        )

        PressureCard(state = state, callbacks = callbacks)

        AltitudeCard(state = state, callbacks = callbacks)

        StepsCard(
            state = state,
            callbacks = callbacks,
            expanded = expanded == ComplicationCardId.STEPS,
            onToggle = { onToggle(ComplicationCardId.STEPS) },
        )

        CustomCard(
            state = state,
            callbacks = callbacks,
            expanded = expanded == ComplicationCardId.CUSTOM,
            onToggle = { onToggle(ComplicationCardId.CUSTOM) },
        )
    }
}

@Composable
private fun ConnectionRow(status: ConnectionStatus, onReconnect: () -> Unit) {
    val chip = connectionChip(status)
    val color = connectionStatusColor(status)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (chip.clickable) {
            TextButton(onClick = onReconnect) {
                Text(chip.label, color = color, style = MaterialTheme.typography.labelLarge)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chip.showSpinner) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(chip.label, color = color, style = MaterialTheme.typography.labelLarge)
            }
        }
        wakeHint(status)?.let { hint ->
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SettingsHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.08.em),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun NotificationsCard(
    state: HomeState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onGrantAccess: () -> Unit,
    onUpdateNow: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Notifications", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.notificationsEnabled) {
                            Text(
                                "● on",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(if (expanded) "  ▾" else "  ▸", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val human = when {
                    !state.notificationsEnabled -> "Off"
                    !state.notificationAccessGranted -> "Needs notification access"
                    else -> "${state.notificationCount} unread"
                }
                Text(human, style = MaterialTheme.typography.headlineMedium)
                Text("Weekday slot overlay on the Clock face.", style = MaterialTheme.typography.bodySmall)
            }
            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NotificationsExpandedContent(state, onToggleEnabled, onGrantAccess, onUpdateNow)
                }
            }
        }
    }
}

@Composable
private fun NotificationsExpandedContent(
    state: HomeState,
    onToggleEnabled: (Boolean) -> Unit,
    onGrantAccess: () -> Unit,
    onUpdateNow: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Show count in weekday slot", style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = state.notificationsEnabled,
            onCheckedChange = onToggleEnabled,
            modifier = Modifier.semantics { contentDescription = "Show count in weekday slot" },
        )
    }
    Text(
        "To save battery, the count is sent at most once a minute, so it can briefly " +
            "trail the notification shade.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (state.notificationsEnabled && !state.notificationAccessGranted) {
        Text("Notification access needed", style = MaterialTheme.typography.titleSmall)
        Button(onClick = onGrantAccess, modifier = Modifier.fillMaxWidth()) {
            Text("Grant notification access")
        }
    }
    Button(
        onClick = onUpdateNow,
        enabled = state.watchSelected && state.notificationAccessGranted && state.notificationsEnabled,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Update now") }
}
