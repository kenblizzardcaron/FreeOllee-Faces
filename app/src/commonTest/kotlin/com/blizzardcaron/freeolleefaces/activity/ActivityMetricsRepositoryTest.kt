package com.blizzardcaron.freeolleefaces.activity

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityMetricsRepositoryTest {

    @Test fun get_defaults_when_empty() {
        val repo = ActivityMetricsRepository(MapSettings())
        assertEquals(ActivityMetricsConfig.DEFAULT, repo.get())
    }

    @Test fun moveDown_persists() {
        val settings = MapSettings()
        val repo = ActivityMetricsRepository(settings)
        repo.moveDown(0)
        assertEquals(
            listOf(ActivityMetric.AVG_PACE, ActivityMetric.PACE),
            ActivityMetricsRepository(settings).get().enabledOrder().take(2),
        )
    }

    @Test fun setEnabled_persists() {
        val settings = MapSettings()
        val repo = ActivityMetricsRepository(settings)
        repo.setEnabled(ActivityMetric.PRESSURE, false)
        assertEquals(
            listOf(
                ActivityMetric.PACE, ActivityMetric.AVG_PACE, ActivityMetric.DISTANCE,
                ActivityMetric.TIME, ActivityMetric.ORIENTATION, ActivityMetric.ALTITUDE,
            ),
            ActivityMetricsRepository(settings).get().enabledOrder(),
        )
    }

    @Test fun setEnabled_last_enabled_is_noop() {
        val settings = MapSettings()
        val repo = ActivityMetricsRepository(settings)
        for (metric in ActivityMetricsConfig.RECORDING_METRICS.drop(1)) {
            repo.setEnabled(metric, false)
        }
        repo.setEnabled(ActivityMetricsConfig.RECORDING_METRICS.first(), false)
        assertEquals(
            listOf(ActivityMetricsConfig.RECORDING_METRICS.first()),
            repo.get().enabledOrder(),
        )
    }
}
