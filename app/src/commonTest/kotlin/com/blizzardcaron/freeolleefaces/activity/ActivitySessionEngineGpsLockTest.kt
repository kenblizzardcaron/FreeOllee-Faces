package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.fakes.FakeActivityTrackStore
import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import com.blizzardcaron.freeolleefaces.fakes.FakeSessionAutoSleep
import com.blizzardcaron.freeolleefaces.location.Coords
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivitySessionEngineGpsLockTest {
    private fun ble() = FakeBleClient()
    private fun engine(
        ble: FakeBleClient,
        config: ActivityMetricsConfig = ActivityMetricsConfig.DEFAULT,
    ) = ActivitySessionEngine(
        ble = ble, store = FakeActivityTrackStore(), prefs = Prefs(MapSettings()),
        autoSleep = FakeSessionAutoSleep(), watchAddress = { "AA:BB" },
        now = { 0L }, newId = { "trk" }, metricsConfig = { config },
    )

    private fun coords() = Coords(lat = 1.0, lng = 2.0, accuracyM = 5f, provider = "gps")

    // Orientation-first recording order: preserves the GPS-lock coverage below (pinned to a
    // GPS-derived metric being selected), now that the engine only ever records.
    private val orientationFirstConfig = ActivityMetricsConfig(
        recording = listOf(
            ActivityMetricItem(ActivityMetric.ORIENTATION),
            ActivityMetricItem(ActivityMetric.PRESSURE),
            ActivityMetricItem(ActivityMetric.PACE),
        ),
    )

    @Test fun hasFix_false_until_first_ingest_then_sticky() = runTest {
        val e = engine(ble())
        e.start()
        assertFalse(e.state.value.hasFix)
        e.ingest(coords(), nowMs = 0L)
        assertTrue(e.state.value.hasFix)
    }

    @Test fun tick_pushes_gps_token_before_first_fix() = runTest {
        val ble = ble()
        val e = engine(ble, orientationFirstConfig)
        e.start() // selected = ORIENTATION (GPS-derived)
        e.tick(nowMs = 0L)
        // Pin the exact 6-cell token, not just the trimmed text: the cell width is the binding
        // wire constraint, so a regression in the padding must fail here.
        assertEquals(" GPS  ", ble.sentNameplate().last())
        assertEquals(6, ble.sentNameplate().last().length)
    }

    @Test fun pressure_pushes_real_value_before_first_fix() = runTest {
        val ble = ble()
        val e = engine(ble, orientationFirstConfig)
        e.start() // selected = ORIENTATION
        e.cycleMetric() // -> PRESSURE
        e.ingestPressure(1013.0)
        e.tick(nowMs = 0L)
        // Pressure is not GPS-derived: its real wire value, not the GPS token.
        assertFalse(ble.sentNameplate().last().trim() == "GPS")
    }

    @Test fun tick_pushes_metric_after_fix() = runTest {
        val ble = ble()
        val e = engine(ble)
        e.start() // recording; selected = PACE
        e.ingest(coords(), nowMs = 0L)
        e.tick(nowMs = 2_000L)
        assertFalse(ble.sentNameplate().last().trim() == "GPS")
    }

    @Test fun start_selects_first_enabled_recording_metric() = runTest {
        val config = ActivityMetricsConfig.DEFAULT.setEnabled(ActivityMetric.PACE, false)
        val e = engine(ble(), config)
        e.start()
        assertEquals(ActivityMetric.AVG_PACE, e.state.value.selectedMetric)
    }

    @Test fun cycle_skips_disabled_recording_metric() = runTest {
        val config = ActivityMetricsConfig(
            recording = listOf(
                ActivityMetricItem(ActivityMetric.ORIENTATION),
                ActivityMetricItem(ActivityMetric.PRESSURE, enabled = false),
                ActivityMetricItem(ActivityMetric.PACE),
            ),
        )
        val e = engine(ble(), config)
        e.start() // ORIENTATION
        e.cycleMetric() // skips disabled PRESSURE -> PACE
        assertEquals(ActivityMetric.PACE, e.state.value.selectedMetric)
    }
}
