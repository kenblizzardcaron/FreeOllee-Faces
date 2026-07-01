package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.activity.ActivityMetric
import com.blizzardcaron.freeolleefaces.activity.ActivityMetricItem
import com.blizzardcaron.freeolleefaces.activity.ActivityMetricsConfig
import com.blizzardcaron.freeolleefaces.activity.ActivityMode
import com.blizzardcaron.freeolleefaces.activity.ActivityState
import com.blizzardcaron.freeolleefaces.activity.ActivityUnit

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
fun ActivityMetricsConfigScreen(
    config: ActivityMetricsConfig,
    unit: ActivityUnit,
    callbacks: ActivityMetricsConfigCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        // Two metric sections (12 rows) plus a Done button overflow short screens; without a
        // scroll the bottom row was clipped to ~22dp tall (ATF TouchTargetSizeCheck) and Done
        // was unreachable. Scroll so every row keeps its full height and the button is reachable.
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Configure metrics", style = MaterialTheme.typography.headlineSmall)
        MetricSection("Recording", ActivityMode.RECORDING, config, unit, callbacks)
        HorizontalDivider()
        MetricSection("Glance", ActivityMode.GLANCE, config, unit, callbacks)
        OutlinedButton(onClick = callbacks.onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@Composable
private fun MetricSection(
    title: String,
    mode: ActivityMode,
    config: ActivityMetricsConfig,
    unit: ActivityUnit,
    callbacks: ActivityMetricsConfigCallbacks,
) {
    val items = config.forMode(mode)
    val enabledCount = items.count { it.enabled }
    Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    items.forEachIndexed { index, item ->
        MetricRow(mode, index, item, items.lastIndex, enabledCount, unit, callbacks)
    }
}

@Composable
private fun MetricRow(
    mode: ActivityMode,
    index: Int,
    item: ActivityMetricItem,
    lastIndex: Int,
    enabledCount: Int,
    unit: ActivityUnit,
    callbacks: ActivityMetricsConfigCallbacks,
) {
    // A sample human value with neutral inputs, so each row reads in plain units.
    val sample = item.metric.human(SAMPLE_STATE, unit) ?: "—"
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(metricLabel(item.metric))
            Text(sample, style = MaterialTheme.typography.bodySmall)
        }
        // Forbid disabling the last enabled metric in the mode (>= 1 invariant).
        val canDisable = !(item.enabled && enabledCount <= 1)
        Switch(
            checked = item.enabled,
            onCheckedChange = { on -> if (on || canDisable) callbacks.onToggle(mode, item.metric, on) },
            enabled = item.enabled.not() || canDisable,
            modifier = Modifier.semantics { contentDescription = "Show ${metricLabel(item.metric)}" },
        )
        TextButton(onClick = { callbacks.onMoveUp(mode, index) }, enabled = index > 0) { Text("▲") }
        TextButton(onClick = { callbacks.onMoveDown(mode, index) }, enabled = index < lastIndex) { Text("▼") }
    }
}

// Representative values so the config rows show a concrete sample of each metric's human format.
private val SAMPLE_STATE = ActivityState(
    distanceMeters = 5_166.0, // ~3.21 mi
    recentPaceSecPerKm = 317.0,
    elapsedMs = 1_923_000L, // 00:32:03
    headingDeg = 45f,
    altitudeM = 376.0, // ~1,234 ft
    pressureHpa = 1013.0,
)
