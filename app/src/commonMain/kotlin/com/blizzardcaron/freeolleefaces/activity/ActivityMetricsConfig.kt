package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.timer.Reorder

/** One configurable metric row: the metric and whether it is enabled (cycled + shown). */
data class ActivityMetricItem(val metric: ActivityMetric, val enabled: Boolean = true)

/**
 * The user's per-mode metric configuration: an ordered, individually-toggleable list for the
 * recording session (all six metrics) and for the non-recording glance (the three instruments).
 * All ops are pure (return a new config). Invariant: each mode always keeps >= 1 enabled metric.
 */
data class ActivityMetricsConfig(
    val recording: List<ActivityMetricItem>,
    val glance: List<ActivityMetricItem>,
) {
    fun forMode(mode: ActivityMode): List<ActivityMetricItem> =
        if (mode == ActivityMode.RECORDING) recording else glance

    fun enabledOrder(mode: ActivityMode): List<ActivityMetric> =
        forMode(mode).filter { it.enabled }.map { it.metric }

    fun moveUp(mode: ActivityMode, index: Int): ActivityMetricsConfig =
        withMode(mode, Reorder.moveUp(forMode(mode), index))

    fun moveDown(mode: ActivityMode, index: Int): ActivityMetricsConfig =
        withMode(mode, Reorder.moveDown(forMode(mode), index))

    fun setEnabled(
        mode: ActivityMode,
        metric: ActivityMetric,
        enabled: Boolean,
    ): ActivityMetricsConfig {
        val current = forMode(mode)
        // Guard the >= 1 invariant: refuse to disable the only remaining enabled metric.
        val wouldViolateInvariant = !enabled &&
            current.count { it.enabled } <= 1 &&
            current.any { it.metric == metric && it.enabled }
        if (wouldViolateInvariant) {
            return this
        }
        val updated = current.map { if (it.metric == metric) it.copy(enabled = enabled) else it }
        return withMode(mode, updated)
    }

    private fun withMode(mode: ActivityMode, list: List<ActivityMetricItem>): ActivityMetricsConfig =
        if (mode == ActivityMode.RECORDING) copy(recording = list) else copy(glance = list)

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
        val GLANCE_METRICS = listOf(
            ActivityMetric.ORIENTATION,
            ActivityMetric.ALTITUDE,
            ActivityMetric.PRESSURE,
        )

        val DEFAULT = ActivityMetricsConfig(
            recording = RECORDING_METRICS.map { ActivityMetricItem(it) },
            glance = GLANCE_METRICS.map { ActivityMetricItem(it) },
        )
    }
}
