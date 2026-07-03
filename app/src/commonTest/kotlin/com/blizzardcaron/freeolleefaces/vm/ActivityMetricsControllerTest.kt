package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.activity.ActivityMetric
import com.blizzardcaron.freeolleefaces.activity.ActivityMetricsConfig
import com.blizzardcaron.freeolleefaces.activity.ActivityMetricsRepository
import com.blizzardcaron.freeolleefaces.activity.ActivitySessionLauncher
import com.blizzardcaron.freeolleefaces.activity.ActivityState
import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [ActivityController]'s metric-config surface: thin delegation to an injected
 * [ActivityMetricsRepository], mirroring how [ActivityController] already delegates lifecycle to
 * its [ActivitySessionLauncher].
 */
class ActivityMetricsControllerTest {

    private class FakeLauncher : ActivitySessionLauncher {
        override val state: StateFlow<ActivityState> = MutableStateFlow(ActivityState())
        override fun start() {}
        override fun stop() {}
        override fun cycleMetric() {}
        override fun setUnit(unit: ActivityUnit) {}
        override fun pause() {}
        override fun resume() {}
    }

    private fun controller(repo: ActivityMetricsRepository) = ActivityController(
        launcher = FakeLauncher(),
        prefs = Prefs(MapSettings()),
        hasLocationPermission = { true },
        showSnackbar = {},
        metricsRepo = repo,
    )

    @Test fun metricsConfig_reflects_repo_default() {
        val repo = ActivityMetricsRepository(MapSettings())
        val c = controller(repo)

        assertEquals(ActivityMetricsConfig.DEFAULT, c.metricsConfig())
    }

    @Test fun moveMetricUp_delegates_to_repo_and_is_visible_via_metricsConfig() {
        val repo = ActivityMetricsRepository(MapSettings())
        val c = controller(repo)
        // RECORDING_METRICS[1] is AVG_PACE; moving index 1 up swaps it with PACE at index 0.
        val before = c.metricsConfig().recording.map { it.metric }
        assertEquals(ActivityMetric.PACE, before[0])
        assertEquals(ActivityMetric.AVG_PACE, before[1])

        c.moveMetricUp(1)

        val after = c.metricsConfig().recording.map { it.metric }
        assertEquals(ActivityMetric.AVG_PACE, after[0])
        assertEquals(ActivityMetric.PACE, after[1])
        assertEquals(repo.get(), c.metricsConfig(), "controller reads through the same repo it wrote")
    }

    @Test fun moveMetricDown_delegates_to_repo_and_is_visible_via_metricsConfig() {
        val repo = ActivityMetricsRepository(MapSettings())
        val c = controller(repo)
        val before = c.metricsConfig().recording.map { it.metric }
        assertEquals(ActivityMetric.PACE, before[0])
        assertEquals(ActivityMetric.AVG_PACE, before[1])

        c.moveMetricDown(0)

        val after = c.metricsConfig().recording.map { it.metric }
        assertEquals(ActivityMetric.AVG_PACE, after[0])
        assertEquals(ActivityMetric.PACE, after[1])
        assertEquals(repo.get(), c.metricsConfig())
    }

    @Test fun setMetricEnabled_disables_a_metric_and_is_visible_via_metricsConfig() {
        val repo = ActivityMetricsRepository(MapSettings())
        val c = controller(repo)

        c.setMetricEnabled(ActivityMetric.PRESSURE, false)

        val item = c.metricsConfig().recording.first { it.metric == ActivityMetric.PRESSURE }
        assertEquals(false, item.enabled)
        assertEquals(repo.get(), c.metricsConfig())
    }

    @Test fun setMetricEnabled_refuses_to_disable_the_last_enabled_metric() {
        val repo = ActivityMetricsRepository(MapSettings())
        val c = controller(repo)
        // Drive recording down to a single enabled metric, then verify the invariant survives the
        // controller call (the repo/config already enforce it — this checks the delegation, not
        // the invariant logic itself, which is covered in ActivityMetricsConfigTest).
        for (metric in ActivityMetricsConfig.RECORDING_METRICS.drop(1)) {
            c.setMetricEnabled(metric, false)
        }
        val onlyEnabled = c.metricsConfig().recording.single { it.enabled }.metric

        c.setMetricEnabled(onlyEnabled, false)

        assertEquals(1, c.metricsConfig().recording.count { it.enabled })
    }
}
