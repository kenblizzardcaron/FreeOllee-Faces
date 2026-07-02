package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.activity.ActivityMetric
import com.blizzardcaron.freeolleefaces.activity.ActivityMetricsConfig
import com.blizzardcaron.freeolleefaces.activity.ActivityMode
import com.blizzardcaron.freeolleefaces.activity.ActivityState
import com.blizzardcaron.freeolleefaces.activity.ActivityTrack
import com.blizzardcaron.freeolleefaces.activity.ActivityUnit

/** The Activity tab. Idle shows Start + unit toggle + last-activity summary; running shows the
 *  three live readouts (selected one highlighted), a MODE button, and Stop. */
// pushIntervalMs/intervalPresetsMs are idle-only state, distinct from ActivityCallbacks (which
// bundles actions); splitting the screen just to dodge the parameter count would obscure the
// single entry point more than it helps.
@Suppress("LongParameterList")
@Composable
fun ActivityScreen(
    state: ActivityState,
    unit: ActivityUnit,
    watchSelected: Boolean,
    recent: List<ActivityTrack>,
    config: ActivityMetricsConfig,
    callbacks: ActivityCallbacks,
    pushIntervalMs: Long,
    intervalPresetsMs: List<Long>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.running) {
            RunningContent(state, unit, watchSelected, config, callbacks)
        } else {
            IdleContent(unit, recent, callbacks, pushIntervalMs, intervalPresetsMs)
        }
    }
}

@Composable
private fun IdleContent(
    unit: ActivityUnit,
    recent: List<ActivityTrack>,
    callbacks: ActivityCallbacks,
    pushIntervalMs: Long,
    intervalPresetsMs: List<Long>,
) {
    Button(onClick = callbacks.onStart, modifier = Modifier.fillMaxWidth()) { Text("Start activity") }
    OutlinedButton(onClick = callbacks.onShowLive, modifier = Modifier.fillMaxWidth()) {
        Text("Instrument glance")
    }
    OutlinedButton(onClick = callbacks.onToggleUnit, modifier = Modifier.fillMaxWidth()) {
        Text("Units: ${if (unit == ActivityUnit.IMPERIAL) "Miles" else "Kilometres"}")
    }
    IntervalPicker(pushIntervalMs, intervalPresetsMs, callbacks.onSelectInterval)
    OutlinedButton(onClick = callbacks.onOpenHistory, modifier = Modifier.fillMaxWidth()) {
        Text("History")
    }
    OutlinedButton(onClick = callbacks.onConfigureMetrics, modifier = Modifier.fillMaxWidth()) {
        Text("Configure metrics")
    }
    RecentActivities(recent, unit, callbacks.onOpenActivity)
}

private const val RECENT_LIMIT = 3

