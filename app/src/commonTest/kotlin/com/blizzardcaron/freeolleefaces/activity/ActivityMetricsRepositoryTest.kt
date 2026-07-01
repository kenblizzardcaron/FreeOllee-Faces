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
        repo.moveDown(ActivityMode.RECORDING, 0)
        assertEquals(
            listOf(ActivityMetric.AVG_PACE, ActivityMetric.PACE),
            ActivityMetricsRepository(settings).get().enabledOrder(ActivityMode.RECORDING).take(2),
        )
    }

    @Test fun setEnabled_persists() {
        val settings = MapSettings()
        val repo = ActivityMetricsRepository(settings)
        repo.setEnabled(ActivityMode.GLANCE, ActivityMetric.PRESSURE, false)
        assertEquals(
            listOf(ActivityMetric.ORIENTATION, ActivityMetric.ALTITUDE),
            ActivityMetricsRepository(settings).get().enabledOrder(ActivityMode.GLANCE),
        )
    }

    @Test fun setEnabled_last_enabled_is_noop() {
        val settings = MapSettings()
        val repo = ActivityMetricsRepository(settings)
        repo.setEnabled(ActivityMode.GLANCE, ActivityMetric.PRESSURE, false)
        repo.setEnabled(ActivityMode.GLANCE, ActivityMetric.ALTITUDE, false)
        repo.setEnabled(ActivityMode.GLANCE, ActivityMetric.ORIENTATION, false)
        assertEquals(
            listOf(ActivityMetric.ORIENTATION),
            repo.get().enabledOrder(ActivityMode.GLANCE),
        )
    }
}
