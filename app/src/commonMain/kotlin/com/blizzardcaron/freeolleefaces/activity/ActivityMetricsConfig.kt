package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.timer.Reorder

/** One configurable metric row: the metric and whether it is enabled (cycled + shown). */
data class ActivityMetricItem(val metric: ActivityMetric, val enabled: Boolean = true)

/**
 * The user's recording-metric configuration: an ordered, individually-toggleable list.
 * All ops are pure (return a new config). Invariant: always >= 1 enabled metric.
 */
data class ActivityMetricsConfig(val recording: List<ActivityMetricItem>) {

    fun enabledOrder(): List<ActivityMetric> = recording.filter { it.enabled }.map { it.metric }

    fun moveUp(index: Int): ActivityMetricsConfig = copy(recording = Reorder.moveUp(recording, index))

    fun moveDown(index: Int): ActivityMetricsConfig = copy(recording = Reorder.moveDown(recording, index))

    fun setEnabled(metric: ActivityMetric, enabled: Boolean): ActivityMetricsConfig {
        // Guard the >= 1 invariant: refuse to disable the only remaining enabled metric.
        val wouldViolateInvariant = !enabled &&
            recording.count { it.enabled } <= 1 &&
            recording.any { it.metric == metric && it.enabled }
        if (wouldViolateInvariant) {
            return this
        }
        return copy(recording = recording.map { if (it.metric == metric) it.copy(enabled = enabled) else it })
    }

    companion object {
        val RECORDING_METRICS = listOf(
            ActivityMetric.PACE,
            ActivityMetric.AVG_PACE,
            ActivityMetric.DISTANCE,
            ActivityMetric.TIME,
            ActivityMetric.ORIENTATION,
            ActivityMetric.ALTITUDE,
            ActivityMetric.PRESSURE,
        )

        val DEFAULT = ActivityMetricsConfig(recording = RECORDING_METRICS.map { ActivityMetricItem(it) })
    }
}