@Composable
private fun RecentActivities(
    recent: List<ActivityTrack>,
    unit: ActivityUnit,
    onOpen: (String) -> Unit,
) {
    if (recent.isEmpty()) return
    Text("Recent activities", fontWeight = FontWeight.Bold)
    for (track in recent.take(RECENT_LIMIT)) {
        Card(elevation = CardDefaults.cardElevation(), modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp).clickable { onOpen(track.id) },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                track.summary?.let {
                    Text("Distance ${distanceText(it.distanceM, unit)}")
                    Text("Time ${hms(it.elapsedTimeMs)}")
                    Text("Avg pace ${paceText(it.avgPaceSecPerKm, unit)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun IntervalPicker(
    selectedMs: Long,
    presetsMs: List<Long>,
    onSelect: (Long) -> Unit,
) {
    Text("Watch update interval", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Narrow content padding: five chips share a phone-width row, and the default
        // 24dp button inset wraps "15s"/"30s" onto two lines.
        val chipPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
        for (ms in presetsMs) {
            val selected = ms == selectedMs
            if (selected) {
                Button(
                    onClick = { onSelect(ms) },
                    modifier = Modifier.weight(1f),
                    contentPadding = chipPadding,
                ) { Text(intervalLabel(ms), maxLines = 1) }
            } else {
                OutlinedButton(
                    onClick = { onSelect(ms) },
                    modifier = Modifier.weight(1f),
                    contentPadding = chipPadding,
                ) { Text(intervalLabel(ms), maxLines = 1) }
            }
        }
    }
}

private const val MS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE_UI = 60L

private fun intervalLabel(ms: Long): String {
    val seconds = ms / MS_PER_SECOND
    return if (seconds < SECONDS_PER_MINUTE_UI) "${seconds}s" else "${seconds / SECONDS_PER_MINUTE_UI}m"
}

@Composable
private fun RunningContent(
    state: ActivityState,
    unit: ActivityUnit,
    watchSelected: Boolean,
    config: ActivityMetricsConfig,
    callbacks: ActivityCallbacks,
) {
    if (!state.hasFix) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.padding(2.dp))
            Text("Acquiring GPS…", style = MaterialTheme.typography.bodyMedium)
        }
    }
    // Show each metric exactly as the watch renders it (faithful segment preview), for whichever
    // metrics are enabled (and in the order configured) for the current mode.
    val mode = if (state.recording) ActivityMode.RECORDING else ActivityMode.GLANCE
    for (metric in config.enabledOrder(mode)) {
        MetricReadout(metricLabel(metric), metric, state, unit)
    }
    val watchStatusText = if (!watchSelected) {
        if (state.recording) "No watch — recording only" else "No watch — glance only"
    } else if (state.watchReachable) {
        "Watch: showing ${state.lastPushText ?: "…"}"
    } else if (state.recording) {
        "Watch unreachable — recording continues"
    } else {
        "Watch unreachable"
    }
    Text(watchStatusText, style = MaterialTheme.typography.bodySmall)
    RunningControls(state, callbacks)
    if (!state.recording) {
        OutlinedButton(onClick = callbacks.onStop, modifier = Modifier.fillMaxWidth()) { Text("Close glance") }
    }
}

@Composable
private fun RunningControls(state: ActivityState, callbacks: ActivityCallbacks) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = callbacks.onMode,
            enabled = !state.stopping,
            modifier = Modifier.weight(1f),
        ) { Text("MODE") }
        if (state.recording) {
            Button(
                onClick = callbacks.onStop,
                enabled = !state.stopping,
                modifier = Modifier.weight(1f),
            ) { Text(if (state.stopping) "Stopping…" else "Stop") }
        } else {
            Button(onClick = callbacks.onStart, modifier = Modifier.weight(1f)) { Text("Record") }
        }
    }
    if (state.recording && !state.stopping) {
        if (state.paused) {
            Button(onClick = callbacks.onResume, modifier = Modifier.fillMaxWidth()) { Text("Resume") }
        } else {
            OutlinedButton(onClick = callbacks.onPause, modifier = Modifier.fillMaxWidth()) { Text("Pause") }
        }
    }
}

private fun metricLabel(metric: ActivityMetric): String = when (metric) {
    ActivityMetric.PACE -> "Pace"
    ActivityMetric.DISTANCE -> "Distance"
    ActivityMetric.TIME -> "Time"
    ActivityMetric.ORIENTATION -> "Compass"
    ActivityMetric.ALTITUDE -> "Altitude"
    ActivityMetric.PRESSURE -> "Pressure"
    ActivityMetric.AVG_PACE -> "Avg pace"
}

@Composable
private fun MetricReadout(label: String, metric: ActivityMetric, state: ActivityState, unit: ActivityUnit) {
    // Match the watch: until a fix lands, every GPS-derived metric blanks to the acquiring banner.
    // Pressure is barometer-derived, so it shows its real value even during acquisition.
    val human = if (!state.hasFix && metric != ActivityMetric.PRESSURE) {
        "Acquiring GPS…"
    } else {
        metric.human(state, unit) ?: "—"
    }
    Readout(label, human, metric.render(state, unit), state.selectedMetric == metric)
}

@Composable
private fun Readout(label: String, human: String, watchValue: String, selected: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (selected) "▶ $label" else label)
            Text(human, style = MaterialTheme.typography.bodySmall)
        }
        SegmentReadout(
            value = watchValue,
            cellHeight = if (selected) 36.dp else 26.dp,
            tone = LcdTone.Green,
        )
    }
}
