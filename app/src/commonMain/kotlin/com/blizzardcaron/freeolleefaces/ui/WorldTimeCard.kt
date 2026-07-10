package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.worldtime.WorldTime
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone

/** Minute ticker interval so time labels stay live while the card is on screen. */
private const val TICK_MS = 60_000L
private const val PICKER_MAX_HEIGHT_DP = 320

@Composable
internal fun WorldTimeCard(
    state: HomeState,
    callbacks: HomeCallbacks,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            callbacks.onWorldTimeRefresh()
        }
    }

    val wt = state.worldTime
    var showPicker by remember { mutableStateOf(false) }

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
                    Text("World Time", style = MaterialTheme.typography.titleMedium)
                    Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.bodyMedium)
                }
                Text(activeLine(wt), style = MaterialTheme.typography.headlineMedium)
                if (wt.slots.isNotEmpty()) {
                    SlotChipsRow(wt, callbacks)
                }
            }
            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    wt.slots.forEach { slot ->
                        SlotRow(slot, callbacks)
                    }
                    TextButton(
                        onClick = { showPicker = true },
                        enabled = wt.slots.size < WorldTime.MAX_SLOTS,
                    ) {
                        Text("Add zone")
                    }
                }
            }
        }
    }

    if (showPicker) {
        ZonePickerDialog(
            onPick = { zoneId ->
                callbacks.onWorldTimeAddSlot(zoneId)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** Collapsed active line: the active slot, the adopted custom offset, or "Not set". */
private fun activeLine(wt: WorldTimeUiState): String {
    val active = wt.activeZoneId?.let { zoneId -> wt.slots.firstOrNull { it.zoneId == zoneId } }
    return when {
        active != null -> "${active.city}  ${active.timeLabel}  (${active.offsetLabel})"
        wt.activeZoneId == null && wt.customOffsetLabel != null -> "Custom (${wt.customOffsetLabel})"
        else -> "Not set"
    }
}

// Wave B substitution note: the brief calls for a FlowRow of chips, but no file in this codebase
// opts into ExperimentalLayoutApi yet (PowerSavingSection.kt's PeriodRow uses FilterChip in a
// plain Row for its fixed small chip set). With MAX_SLOTS = 4 slots, a horizontally scrolling Row
// covers the same ground without the new opt-in.
@Composable
private fun SlotChipsRow(wt: WorldTimeUiState, callbacks: HomeCallbacks) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        wt.slots.forEach { slot ->
            FilterChip(
                selected = slot.zoneId == wt.activeZoneId,
                onClick = { callbacks.onWorldTimeActivate(slot.zoneId) },
                label = { Text("${slot.city} ${slot.timeLabel}") },
            )
        }
    }
}

@Composable
private fun SlotRow(slot: WorldTimeSlotUi, callbacks: HomeCallbacks) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "${slot.city}  ${slot.timeLabel}  (${slot.offsetLabel})",
            style = MaterialTheme.typography.bodyLarge,
        )
        TextButton(onClick = { callbacks.onWorldTimeRemoveSlot(slot.zoneId) }) { Text("Remove") }
    }
}

@Composable
private fun ZonePickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val allZoneIds = remember { TimeZone.availableZoneIds.sorted() }
    val filtered = remember(query) { allZoneIds.filter { it.contains(query, ignoreCase = true) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add time zone") },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Filter") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = PICKER_MAX_HEIGHT_DP.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(filtered) { zoneId ->
                        Text(
                            "${WorldTime.cityOf(zoneId)} — $zoneId",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(zoneId) }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
    )
}
