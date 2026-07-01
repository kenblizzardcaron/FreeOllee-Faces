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
            ActivityMetricsConfig.DEFAULT.enabledOrder(ActivityMode.RECORDING),
        )
    }

    @Test fun default_glance_order_is_three_instruments() {
        assertEquals(
            listOf(ActivityMetric.ORIENTATION, ActivityMetric.ALTITUDE, ActivityMetric.PRESSURE),
            ActivityMetricsConfig.DEFAULT.enabledOrder(ActivityMode.GLANCE),
        )
    }

    @Test fun moveDown_swaps_recording_pair() {
        val c = ActivityMetricsConfig.DEFAULT.moveDown(ActivityMode.RECORDING, 0)
        assertEquals(
            listOf(ActivityMetric.AVG_PACE, ActivityMetric.PACE, ActivityMetric.DISTANCE),
            c.enabledOrder(ActivityMode.RECORDING).take(3),
        )
    }

    @Test fun moveUp_out_of_bounds_is_noop() {
        val c = ActivityMetricsConfig.DEFAULT.moveUp(ActivityMode.RECORDING, 0)
        assertEquals(ActivityMetricsConfig.DEFAULT.recording, c.recording)
    }

    @Test fun setEnabled_disables_metric_excluding_it_from_order() {
        val c = ActivityMetricsConfig.DEFAULT.setEnabled(ActivityMode.GLANCE, ActivityMetric.PRESSURE, false)
        assertEquals(
            listOf(ActivityMetric.ORIENTATION, ActivityMetric.ALTITUDE),
            c.enabledOrder(ActivityMode.GLANCE),
        )
    }

    @Test fun setEnabled_cannot_disable_last_enabled_metric() {
        var c = ActivityMetricsConfig.DEFAULT
            .setEnabled(ActivityMode.GLANCE, ActivityMetric.PRESSURE, false)
            .setEnabled(ActivityMode.GLANCE, ActivityMetric.ALTITUDE, false)
        // Only ORIENTATION enabled now; disabling it must be a no-op.
        c = c.setEnabled(ActivityMode.GLANCE, ActivityMetric.ORIENTATION, false)
        assertEquals(listOf(ActivityMetric.ORIENTATION), c.enabledOrder(ActivityMode.GLANCE))
    }

    @Test fun reEnabling_keeps_position() {
        val c = ActivityMetricsConfig.DEFAULT
            .setEnabled(ActivityMode.RECORDING, ActivityMetric.TIME, false)
            .setEnabled(ActivityMode.RECORDING, ActivityMetric.TIME, true)
        assertEquals(3, c.recording.indexOfFirst { it.metric == ActivityMetric.TIME })
    }
}
