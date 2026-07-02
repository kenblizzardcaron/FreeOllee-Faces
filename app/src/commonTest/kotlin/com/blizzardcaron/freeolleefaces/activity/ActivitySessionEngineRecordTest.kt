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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ActivitySessionEngineRecordTest {
    private fun engine(store: FakeActivityTrackStore) = ActivitySessionEngine(
        ble = FakeBleClient(), store = store, prefs = Prefs(MapSettings()),
        autoSleep = FakeSessionAutoSleep(), watchAddress = { "AA:BB" }, now = { 0L }, newId = { "trk" },
    )

    private class ClockHarness {
        val store = FakeActivityTrackStore()
        var clockMs = 0L
        val engine = ActivitySessionEngine(
            ble = FakeBleClient(), store = store, prefs = Prefs(MapSettings()),
            autoSleep = FakeSessionAutoSleep(), watchAddress = { "AA:BB" }, now = { clockMs }, newId = { "trk" },
        )
        fun fix(lat: Double, lng: Double) = Coords(lat, lng, 5f, "gps")
    }

    @Test fun begin_recording_upgrades_live_session() = runTest {
        val store = FakeActivityTrackStore()
        val e = engine(store)
        e.startLive()
        assertFalse(e.state.value.recording)
        e.beginRecording()
        assertTrue(e.state.value.recording)
        assertEquals(ActivityMetric.PACE, e.state.value.selectedMetric)
        e.stop()
        assertNotNull(store.latest())
    }

    @Test fun begin_recording_cold_starts_when_idle() = runTest {
        val store = FakeActivityTrackStore()
        val e = engine(store)
        e.beginRecording()
        assertTrue(e.state.value.recording)
    }

    @Test fun begin_recording_does_not_leak_glance_time_or_distance() = runTest {
        val h = ClockHarness()
        h.engine.startLive()
        h.engine.ingest(h.fix(0.0, 0.0), h.clockMs)
        h.clockMs = 60_000L
        h.engine.ingest(h.fix(0.001, 0.0), h.clockMs) // ~111 m walked during the glance
        h.clockMs = 300_000L
        h.engine.beginRecording()
        h.engine.ingest(h.fix(0.001, 0.0), h.clockMs) // bootstrap fix for the fresh session
        h.clockMs = 310_000L
        h.engine.ingest(h.fix(0.002, 0.0), h.clockMs) // ~111 m walked while recording
        h.clockMs = 900_000L
        h.engine.stop()
        val s = h.store.latest()!!.summary!!
        assertEquals(600_000L, s.elapsedTimeMs)
        assertTrue(s.movingTimeMs <= s.elapsedTimeMs, "moving ${s.movingTimeMs} > elapsed ${s.elapsedTimeMs}")
        assertTrue(s.distanceM in 100.0..130.0, "distance leaked glance movement: ${s.distanceM}")
    }
}
