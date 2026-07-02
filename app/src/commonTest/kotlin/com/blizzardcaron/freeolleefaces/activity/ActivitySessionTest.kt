package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.location.Coords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivitySessionTest {

    private fun fix(lat: Double, lng: Double, acc: Float? = 5f) = Coords(lat, lng, acc, "gps")

    @Test fun accumulates_haversine_distance_between_accepted_fixes() {
        val s = ActivitySession(startedAtMs = 0L)
        s.onSample(fix(0.0, 0.0), 0L)
        s.onSample(fix(0.001, 0.0), 10_000L) // ~111.3 m north
        assertEquals(111.3, s.distanceMeters, 1.0)
    }

    @Test fun drops_low_accuracy_fixes() {
        val s = ActivitySession(startedAtMs = 0L)
        s.onSample(fix(0.0, 0.0), 0L)
        s.onSample(fix(0.001, 0.0, acc = 100f), 10_000L) // accuracy worse than 30 m gate
        assertEquals(0.0, s.distanceMeters, 1e-9)
    }

    @Test fun ignores_sub_minimum_jitter() {
        val s = ActivitySession(startedAtMs = 0L)
        s.onSample(fix(0.0, 0.0), 0L)
        s.onSample(fix(0.000005, 0.0), 2_000L) // ~0.55 m, below the 2 m floor
        assertEquals(0.0, s.distanceMeters, 1e-9)
    }

    @Test fun pace_is_null_until_window_has_enough_span() {
        val s = ActivitySession(startedAtMs = 0L)
        s.onSample(fix(0.0, 0.0), 0L)
        assertNull(s.recentPaceSecPerKm(0L))
    }

    @Test fun pace_reflects_recent_speed() {
        val s = ActivitySession(startedAtMs = 0L)
        // 200 m over 60 s == 0.2 km in 60 s == 300 sec/km
        s.onSample(fix(0.0, 0.0), 0L)
        s.onSample(fix(0.0017986, 0.0), 60_000L) // ~200 m north
        val pace = s.recentPaceSecPerKm(60_000L)
        assertTrue(pace != null && kotlin.math.abs(pace - 300.0) < 15.0, "pace=$pace")
    }

    @Test fun state_carries_distance_elapsed_and_selected_metric() {
        val s = ActivitySession(startedAtMs = 1_000L)
        s.onSample(fix(0.0, 0.0), 1_000L)
        val st = s.state(ActivityMetric.DISTANCE, 4_000L)
        assertEquals(ActivityMetric.DISTANCE, st.selectedMetric)
        assertEquals(3_000L, st.elapsedMs)
        assertTrue(st.running)
    }

    @Test fun movingTimeExcludesPausedSpan() {
        val s = ActivitySession(startedAtMs = 0L)
        s.pause(10_000L)
        s.resume(15_000L)              // 5s paused
        assertEquals(25_000L, s.movingTimeMs(30_000L)) // 30s elapsed - 5s paused
    }

    @Test fun movingTimeCountsOngoingPause() {
        val s = ActivitySession(startedAtMs = 0L)
        s.pause(10_000L)
        assertEquals(10_000L, s.movingTimeMs(25_000L)) // frozen at pause instant
    }

    @Test fun distanceDoesNotAccrueWhilePaused() {
        val s = ActivitySession(startedAtMs = 0L)
        s.onSample(Coords(0.0, 0.0, 5f, "gps"), 0L)
        s.pause(1_000L)
        s.onSample(Coords(0.01, 0.0, 5f, "gps"), 2_000L) // ~1.1 km jump, but paused
        assertEquals(0.0, s.distanceMeters)
        s.resume(3_000L)
        s.onSample(Coords(0.01, 0.0, 5f, "gps"), 4_000L) // same spot as resume -> no teleport
        assertEquals(0.0, s.distanceMeters)
    }

    @Test fun pace_does_not_span_the_paused_gap_after_resume() {
        val s = ActivitySession(startedAtMs = 0L)
        // accrue a couple of pre-pause marks
        s.onSample(fix(0.0, 0.0), 0L)
        s.onSample(fix(0.0005, 0.0), 5_000L) // ~55 m
        s.pause(10_000L)
        s.resume(70_000L) // 60 s paused gap
        // one post-resume sample: without a window reset this would span the 60 s gap
        s.onSample(fix(0.0006, 0.0), 71_000L)
        assertNull(s.recentPaceSecPerKm(71_000L))
        // two more samples spanning >=5s of real post-resume movement -> pace comes back
        s.onSample(fix(0.0012, 0.0), 74_000L)
        s.onSample(fix(0.0018, 0.0), 77_000L)
        val pace = s.recentPaceSecPerKm(77_000L)
        assertTrue(pace != null && pace > 0.0, "expected a sane pace once the window refills, was $pace")
    }
}
