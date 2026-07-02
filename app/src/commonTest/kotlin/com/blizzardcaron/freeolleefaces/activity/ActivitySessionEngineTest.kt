package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
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

class ActivitySessionEngineTest {

    private class Harness {
        val ble = FakeBleClient()
        val store = FakeActivityTrackStore()
        val autoSleep = FakeSessionAutoSleep()
        val prefs = Prefs(MapSettings())
        var clockMs = 0L
        val engine = ActivitySessionEngine(
            ble = ble, store = store, prefs = prefs, autoSleep = autoSleep,
            watchAddress = { "AA:BB" }, now = { clockMs }, newId = { "trk" },
        )
        fun fix(lat: Double, lng: Double) = Coords(lat, lng, 5f, "gps")
        fun slowFix() = Coords(0.0, 0.0, 5f, "gps", speedMps = 0.0f)
        fun fastFix() = Coords(0.0, 0.0, 5f, "gps", speedMps = 5.0f)
    }

    @Test fun start_marks_running_and_disables_autosleep() = runTest {
        val h = Harness()
        h.engine.start()
        assertTrue(h.engine.state.value.running)
        assertEquals(listOf("disable(AA:BB)"), h.autoSleep.calls)
    }

    @Test fun ingest_records_a_track_point_and_updates_distance() = runTest {
        val h = Harness()
        h.engine.start()
        h.engine.ingest(h.fix(0.0, 0.0), 0L)
        h.engine.ingest(h.fix(0.001, 0.0), 10_000L) // ~111 m
        assertTrue(h.engine.state.value.distanceMeters > 100.0)
    }

    @Test fun tick_pushes_rendered_metric_to_nameplate() = runTest {
        val h = Harness()
        h.engine.start()
        h.engine.ingest(h.fix(0.0, 0.0), 0L)
        h.engine.tick(1_000L)
        assertTrue(
            h.ble.sentNameplate().isNotEmpty(),
            "expected a nameplate write, got ${h.ble.sentNameplate()}",
        )
    }

    @Test fun unchanged_render_within_spacing_pushes_only_once() = runTest {
        val h = Harness()
        h.engine.start()
        h.engine.tick(0L)        // first push (TIME defaults? no — PACE warmup "P --:-")
        val firstCount = h.ble.sentNameplate().size
        h.engine.tick(1_000L)    // identical render, within default 30s spacing
        assertEquals(firstCount, h.ble.sentNameplate().size)
    }

    @Test fun cycle_metric_forces_immediate_push_of_new_metric() = runTest {
        val h = Harness()
        h.engine.start()
        h.engine.tick(0L)
        val before = h.ble.sentNameplate().size
        h.engine.cycleMetric()   // PACE -> AVG_PACE
        h.engine.tick(1_000L)    // within spacing, but forced -> writes immediately
        assertEquals(before + 1, h.ble.sentNameplate().size)
        assertEquals(ActivityMetric.AVG_PACE, h.engine.state.value.selectedMetric)
    }

    @Test fun push_failure_marks_unreachable_but_keeps_state_running() = runTest {
        val h = Harness()
        h.ble.sendResult = Result.failure(IllegalStateException("link down"))
        h.engine.start()
        h.engine.tick(0L)
        assertFalse(h.engine.state.value.watchReachable)
        assertTrue(h.engine.state.value.running)
    }

    @Test fun engine_uses_configured_push_interval() = runTest {
        val h = Harness()
        h.prefs.activityPushIntervalMs = 3_000L
        h.engine.start()
        h.engine.ingest(h.fix(0.0, 0.0), 0L)
        h.engine.tick(0L)
        val first = h.ble.sentNameplate().size
        h.engine.tick(1_000L) // within 3s spacing -> no new write
        assertEquals(first, h.ble.sentNameplate().size)
        h.engine.tick(3_000L) // spacing elapsed -> a new write
        assertTrue(h.ble.sentNameplate().size > first)
    }

    @Test fun stop_saves_finalized_track_and_restores_autosleep() = runTest {
        val h = Harness()
        h.engine.start()
        h.engine.ingest(h.fix(0.0, 0.0), 0L)
        h.engine.ingest(h.fix(0.001, 0.0), 10_000L)
        h.clockMs = 12_000L
        h.engine.stop()
        val saved = h.store.latest()
        assertNotNull(saved)
        assertEquals("trk", saved.id)
        assertNotNull(saved.endedAtMs)
        assertNotNull(saved.summary)
        assertTrue(saved.points.size >= 2)
        assertEquals("restore(AA:BB)", h.autoSleep.calls.last())
        assertFalse(h.engine.state.value.running)
    }

    @Test fun manual_pause_sets_state_and_freezes_distance() = runTest {
        val h = Harness()
        h.engine.start()
        h.engine.ingest(h.fix(0.0, 0.0), 0L)
        h.engine.pause(1_000L)
        assertTrue(h.engine.state.value.paused)
        h.engine.ingest(h.fix(0.01, 0.0), 2_000L) // big jump while paused
        assertEquals(0.0, h.engine.state.value.distanceMeters)
        h.engine.resume(3_000L)
        assertFalse(h.engine.state.value.paused)
    }

