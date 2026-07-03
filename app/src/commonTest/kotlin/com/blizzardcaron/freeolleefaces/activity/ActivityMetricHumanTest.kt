package com.blizzardcaron.freeolleefaces.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActivityMetricHumanTest {
    private val imperial = ActivityUnit.IMPERIAL
    private val metric = ActivityUnit.METRIC

    @Test fun pace_imperial() {
        // 8:30 /mi => 510 s/mi => secPerKm = 510 / (1609.344/1000)
        val st = ActivityState(recentPaceSecPerKm = 510.0 / (1609.344 / 1000.0))
        assertEquals("8:30 /mi", ActivityMetric.PACE.human(st, imperial))
    }

    @Test fun pace_null_when_no_pace() {
        assertNull(ActivityMetric.PACE.human(ActivityState(), imperial))
    }

    @Test fun distance_imperial() {
        val st = ActivityState(distanceMeters = 1609.344 * 3.21)
        assertEquals("3.21 mi", ActivityMetric.DISTANCE.human(st, imperial))
    }

    @Test fun distance_metric() {
        val st = ActivityState(distanceMeters = 5000.0)
        assertEquals("5.00 km", ActivityMetric.DISTANCE.human(st, metric))
    }

    @Test fun time_hms() {
        // Never paused, so movingTimeMs mirrors elapsedMs.
        val elapsedMs = ((1 * 3600) + (2 * 60) + 3) * 1000L
        val st = ActivityState(elapsedMs = elapsedMs, movingTimeMs = elapsedMs)
        assertEquals("01:02:03", ActivityMetric.TIME.human(st, imperial))
    }

    @Test fun time_human_freezes_at_moving_time_during_pause() {
        // 10:00 elapsed wall-clock, but only 5:00 of it was actually moving (paused for 5:00).
        val st = ActivityState(elapsedMs = 600_000L, movingTimeMs = 300_000L)
        assertEquals("00:05:00", ActivityMetric.TIME.human(st, imperial))
    }

    @Test fun orientation_cardinal() {
        val st = ActivityState(headingDeg = 45f)
        assertEquals("045° NE", ActivityMetric.ORIENTATION.human(st, imperial))
    }

    @Test fun orientation_null_when_no_heading() {
        assertNull(ActivityMetric.ORIENTATION.human(ActivityState(), imperial))
    }

    @Test fun altitude_imperial_grouped() {
        val st = ActivityState(altitudeM = 1234.0 / 3.28084)
        assertEquals("1,234 ft", ActivityMetric.ALTITUDE.human(st, imperial))
    }

    @Test fun altitude_metric() {
        val st = ActivityState(altitudeM = 1500.0)
        assertEquals("1,500 m", ActivityMetric.ALTITUDE.human(st, metric))
    }

    @Test fun altitude_null_when_absent() {
        assertNull(ActivityMetric.ALTITUDE.human(ActivityState(), imperial))
    }

    @Test fun pressure_imperial_inhg() {
        val st = ActivityState(pressureHpa = 1013.0)
        assertEquals("29.91 inHg", ActivityMetric.PRESSURE.human(st, imperial))
    }

    @Test fun pressure_metric_hpa() {
        val st = ActivityState(pressureHpa = 1013.4)
        assertEquals("1013 hPa", ActivityMetric.PRESSURE.human(st, metric))
    }

    @Test fun pressure_null_when_absent() {
        assertNull(ActivityMetric.PRESSURE.human(ActivityState(), imperial))
    }
}
