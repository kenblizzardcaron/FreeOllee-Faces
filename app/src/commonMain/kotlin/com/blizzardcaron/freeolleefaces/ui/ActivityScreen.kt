package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.blizzardcaron.freeolleefaces.activity.ActivitySummary
import com.blizzardcaron.freeolleefaces.activity.ActivityUnit

/** The Activity tab. Idle shows Start + unit toggle + last-activity summary; running shows the
 *  three live readouts (selected one highlighted), a MODE button, and Stop. */
@Composable
fun ActivityScreen(
    state: ActivityState,
    unit: ActivityUnit,
    watchSelected: Boolean,
    lastSummary: ActivitySummary?,
    config: ActivityMetricsConfig,
    callbacks: ActivityCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.running) {
            RunningContent(state, unit, watchSelected, config, callbacks)
        } else {
            IdleContent(unit, lastSummary, callbacks)
        }
    }
}

@Composable
private fun IdleContent(
    unit: ActivityUnit,
    lastSummary: ActivitySummary?,
    callbacks: ActivityCallbacks,
) {
    Button(onClick = callbacks.onStart, modifier = Modifier.fillMaxWidth()) { Text("Start activity") }
    OutlinedButton(onClick = callbacks.onShowLive, modifier = Modifier.fillMaxWidth()) {
        Text("Instrument glance")
    }
    OutlinedButton(onClick = callbacks.onToggleUnit, modifier = Modifier.fillMaxWidth()) {
        Text("Units: ${if (unit == ActivityUnit.IMPERIAL) "Miles" else "Kilometres"}")
    }
    OutlinedButton(onClick = callbacks.onOpenHistory, modifier = Modifier.fillMaxWidth()) {
        Text("History")
    }
    OutlinedButton(onClick = callbacks.onConfigureMetrics, modifier = Modifier.fillMaxWidth()) {
        Text("Configure metrics")
    }
    if (lastSummary != null) {
        Card(elevation = CardDefaults.cardElevation()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Last activity", fontWeight = FontWeight.Bold)
                Text("Distance ${distanceText(lastSummary.distanceM, unit)}")
                Text("Time ${hms(lastSummary.elapsedTimeMs)}")
                Text("Avg pace ${paceText(lastSummary.avgPaceSecPerKm, unit)}")
            }
        }
    }
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = callbacks.onMode, modifier = Modifier.weight(1f)) { Text("MODE") }
        if (state.recording) {
            Button(onClick = callbacks.onStop, modifier = Modifier.weight(1f)) { Text("Stop") }
        } else {
            Button(onClick = callbacks.onStart, modifier = Modifier.weight(1f)) { Text("Record") }
        }
    }
    if (!state.recording) {
        OutlinedButton(onClick = callbacks.onStop, modifier = Modifier.fillMaxWidth()) { Text("Close glance") }
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