    @Test fun tick_while_paused_pushes_paused_nameplate() = runTest {
        val h = Harness()
        h.engine.start()
        h.engine.ingest(h.fix(0.0, 0.0), 0L)
        h.engine.pause(1_000L)
        h.engine.tick(1_000L)
        assertTrue(h.ble.sentNameplate().any { it == "PAUSE " })
    }

    @Test fun pausedAtMs_survives_ingest_and_tick() = runTest {
        val h = Harness()
        h.engine.start()
        h.engine.pause(1_000L)
        assertEquals(1_000L, h.engine.state.value.pausedAtMs)
        h.engine.ingest(h.fix(0.0, 0.0), 2_000L)
        assertEquals(1_000L, h.engine.state.value.pausedAtMs)
        h.engine.tick(3_000L)
        assertEquals(1_000L, h.engine.state.value.pausedAtMs)
    }

    @Test fun auto_pause_after_slow_hold() = runTest {
        val h = Harness()
        h.prefs.autoPauseThresholdMps = 0.5f
        h.engine.start()
        h.engine.ingest(h.slowFix(), 0L)
        h.engine.ingest(h.slowFix(), 1_000L)
        h.engine.ingest(h.slowFix(), 2_000L)
        h.engine.ingest(h.slowFix(), 3_000L) // window full + 3s hold
        assertTrue(h.engine.state.value.paused)
    }

    @Test fun auto_resume_when_moving_again() = runTest {
        val h = Harness()
        h.prefs.autoPauseThresholdMps = 0.5f
        h.engine.start()
        repeat(4) { h.engine.ingest(h.slowFix(), it * 1_000L) }
        assertTrue(h.engine.state.value.paused)
        h.engine.ingest(h.fastFix(), 4_000L)
        assertFalse(h.engine.state.value.paused)
    }

    @Test fun manual_pause_not_lifted_by_movement() = runTest {
        val h = Harness()
        h.prefs.autoPauseThresholdMps = 0.5f
        h.engine.start()
        h.engine.pause(1_000L)
        h.engine.ingest(h.fastFix(), 2_000L) // moving fast, but manual pause holds
        assertTrue(h.engine.state.value.paused)
    }

    @Test fun saved_summary_uses_moving_time_not_elapsed() = runTest {
        val h = Harness()
        h.clockMs = 0L
        h.engine.start()
        h.engine.ingest(h.fix(0.0, 0.0), 0L)
        h.engine.ingest(h.fix(0.001, 0.0), 10_000L) // ~111 m over 10 s of movement
        h.engine.pause(10_000L)                      // pause at t=10s
        h.clockMs = 30_000L
        h.engine.stop()                              // stop at t=30s -> 20 s paused
        val s = h.store.latest()!!.summary!!
        assertEquals(30_000L, s.elapsedTimeMs)       // wall clock unchanged
        assertEquals(10_000L, s.movingTimeMs)        // excludes the 20 s pause
        // avg pace over moving time (~10s / 0.111km ~= 90 s/km); elapsed-based would be ~270 s/km
        assertTrue(s.avgPaceSecPerKm < 150.0, "avg pace must be moving-time based, was ${s.avgPaceSecPerKm}")
        assertTrue(s.avgPaceSecPerKm > 0.0)
    }

    @Test fun resume_after_manual_pause_requires_a_fresh_slow_streak() = runTest {
        val h = Harness()
        h.prefs.autoPauseThresholdMps = 0.5f
        h.engine.start()
        h.engine.pause(1_000L)
        // stale auto-pause hold accrues while manually paused (ignored -> manual precedence)
        h.engine.ingest(h.slowFix(), 2_000L)
        h.engine.ingest(h.slowFix(), 3_000L)
        h.engine.ingest(h.slowFix(), 4_000L)
        h.engine.ingest(h.slowFix(), 5_000L) // window full + 3s hold satisfied, but still manual
        assertTrue(h.engine.state.value.paused)
        h.engine.resume(6_000L)
        assertFalse(h.engine.state.value.paused)
        h.engine.ingest(h.slowFix(), 6_100L) // one sample right after resume
        assertFalse(h.engine.state.value.paused, "must not instantly re-pause after an explicit resume")
    }

    @Test fun glance_does_not_auto_pause_when_stationary() = runTest {
        val h = Harness()
        h.engine.startLive()
        h.engine.ingest(h.slowFix(), 0L)
        h.engine.ingest(h.slowFix(), 1_000L)
        h.engine.ingest(h.slowFix(), 2_000L)
        h.engine.ingest(h.slowFix(), 3_000L) // past the 3 s auto-pause hold
        assertFalse(h.engine.state.value.paused)
    }
}
