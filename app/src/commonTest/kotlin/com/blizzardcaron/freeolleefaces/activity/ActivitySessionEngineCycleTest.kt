package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.fakes.FakeActivityTrackStore
import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import com.blizzardcaron.freeolleefaces.fakes.FakeSessionAutoSleep
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivitySessionEngineCycleTest {
    private fun engine() = ActivitySessionEngine(
        ble = FakeBleClient(), store = FakeActivityTrackStore(), prefs = Prefs(MapSettings()),
        autoSleep = FakeSessionAutoSleep(), watchAddress = { "AA:BB" }, now = { 0L }, newId = { "trk" },
    )

    @Test fun live_mode_cycles_orientation_altitude_pressure() = runTest {
        val e = engine()
        e.startLive()
        assertEquals(ActivityMetric.ORIENTATION, e.state.value.selectedMetric)
        e.cycleMetric()
        assertEquals(ActivityMetric.ALTITUDE, e.state.value.selectedMetric)
        e.cycleMetric()
        assertEquals(ActivityMetric.PRESSURE, e.state.value.selectedMetric)
        e.cycleMetric()
        assertEquals(ActivityMetric.ORIENTATION, e.state.value.selectedMetric)
    }

    @Test fun recording_cycles_all_seven() = runTest {
        val e = engine()
        e.start()
        val seen = buildList { repeat(7) { add(e.state.value.selectedMetric); e.cycleMetric() } }
        assertEquals(
            listOf(
                ActivityMetric.PACE, ActivityMetric.AVG_PACE, ActivityMetric.DISTANCE,
                ActivityMetric.TIME, ActivityMetric.ORIENTATION, ActivityMetric.ALTITUDE,
                ActivityMetric.PRESSURE,
            ),
            seen,
        )
    }
}
