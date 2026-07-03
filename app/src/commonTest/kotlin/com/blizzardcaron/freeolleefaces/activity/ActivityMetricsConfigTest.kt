package com.blizzardcaron.freeolleefaces.activity

import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityMetricsConfigTest {

    @Test fun default_recording_order_is_all_seven() {
        assertEquals(
            listOf(
                ActivityMetric.PACE, ActivityMetric.AVG_PACE, ActivityMetric.DISTANCE,
                ActivityMetric.TIME, ActivityMetric.ORIENTATION, ActivityMetric.ALTITUDE,
                ActivityMetric.PRESSURE,
            ),
            ActivityMetricsConfig.DEFAULT.enabledOrder(),
        )
    }

    @Test fun moveDown_swaps_recording_pair() {
        val c = ActivityMetricsConfig.DEFAULT.moveDown(0)
        assertEquals(
            listOf(ActivityMetric.AVG_PACE, ActivityMetric.PACE, ActivityMetric.DISTANCE),
            c.enabledOrder().take(3),
        )
    }

    @Test fun moveUp_out_of_bounds_is_noop() {
        val c = ActivityMetricsConfig.DEFAULT.moveUp(0)
        assertEquals(ActivityMetricsConfig.DEFAULT.recording, c.recording)
    }

    @Test fun setEnabled_disables_metric_excluding_it_from_order() {
        val c = ActivityMetricsConfig.DEFAULT.setEnabled(ActivityMetric.PRESSURE, false)
        assertEquals(
            listOf(
                ActivityMetric.PACE, ActivityMetric.AVG_PACE, ActivityMetric.DISTANCE,
                ActivityMetric.TIME, ActivityMetric.ORIENTATION, ActivityMetric.ALTITUDE,
            ),
            c.enabledOrder(),
        )
    }

    @Test fun setEnabled_cannot_disable_last_enabled_metric() {
        var c = ActivityMetricsConfig(recording = listOf(ActivityMetricItem(ActivityMetric.ORIENTATION)))
        c = c.setEnabled(ActivityMetric.ORIENTATION, false)
        assertEquals(listOf(ActivityMetric.ORIENTATION), c.enabledOrder())
    }

    @Test fun reEnabling_keeps_position() {
        val c = ActivityMetricsConfig.DEFAULT
            .setEnabled(ActivityMetric.TIME, false)
            .setEnabled(ActivityMetric.TIME, true)
        assertEquals(3, c.recording.indexOfFirst { it.metric == ActivityMetric.TIME })
    }
}
